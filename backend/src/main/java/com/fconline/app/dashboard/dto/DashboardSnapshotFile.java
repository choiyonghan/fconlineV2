package com.fconline.app.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * data/dashboard-snapshot.json 파일 스키마. GitHub Pages(site-root/index.html)가 백엔드를
 * 전혀 거치지 않고 raw.githubusercontent.com에서 이 파일을 직접 fetch한다(백엔드 콜드 스타트와
 * 무관하게 즉시 뜨게 하려는 목적 — data/insight-snapshots/*.json과 같은 컨벤션).
 *
 * users는 ouid를 키로 하는 맵 — DashboardRankingEntry.ouid로 조인해서 렌더링한다.
 */
public record DashboardSnapshotFile(
        Instant generatedAt,
        String currentSeasonName,
        boolean aiRankingFailed,
        String aiRankingNote,
        List<DashboardRankingEntry> ranking,
        Map<String, DashboardUserSnapshot> users
) {
}
