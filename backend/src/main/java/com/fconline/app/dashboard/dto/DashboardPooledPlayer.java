package com.fconline.app.dashboard.dto;

/**
 * "전체 선수 스탯"(대시보드) 한 행 — 9명 전원의 선수 스탯을 nickname 태그를 붙여 그대로 풀링한다.
 * 같은 카드(spId)를 여러 유저가 각자 쓸 수 있어 spId끼리 합치지 않는다(누구 스쿼드의 활약인지가
 * 의미 있어서). dribbleDistance는 원본(야드) 그대로 — 미터 환산은 표시 시점(프론트)에 한다.
 * momCount는 이 유저의 매치 중 이 선수가 그 매치의 최고 평점(MOM, 상대도 추적 대상이면 양팀 통틀어)
 * 이었던 횟수.
 */
public record DashboardPooledPlayer(
        String nickname, String spId, String playerName, int appearances,
        int goals, int assists, int saves, int tackles, int intercepts, int blocks,
        int shootTotal, int effectiveShoot, int passTry, int passSuccess,
        int dribbleTry, int dribbleSuccess, int dribbleDistance, int aerialTry, int aerialSuccess,
        Double avgRating, double xg, double finishing, int momCount
) {
}
