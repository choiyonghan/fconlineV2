package com.fconline.app.record.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.GoalTimeBucketResponse;
import com.fconline.app.record.dto.GoalTypeStatResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.ShotPointResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.service.MatchDomainService;
import com.fconline.domain.match.vo.AssistChainCount;
import com.fconline.domain.match.vo.MatchStatsSummary;
import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.match.vo.ShotPoint;
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
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 종합 전적 카드(화면 3) 유스케이스. 도메인 서비스 여러 개를 조합하고 record DTO로 조립한다.
 */
@Component
public class RecordFacade {

    private static final int TOP_PLAYER_LIMIT = 3;
    private static final int ASSIST_CHAIN_LIMIT = 10;
    /** "전체 선수" 그리드/최다 세이브 등에 쓰는 사실상 무제한 한도. */
    private static final int ALL_PLAYERS_LIMIT = 1000;

    private final TrackedUserRepository trackedUserRepository;
    private final SeasonRangeResolver seasonRangeResolver;
    private final MatchDomainService matchDomainService;
    private final PlayerMetaRepository playerMetaRepository;
    private final MatchDetailRepository matchDetailRepository;

    public RecordFacade(TrackedUserRepository trackedUserRepository, SeasonRangeResolver seasonRangeResolver,
                         MatchDomainService matchDomainService, PlayerMetaRepository playerMetaRepository,
                         MatchDetailRepository matchDetailRepository) {
        this.trackedUserRepository = trackedUserRepository;
        this.seasonRangeResolver = seasonRangeResolver;
        this.matchDomainService = matchDomainService;
        this.playerMetaRepository = playerMetaRepository;
        this.matchDetailRepository = matchDetailRepository;
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
                        stat.appearances(),
                        stat.goals(), stat.assists(), stat.saves(),
                        stat.tackles(), stat.intercepts(), stat.blocks(),
                        stat.shootTotal(), stat.effectiveShoot(), stat.passTry(), stat.passSuccess(),
                        stat.dribbleTry(), stat.dribbleSuccess(), stat.aerialTry(), stat.aerialSuccess(),
                        stat.avgRating(), stat.contributionScore()))
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
                goalTimeDistribution,
                statsSummary.cleanSheets(),
                statsSummary.multiConcededGames(),
                statsSummary.highPossessionGames(),
                statsSummary.lowPossessionGames()
        );
    }

    /** 화면: 정렬 가능한 "전체 선수 스탯" 그리드, 최다 세이브 등 top-3 밖의 통계. */
    @Transactional(readOnly = true)
    public List<TopPlayerResponse> getAllPlayers(String ouid, MatchType matchType, Long seasonId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        List<TopPlayerStat> players = matchDomainService.topPlayers(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), ALL_PLAYERS_LIMIT);

        Map<String, String> playerNames = playerNamesOf(players);

        return players.stream()
                .map(stat -> new TopPlayerResponse(
                        stat.spId(),
                        playerNames.getOrDefault(stat.spId(), stat.spId()),
                        stat.appearances(),
                        stat.goals(), stat.assists(), stat.saves(),
                        stat.tackles(), stat.intercepts(), stat.blocks(),
                        stat.shootTotal(), stat.effectiveShoot(), stat.passTry(), stat.passSuccess(),
                        stat.dribbleTry(), stat.dribbleSuccess(), stat.aerialTry(), stat.aerialSuccess(),
                        stat.avgRating(), stat.contributionScore()))
                .toList();
    }

    private Map<String, String> playerNamesOf(List<TopPlayerStat> topPlayers) {
        List<String> spIds = topPlayers.stream().map(TopPlayerStat::spId).toList();
        return playerMetaRepository.findBySpIdIn(spIds).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));
    }

    /** 좌표 히트맵(화면: 슛/득점 위치 시각화). goalsOnly=true면 득점한 슛만 반환한다. */
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getShotHeatmap(String ouid, MatchType matchType, Long seasonId, boolean goalsOnly) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        List<ShotPoint> points = matchDomainService.shotHeatmap(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), goalsOnly);

        List<ShotPointResponse> pointResponses = points.stream()
                .map(p -> new ShotPointResponse(p.x(), p.y(), p.shootType().label(), p.result().name(),
                        p.result() == ShootResult.GOAL))
                .toList();

        return new ShotHeatmapResponse(ouid, pointResponses);
    }

    /** 어시스트 체인(화면: 누가 누구에게 어시스트해서 득점했는지). 상위 {@value #ASSIST_CHAIN_LIMIT}건. */
    @Transactional(readOnly = true)
    public List<AssistChainResponse> getAssistChains(String ouid, MatchType matchType, Long seasonId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        List<AssistChainCount> chains = matchDomainService.assistChains(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), ASSIST_CHAIN_LIMIT);

        Map<String, String> playerNames = playerNamesOfChains(chains);

        return chains.stream()
                .map(c -> new AssistChainResponse(
                        c.assisterSpId(), playerNames.getOrDefault(c.assisterSpId(), c.assisterSpId()),
                        c.scorerSpId(), playerNames.getOrDefault(c.scorerSpId(), c.scorerSpId()),
                        c.goals()))
                .toList();
    }

    /** 상대 무관, 이 유저의 진짜 최신 경기 목록(화면: 최근 경기 — 더보기 페이징). */
    @Transactional(readOnly = true)
    public Page<RecentMatchResponse> getRecentMatches(String ouid, MatchType matchType, Long seasonId,
                                                        Pageable pageable) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        Page<MatchDetail> page = matchDetailRepository.findRecentByOuid(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), pageable);

        return page.map(this::toRecentMatchResponse);
    }

    private RecentMatchResponse toRecentMatchResponse(MatchDetail detail) {
        var stats = detail.getStats();
        return new RecentMatchResponse(
                detail.getMatch().getMatchId(),
                detail.getMatch().getMatchDate(),
                detail.getOpponentNickname(),
                detail.getResult().label(),
                nz(stats.getGoalsFor()),
                nz(stats.getGoalsAgainst()),
                stats.getAverageRating(),
                stats.getPossession(),
                stats.getShootTotal(),
                stats.getEffectiveShoot(),
                stats.getPassTry(),
                stats.getPassSuccess(),
                stats.getTackleTry(),
                stats.getTackleSuccess(),
                stats.getFoul(),
                stats.getYellowCards(),
                stats.getRedCards()
        );
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private Map<String, String> playerNamesOfChains(List<AssistChainCount> chains) {
        List<String> spIds = chains.stream()
                .flatMap(c -> Stream.of(c.assisterSpId(), c.scorerSpId()))
                .distinct()
                .toList();
        return playerMetaRepository.findBySpIdIn(spIds).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));
    }
}
