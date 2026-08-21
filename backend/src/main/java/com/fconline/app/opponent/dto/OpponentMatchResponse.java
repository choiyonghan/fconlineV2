package com.fconline.app.opponent.dto;

import java.time.Instant;

public record OpponentMatchResponse(
        String matchId,
        Instant matchDate,
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
