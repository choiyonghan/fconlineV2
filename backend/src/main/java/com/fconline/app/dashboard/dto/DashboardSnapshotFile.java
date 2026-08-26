package com.fconline.app.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * data/dashboard-snapshot.json 파일 스키마. site-root/report.html(유저 칩 "전체")이 백엔드를
 * 전혀 거치지 않고 raw.githubusercontent.com에서 이 파일을 직접 fetch한다(백엔드 콜드 스타트와
 * 무관하게 즉시 뜨게 하려는 목적 — data/insight-snapshots/*.json과 같은 컨벤션).
 *
 * introText/outroText는 AI(성공 시) 또는 하드코딩된 대체 문구(실패 시, DashboardSnapshotBuilder의
 * FALLBACK_INTRO/FALLBACK_OUTRO 참고)가 채우는 "해설자 톤" 인트로/총평 문단이다.
 * users는 ouid를 키로 하는 맵 — DashboardRankingEntry.ouid로 조인해서 렌더링한다.
 */
public record DashboardSnapshotFile(
        Instant generatedAt,
        String currentSeasonName,
        boolean aiRankingFailed,
        String aiRankingNote,
        String introText,
        String outroText,
        List<DashboardRankingEntry> ranking,
        Map<String, DashboardUserSnapshot> users,
        /**
         * 9명 전원의 "전체 선수 스탯"을 풀링한 목록(최소 10경기 출전만) — 같은 spId를 여러 유저가
         * 각자 쓸 수 있어 유저별로 합치지 않고 행마다 nickname을 붙여 그대로 나열한다. 프론트가
         * 이 배열 하나로 TOP5 랭킹 여러 종류(득점/평균득점/도움/...)와 하단 정렬 가능한 전체 그리드를
         * 전부 계산한다(서버에서 카테고리별 TOP5를 미리 만들지 않음 — 프론트에서 정렬/슬라이스).
         */
        List<DashboardPooledPlayer> allPlayers
) {
}
