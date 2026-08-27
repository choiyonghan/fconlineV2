package com.fconline.domain.match.vo;

/**
 * appearances는 이 유저의 스쿼드에 선발로 들어간 매치 수(substitute=false 필터가 이미 걸려있어
 * 교체 출전은 세지 않는다 — goals/assists 등 다른 합산 지표와 동일한 모집단으로 맞추기 위함).
 * avgRating은 매치별 rating 중 결측이 아닌 값들의 평균(전부 결측이면 null).
 * goalsAgainst는 이 선수가 출전한 매치들의 "팀 실점" 합계 — 골키퍼(save>0)의 선방률
 * saves/(saves+goalsAgainst) 계산용 분모다. 골키퍼는 매치당 1명뿐이라 그 선수가 출전한
 * 매치의 팀 실점은 곧 그 선수가 내준 실점과 같다(교체 없이 풀타임 출전 가정). 필드 플레이어는
 * save가 항상 0이라 프론트에서 자연히 "-"로 숨겨진다.
 */
public record TopPlayerStat(String spId, int appearances, int goals, int assists, int saves,
                             int tackles, int intercepts, int blocks,
                             int shootTotal, int effectiveShoot, int passTry, int passSuccess,
                             int dribbleTry, int dribbleSuccess, int dribbleDistance,
                             int aerialTry, int aerialSuccess,
                             Double avgRating, double contributionScore, int goalsAgainst) {
}
