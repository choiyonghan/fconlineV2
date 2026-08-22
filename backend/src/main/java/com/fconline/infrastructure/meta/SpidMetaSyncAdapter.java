package com.fconline.infrastructure.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fconline.domain.meta.repository.PlayerMetaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Nexon 정적 메타(spid.json: 선수ID -> 이름)를 서버측 캐시(player_meta)로 동기화한다.
 * v1은 이 대용량 정적 파일을 페이지 로드마다 브라우저가 재요청했다(analysis 6.10) —
 * v2는 배치가 주기적으로 갱신하고, 조회 API가 이미 캐시된 이름을 응답에 포함해 내려준다.
 *
 * sync.yml의 matrix(CUSTOM/OFFICIAL) 두 job이 각자 독립적으로 이 메서드를 호출하므로
 * (matchType과 무관하게 항상 실행), 두 job이 겹쳐 도는 구간에는 같은 spId를 동시에
 * 처음 보는 경우가 생긴다 — findById 후 save()로는 이 레이스에서 unique 제약을 위반하고
 * 죽었다. PlayerMetaRepository.upsert()가 INSERT ... ON CONFLICT로 원자적으로 처리한다.
 */
@Component
public class SpidMetaSyncAdapter {

    private static final Logger log = LoggerFactory.getLogger(SpidMetaSyncAdapter.class);
    private static final String SPID_META_PATH = "/static/fconline/meta/spid.json";

    private final RestClient nexonStaticRestClient;
    private final PlayerMetaRepository playerMetaRepository;

    public SpidMetaSyncAdapter(RestClient nexonStaticRestClient, PlayerMetaRepository playerMetaRepository) {
        this.nexonStaticRestClient = nexonStaticRestClient;
        this.playerMetaRepository = playerMetaRepository;
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

        int updated = 0;
        for (JsonNode node : body) {
            String spId = node.path("id").asText(null);
            String spName = node.path("name").asText(null);
            if (spId == null || spName == null) {
                continue;
            }

            updated += playerMetaRepository.upsert(spId, spName);
        }

        log.info("spid.json 동기화 완료: {}건 갱신", updated);
        return updated;
    }
}
