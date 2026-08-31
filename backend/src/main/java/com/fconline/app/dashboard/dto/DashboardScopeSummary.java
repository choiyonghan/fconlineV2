package com.fconline.app.dashboard.dto;

import java.util.List;

/**
 * 한 유저의 "모두의 커스텀"(matchType=CUSTOM, 현재시즌 날짜 범위) 서머리 — 대시보드는 공식전
 * 스코프는 안 보여준다(요청). 지표 공식은 전부
 * {@code report.js}의 {@code renderPlayStyle}(플레이 성향 카드)이 라이브 페이지에서 쓰는 것과
 * 동일하게 맞췄다 — 새 지표를 여기서 발명하지 않는다:
 * <ul>
 *   <li>결정력 = 실제 득점 총합 − xG 총합(평균이 아니라 표본 전체 합계 기준, 라이브 페이지와 동일)</li>
 *   <li>평균 실점 xG값의 분모는 games가 아니라 concededSampleGames(상대도 추적 대상이라
 *       실점 슛 좌표를 복원할 수 있었던 경기 수)다 — games보다 작거나 같다.</li>
 *   <li>totalXaFor/avgXaFor(기대 어시스트, 2026-08-31 추가)는 팀 전체 슛 좌표에서 별도로 다시
 *       계산하지 않고, 이미 fetch한 players(TopPlayerResponse) 각각의 xa를 그냥 합산한다 —
 *       같은 슛 데이터를 두 번 조회할 필요가 없어서. avgXaFor는 totalXaFor/games다(다른
 *       avgXxxFor 필드들과 동일하게 games==0이면 0).</li>
 * </ul>
 * totalXxx 필드들은 대시보드 순위표(프리미어리그 순위표 스타일 — played/goals/xg/shots/sot 등)용
 * 합계값이다. avgXxx(경기당 평균)와 별도로 둔다 — 표는 합계로, 아코디언 상세는 평균으로 보여준다.
 */
public record DashboardScopeSummary(
        int games,
        int wins,
        int draws,
        int losses,
        double avgGoalsFor,
        double avgGoalsForXg,
        double avgXaFor,
        double finishing,
        double shotsPerGame,
        double avgGoalsAgainst,
        Double avgGoalsAgainstXg,
        int concededSampleGames,
        int totalGoalsFor,
        int totalGoalsAgainst,
        int totalShots,
        int totalShotsOnTarget,
        int totalPassTry,
        int totalPassSuccess,
        double totalXgFor,
        Double totalXgAgainst,
        double totalXaFor,
        int cleanSheets,
        double cleanSheetPct,
        int multiConcededGames,
        double multiConcededPct,
        int lowPossessionGames,
        double lowPossessionPct,
        int balancedPossessionGames,
        double balancedPossessionPct,
        int highPossessionGames,
        double highPossessionPct,
        double avgPossession,
        double avgRating,
        DashboardCombo combo,
        List<DashboardTopPlayer> topGoals,
        List<DashboardTopPlayer> topAssists,
        List<DashboardTopPlayer> topAttackPoints,
        List<DashboardTopPlayer> topDefense,
        List<DashboardTopPlayer> topSaves
) {
}
