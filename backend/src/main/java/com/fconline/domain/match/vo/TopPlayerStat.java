package com.fconline.domain.match.vo;

/**
 * appearances는 이 유저의 스쿼드에 선발로 들어간 매치 수(substitute=false 필터가 이미 걸려있어
 * 교체 출전은 세지 않는다 — goals/assists 등 다른 합산 지표와 동일한 모집단으로 맞추기 위함).
 * avgRating은 매치별 rating 중 결측이 아닌 값들의 평균(전부 결측이면 null).
 */
public record TopPlayerStat(String spId, int appearances, int goals, int assists, int saves,
                             int tackles, int intercepts, int blocks,
                             int shootTotal, int effectiveShoot, int passTry, int passSuccess,
                             int dribbleTry, int dribbleSuccess, int aerialTry, int aerialSuccess,
                             Double avgRating, double contributionScore) {
}
