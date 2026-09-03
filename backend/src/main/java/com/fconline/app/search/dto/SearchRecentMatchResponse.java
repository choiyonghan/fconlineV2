package com.fconline.app.search.dto;

import java.time.Instant;

/**
 * 검색 결과 "최근 경기" 목록 한 행. 클릭하면 프론트가 matchId로 match-shots/match-squad를
 * 다시 호출해 상세 모달을 연다(SearchController 참고) — 이 매치는 검색 집계 과정에서 이미
 * SearchMatchDetailCache에 채워져 있으므로 그 두 호출은 Nexon을 다시 안 친다.
 */
public record SearchRecentMatchResponse(
        String matchId,
        Instant matchDate,
        String result,
        int goalsFor,
        int goalsAgainst,
        String opponentNickname,
        String opponentOuid
) {
}
