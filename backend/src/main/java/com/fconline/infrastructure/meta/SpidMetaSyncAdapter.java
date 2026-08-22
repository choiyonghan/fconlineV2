package com.fconline.infrastructure.meta;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Nexon 정적 메타(spid.json: 선수ID -> 이름)를 서버측 캐시(player_meta)로 동기화한다.
 * v1은 이 대용량 정적 파일을 페이지 로드마다 브라우저가 재요청했다(analysis 6.10) —
 * v2는 배치가 주기적으로 갱신하고, 조회 API가 이미 캐시된 이름을 응답에 포함해 내려준다.
 *
 * sync.yml의 matrix(CUSTOM/OFFICIAL) 두 job이 각자 독립적으로 이 메서드를 호출하므로
 * (matchType과 무관하게 항상 실행), 두 job이 겹쳐 도는 구간에는 같은 spId를 동시에
 * 처음 보는 경우가 생긴다 — INSERT ... ON CONFLICT로 그 레이스를 원자적으로 처리한다.
 *
 * spid.json은 수만 건 규모라 건별 find-then-save/단건 upsert는 각각 별도 네트워크 왕복이
 * 필요해 실행 시간이 10분 이상 걸렸다(pooler까지의 왕복 지연이 크면 더 심함) — JdbcTemplate
 * batchUpdate + Hikari의 reWriteBatchedInserts(application.yml)로 청크 단위 다중 VALUES
 * INSERT로 묶어서 왕복 횟수를 batchSize분의 1로 줄인다.
 */
@Component
public class SpidMetaSyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(SpidMetaSyncAdapter.class);
    private static final String SPID_META_PATH = "/static/fconline/meta/spid.json";
    private static final int BATCH_SIZE = 500;

    private static final String UPSERT_SQL = """
            INSERT INTO v2.player_meta (sp_id, sp_name, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (sp_id) DO UPDATE
                SET sp_name = excluded.sp_name, updated_at = excluded.updated_at
                WHERE v2.player_meta.sp_name IS DISTINCT FROM excluded.sp_name
            """;

    private final RestClient nexonStaticRestClient;
    private final JdbcTemplate jdbcTemplate;

    public SpidMetaSyncAdapter(RestClient nexonStaticRestClient, JdbcTemplate jdbcTemplate) {
        this.nexonStaticRestClient = nexonStaticRestClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    public int syncAll() {
        JsonNode body = nexonStaticRestClient.get()
                .uri(SPID_META_PATH)
                .retrieve()
                .body(JsonNode.class);

        if (body == null || !body.isArray()) {
            log.warn("spid.json 응답이 배열 형태가 아닙니다.");
            return 0;
        }

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int processed = 0;
        for (JsonNode node : body) {
            String spId = node.path("id").asText(null);
            String spName = node.path("name").asText(null);
            if (spId == null || spName == null) {
                continue;
            }

            batch.add(new Object[]{spId, spName});
            if (batch.size() == BATCH_SIZE) {
                jdbcTemplate.batchUpdate(UPSERT_SQL, batch);
                processed += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(UPSERT_SQL, batch);
            processed += batch.size();
        }

        log.info("spid.json 동기화 완료: {}건 처리", processed);
        return processed;
    }
}
