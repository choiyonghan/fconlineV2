package com.fconline.app.opponent.dto;

import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.common.dto.StreakBadgeResponse;

public record OpponentSummaryResponse(String opponentOuid, String opponentNickname,
                                       MatchTallyResponse tally, StreakBadgeResponse streak,
                                       int dugsikScore) {
}
