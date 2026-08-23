package com.fconline.app.record.dto;

/**
 * 프론트가 슛정확/패스/드리블/공중볼 성공률과 100점 만점 종합 점수는 이 raw 값들로부터
 * 직접 계산한다(그룹 내 최댓값 기준 재조정이 필요해서 응답 시점엔 절대값만 내려준다).
 */
public record TopPlayerResponse(String spId, String playerName, int appearances,
                                 int goals, int assists, int saves, int tackles, int intercepts, int blocks,
                                 int shootTotal, int effectiveShoot, int passTry, int passSuccess,
                                 int dribbleTry, int dribbleSuccess, int aerialTry, int aerialSuccess,
                                 Double avgRating, double contributionScore) {
}
