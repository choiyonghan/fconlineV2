package com.fconline.infrastructure.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fconline.domain.meta.PlayerMeta;
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
 * TODO(구현 착수 시 검증 필요): spid.json 응답의 실제 필드명("id"/"name" 추정)을 확인해 보정할 것.
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

            PlayerMeta existing = playerMetaRepository.findById(spId).orElse(null);
            if (existing == null) {
                playerMetaRepository.save(PlayerMeta.of(spId, spName));
                updated++;
            } else if (!existing.getSpName().equals(spName)) {
                existing.rename(spName);
                playerMetaRepository.save(existing);
                updated++;
            }
        }

        log.info("spid.json 동기화 완료: {}건 갱신", updated);
        return updated;
    }
}
