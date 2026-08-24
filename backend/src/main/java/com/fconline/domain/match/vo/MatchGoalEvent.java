package com.fconline.domain.match.vo;

/**
 * 특정 상대와의 매치들에서 나온 골 이벤트 원시값(누구 골인지 + 시각). "선제골" 분석용 —
 * mine=true면 이 유저의 골, false면 상대(그 상대 본인 관점 shoot_events)의 골이다. minute은
 * period 시작 기준 경과분이라 절대 분 환산(= 오프셋 더하기)은 이 값만으로는 안 되고
 * period가 같이 있어야 한다(MatchDomainService.periodOffset 참고).
 */
public record MatchGoalEvent(String matchId, Integer minute, Integer period, boolean mine) {
}
