package com.fconline.domain.match.vo;

/**
 * 매치 1건에서 "누가 먼저 골을 넣었는지"(선제골). 무득점으로 끝난 매치는 애초에 이 결과가
 * 생성되지 않는다(MatchDomainService.firstGoalScorers 참고).
 */
public record FirstGoalResult(String matchId, boolean mine) {
}
