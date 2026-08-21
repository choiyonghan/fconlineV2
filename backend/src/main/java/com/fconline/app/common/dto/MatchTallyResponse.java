package com.fconline.app.common.dto;

import com.fconline.domain.match.vo.MatchTally;

public record MatchTallyResponse(int win, int draw, int lose, long goalsFor, long goalsAgainst) {

    public static MatchTallyResponse from(MatchTally tally) {
        return new MatchTallyResponse(tally.wins(), tally.draws(), tally.losses(),
                tally.goalsFor(), tally.goalsAgainst());
    }
}
