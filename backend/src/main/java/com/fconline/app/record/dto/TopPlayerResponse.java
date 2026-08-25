package com.fconline.app.record.dto;

/**
 * 프론트가 슛정확/패스/드리블/공중볼 성공률과 100점 만점 종합 점수는 이 raw 값들로부터
 * 직접 계산한다(그룹 내 최댓값 기준 재조정이 필요해서 응답 시점엔 절대값만 내려준다).
 * xg = 이 선수가 쏜 슛 좌표 전체의 합산 기대 득점(ExpectedGoalsCalculator, "전체 선수 스탯"의
 * xG/결정력 열용) — RecordFacade가 shoot_events를 별도 조회해서 채운다.
 */
public record TopPlayerResponse(String spId, String playerName, int appearances,
                                 int goals, int assists, int saves, int tackles, int intercepts, int blocks,
                                 int shootTotal, int effectiveShoot, int passTry, int passSuccess,
                                 int dribbleTry, int dribbleSuccess, int dribbleDistance,
                                 int aerialTry, int aerialSuccess,
                                 Double avgRating, double contributionScore, double xg) {
}
