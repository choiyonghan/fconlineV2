package com.fconline.infrastructure.insight;

import java.time.Instant;
import java.util.Map;

/**
 * data/insight-snapshots/{ouid}_{matchTypeCode}.json 파일 하나의 스키마.
 * matchType은 사람이 읽기 쉽게 이름(CUSTOM/OFFICIAL)으로 저장한다.
 */
public record GithubInsightSnapshotFile(
        String ouid,
        String matchType,
        Long seasonId,
        Instant snapshotAt,
        String summaryText,
        Map<String, String> opponentDetailByNickname
) {
}
