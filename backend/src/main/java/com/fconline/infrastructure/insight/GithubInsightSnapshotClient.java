package com.fconline.infrastructure.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fconline.domain.match.vo.MatchType;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * DB에 별도 테이블을 두는 대신, 매일 아침 GitHub Actions(insight-snapshot.yml)가
 * data/insight-snapshots/에 커밋해둔 스냅샷 JSON을 raw.githubusercontent.com에서
 * 그대로 읽어온다 — 커밋된 파일 자체가 "오늘의 스냅샷"이다.
 */
@Component
public class GithubInsightSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(GithubInsightSnapshotClient.class);

    private final RestClient insightSnapshotRestClient;
    private final ObjectMapper objectMapper;

    public GithubInsightSnapshotClient(RestClient insightSnapshotRestClient, ObjectMapper objectMapper) {
        this.insightSnapshotRestClient = insightSnapshotRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 스냅샷이 없거나(첫 실행 등) 조회에 실패하면 empty를 반환한다 — 호출부가 즉석 조립으로 폴백한다.
     *
     * raw.githubusercontent.com은 Accept 헤더와 무관하게 항상 Content-Type: text/plain으로
     * 내려준다 — RestClient의 기본 JSON 컨버터는 이 Content-Type을 JSON으로 인식 못 해서
     * body(GithubInsightSnapshotFile.class)로 바로 받으면 매번 UnknownContentTypeException으로
     * 깨진다(운영에서 실제로 겪은 버그 — 이 경로가 항상 실패해서 질문마다 즉석 조립이라는 훨씬
     * 무거운 폴백만 타고 있었음). String으로 받은 뒤 ObjectMapper로 직접 파싱해서 우회한다.
     */
    public Optional<GithubInsightSnapshotFile> fetch(String ouid, MatchType matchType) {
        String fileName = ouid + "_" + matchType.code() + ".json";
        try {
            String body = insightSnapshotRestClient.get()
                    .uri("/{fileName}", fileName)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(body, GithubInsightSnapshotFile.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("GitHub 인사이트 스냅샷 조회 실패, 즉석 조립으로 폴백합니다: ouid={}, matchType={}", ouid, matchType, e);
            return Optional.empty();
        }
    }
}
