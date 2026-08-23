package com.fconline.app.record.dto;

import java.time.Instant;

/** OpponentMatchResponse와 필드는 같지만 상대 무관 "내 최신 경기" 목록이라 opponentNickname을 포함한다. */
public record RecentMatchResponse(
        String matchId,
        Instant matchDate,
        String opponentNickname,
        String result,
        int goalsFor,
        int goalsAgainst,
        Double averageRating,
        Integer possession,
        Integer shootTotal,
        Integer effectiveShoot,
        Integer passTry,
        Integer passSuccess,
        Integer tackleTry,
        Integer tackleSuccess,
        Integer foul,
        Integer yellowCards,
        Integer redCards
) {
}
