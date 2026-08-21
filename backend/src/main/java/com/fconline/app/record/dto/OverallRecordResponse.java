package com.fconline.app.record.dto;

import com.fconline.app.common.dto.MatchTallyResponse;
import java.util.List;

public record OverallRecordResponse(
        String ouid,
        String nickname,
        MatchTallyResponse tally,
        double averageRating,
        double possessionAverage,
        long foulTotal,
        long yellowCards,
        long redCards,
        List<TopPlayerResponse> topPlayers,
        List<GoalTypeStatResponse> goalTypeDistribution,
        List<GoalTimeBucketResponse> goalTimeDistribution
) {
}
