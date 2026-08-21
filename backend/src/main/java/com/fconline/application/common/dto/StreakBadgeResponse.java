package com.fconline.application.common.dto;

import com.fconline.domain.streak.OpponentStreak;

public record StreakBadgeResponse(int curWin, int curLose, int curWinless, int curUnbeaten,
                                   int maxWin, int maxLose, int maxWinless, int maxUnbeaten) {

    public static StreakBadgeResponse from(OpponentStreak streak) {
        return new StreakBadgeResponse(
                streak.getCurWin(), streak.getCurLose(), streak.getCurWinless(), streak.getCurUnbeaten(),
                streak.getMaxWin(), streak.getMaxLose(), streak.getMaxWinless(), streak.getMaxUnbeaten());
    }

    public static StreakBadgeResponse empty() {
        return new StreakBadgeResponse(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
