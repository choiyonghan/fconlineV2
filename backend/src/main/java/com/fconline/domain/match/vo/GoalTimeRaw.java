package com.fconline.domain.match.vo;

/**
 * 득점 시간대 집계용 원시값. {@code minute}은 {@link com.fconline.domain.match.ShootEvent#getGoalTimeMinutes()}
 * 그대로(해당 period 시작 시점 기준 경과분 — 전반/후반/연장 각각 0에서 다시 시작), {@code period}는
 * 1(전반)~5(승부차기)다. 버킷 계산 시 period별 오프셋(전반 +0, 후반 +45, 연장전반 +90, 연장후반 +105,
 * 승부차기 +120)을 더해야 "경기 시작 기준 누적 분"이 된다 — 이 record 자체는 그 계산 전 원시값만 담는다.
 */
public record GoalTimeRaw(Integer minute, Integer period) {
}
