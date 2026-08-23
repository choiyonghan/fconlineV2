package com.fconline.infrastructure.insight;

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

    public GithubInsightSnapshotClient(RestClient insightSnapshotRestClient) {
        this.insightSnapshotRestClient = insightSnapshotRestClient;
    }

    /** 스냅샷이 없거나(첫 실행 등) 조회에 실패하면 empty를 반환한다 — 호출부가 즉석 조립으로 폴백한다. */
    public Optional<GithubInsightSnapshotFile> fetch(String ouid, MatchType matchType) {
        String fileName = ouid + "_" + matchType.code() + ".json";
        try {
            GithubInsightSnapshotFile file = insightSnapshotRestClient.get()
                    .uri("/{fileName}", fileName)
                    .retrieve()
                    .body(GithubInsightSnapshotFile.class);
            return Optional.ofNullable(file);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("GitHub 인사이트 스냅샷 조회 실패, 즉석 조립으로 폴백합니다: ouid={}, matchType={}", ouid, matchType, e);
            return Optional.empty();
        }
    }
}
