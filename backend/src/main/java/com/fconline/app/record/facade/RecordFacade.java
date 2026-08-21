package com.fconline.app.record.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.record.dto.GoalTimeBucketResponse;
import com.fconline.app.record.dto.GoalTypeStatResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.domain.match.service.MatchDomainService;
import com.fconline.domain.match.vo.MatchStatsSummary;
import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.match.vo.TopPlayerStat;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.meta.PlayerMeta;
import com.fconline.domain.meta.repository.PlayerMetaRepository;
import com.fconline.domain.season.Season;
import com.fconline.domain.shared.exception.DomainException;
import com.fconline.domain.user.TrackedUser;
import com.fconline.domain.user.repository.TrackedUserRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종합 전적 카드(화면 3) 유스케이스. 도메인 서비스 여러 개를 조합하고 record DTO로 조립한다.
 */
@Component
public class RecordFacade {

    private static final int TOP_PLAYER_LIMIT = 3;

    private final TrackedUserRepository trackedUserRepository;
    private final SeasonRangeResolver seasonRangeResolver;
    private final MatchDomainService matchDomainService;
    private final PlayerMetaRepository playerMetaRepository;

    public RecordFacade(TrackedUserRepository trackedUserRepository, SeasonRangeResolver seasonRangeResolver,
                         MatchDomainService matchDomainService, PlayerMetaRepository playerMetaRepository) {
        this.trackedUserRepository = trackedUserRepository;
        this.seasonRangeResolver = seasonRangeResolver;
        this.matchDomainService = matchDomainService;
        this.playerMetaRepository = playerMetaRepository;
    }

    @Transactional(readOnly = true)
    public OverallRecordResponse getOverallRecord(String ouid, MatchType matchType, Long seasonId) {
        TrackedUser user = trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        var from = season.startInstant();
        var to = season.endInstantExclusiveOrNull();

        MatchTally tally = matchDomainService.overallTally(ouid, matchType, from, to);
        MatchStatsSummary statsSummary = matchDomainService.statsSummary(ouid, matchType, from, to);
        List<TopPlayerStat> topPlayers = matchDomainService.topPlayers(ouid, matchType, from, to, TOP_PLAYER_LIMIT);
        List<GoalTypeStatResponse> goalTypeDistribution = matchDomainService
                .goalTypeDistribution(ouid, matchType, from, to).stream()
                .map(gt -> new GoalTypeStatResponse(gt.shootType().label(), gt.count()))
                .toList();
        List<GoalTimeBucketResponse> goalTimeDistribution = matchDomainService
                .goalTimeDistribution(ouid, matchType, from, to).stream()
                .map(gt -> new GoalTimeBucketResponse(gt.bucketLabel(), gt.count()))
                .toList();

        Map<String, String> playerNames = playerNamesOf(topPlayers);

        List<TopPlayerResponse> topPlayerResponses = topPlayers.stream()
                .map(stat -> new TopPlayerResponse(
                        stat.spId(),
                        playerNames.getOrDefault(stat.spId(), stat.spId()),
                        stat.goals(), stat.assists(), stat.saves(),
                        stat.tackles(), stat.intercepts(), stat.blocks(),
                        stat.contributionScore()))
                .toList();

        return new OverallRecordResponse(
                ouid,
                user.getNickname(),
                MatchTallyResponse.from(tally),
                statsSummary.averageRating(),
                statsSummary.averagePossession(),
                statsSummary.foulTotal(),
                statsSummary.yellowCards(),
                statsSummary.redCards(),
                topPlayerResponses,
                goalTypeDistribution,
                goalTimeDistribution
        );
    }

    private Map<String, String> playerNamesOf(List<TopPlayerStat> topPlayers) {
        List<String> spIds = topPlayers.stream().map(TopPlayerStat::spId).toList();
        return playerMetaRepository.findBySpIdIn(spIds).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));
    }
}
