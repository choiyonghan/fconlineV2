package com.fconline.application.opponent.dto;

import com.fconline.application.common.dto.MatchTallyResponse;
import com.fconline.application.common.dto.StreakBadgeResponse;

public record OpponentSummaryResponse(String opponentOuid, String opponentNickname,
                                       MatchTallyResponse tally, StreakBadgeResponse streak,
                                       int dugsikScore) {
}
