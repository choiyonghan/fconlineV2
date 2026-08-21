package com.fconline.application.common.dto;

import com.fconline.domain.match.MatchTally;

public record MatchTallyResponse(int win, int draw, int lose, long goalsFor, long goalsAgainst) {

    public static MatchTallyResponse from(MatchTally tally) {
        return new MatchTallyResponse(tally.wins(), tally.draws(), tally.losses(),
                tally.goalsFor(), tally.goalsAgainst());
    }
}
