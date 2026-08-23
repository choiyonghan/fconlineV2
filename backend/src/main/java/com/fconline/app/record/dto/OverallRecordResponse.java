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
        List<GoalTimeBucketResponse> goalTimeDistribution,
        /** "플레이 성향" 카드(수비 성향)용 — 무실점/3실점 이상/점유율 55%↑/45%↓ 경기 수. */
        long cleanSheets,
        long multiConcededGames,
        long highPossessionGames,
        long lowPossessionGames
) {
}
