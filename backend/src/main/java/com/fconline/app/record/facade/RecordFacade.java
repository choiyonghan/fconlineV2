package com.fconline.app.record.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.common.TeamPeriodRangeResolver;
import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.GoalTimeBucketResponse;
import com.fconline.app.record.dto.GoalTypeStatResponse;
import com.fconline.app.record.dto.MatchShotResponse;
import com.fconline.app.record.dto.MatchShotsResponse;
import com.fconline.app.record.dto.MatchSquadEntryResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.MatchPlayerRatingResponse;
import com.fconline.app.record.dto.PlayerGradeResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.ShotPointResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.service.MatchDomainService;
import com.fconline.domain.match.vo.AssistChainCount;
import com.fconline.domain.match.vo.ExpectedGoalsCalculator;
import com.fconline.domain.match.vo.MatchShotDetail;
import com.fconline.domain.match.vo.MatchSquadEntryRaw;
import com.fconline.domain.match.vo.MatchStatsSummary;
import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.match.vo.PlayerShotPoint;
import com.fconline.domain.match.vo.RecentMatchRaw;
import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.match.vo.ShotPoint;
import com.fconline.domain.match.vo.TopPlayerStat;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.meta.PlayerMeta;
import com.fconline.domain.meta.repository.PlayerMetaRepository;
import com.fconline.domain.season.Season;
import com.fconline.domain.shared.exception.DomainException;
import com.fconline.domain.user.TrackedUser;
import com.fconline.domain.user.UserTeamPeriod;
import com.fconline.domain.user.repository.TrackedUserRepository;
import com.fconline.infrastructure.cache.CacheNames;
import com.fconline.infrastructure.cache.RecentMatchesPageCache;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.cache.annotation.Cacheable;
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
    /**
     * 프론트가 "환상의 콤비" 화면에서 양방향 조합(A→B + B→A)을 합산한 TOP5를 정확히 계산하려면
     * 방향별 상위 10건만으론 부족할 수 있어(한쪽 방향이 10위 밖으로 밀려나면 합산이 과소해짐)
     * 더 넓은 범위를 요청할 수 있게 한 상한. 인사이트 스냅샷 등 기존 호출부는 이 파라미터를
     * 안 쓰므로 기존 동작(ASSIST_CHAIN_LIMIT=10)에 영향 없다.
     */
    private static final int ASSIST_CHAIN_MAX_LIMIT = 200;
    /** "전체 선수" 그리드/최다 세이브 등에 쓰는 사실상 무제한 한도. */
    private static final int ALL_PLAYERS_LIMIT = 1000;

    private final TrackedUserRepository trackedUserRepository;
    private final SeasonRangeResolver seasonRangeResolver;
    private final TeamPeriodRangeResolver teamPeriodRangeResolver;
    private final MatchDomainService matchDomainService;
    private final PlayerMetaRepository playerMetaRepository;
    private final MatchDetailRepository matchDetailRepository;
    private final RecentMatchesPageCache recentMatchesPageCache;
    private final RecentMatchMapper recentMatchMapper;

    public RecordFacade(TrackedUserRepository trackedUserRepository, SeasonRangeResolver seasonRangeResolver,
                         TeamPeriodRangeResolver teamPeriodRangeResolver,
                         MatchDomainService matchDomainService, PlayerMetaRepository playerMetaRepository,
                         MatchDetailRepository matchDetailRepository,
                         RecentMatchesPageCache recentMatchesPageCache, RecentMatchMapper recentMatchMapper) {
        this.trackedUserRepository = trackedUserRepository;
        this.seasonRangeResolver = seasonRangeResolver;
        this.teamPeriodRangeResolver = teamPeriodRangeResolver;
        this.matchDomainService = matchDomainService;
        this.playerMetaRepository = playerMetaRepository;
        this.matchDetailRepository = matchDetailRepository;
        this.recentMatchesPageCache = recentMatchesPageCache;
        this.recentMatchMapper = recentMatchMapper;
    }

    @Transactional(readOnly = true)
    public OverallRecordResponse getOverallRecord(String ouid, MatchType matchType, Long seasonId) {
        return getOverallRecord(ouid, matchType, seasonId, null);
    }

    /**
     * teamPeriodId(선택) — "사용한 팀" 필터. Redis 캐시 대상(TTL 3시간, RedisCacheConfig.TTL —
     * 조회성 데이터는 전부 이 값으로 통일, 요청) — 반복 새로고침·9명 대시보드 배치가 만드는
     * DB 부하를 줄인다.
     */
    @Cacheable(CacheNames.OVERALL_RECORD)
    @Transactional(readOnly = true)
    public OverallRecordResponse getOverallRecord(String ouid, MatchType matchType, Long seasonId, Long teamPeriodId) {
        TrackedUser user = trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        var range = teamPeriodRangeResolver.narrow(season, teamPeriodId);
        var from = range.from();
        var to = range.to();

        MatchTally tally = matchDomainService.overallTally(ouid, matchType, from, to);
        MatchStatsSummary statsSummary = matchDomainService.statsSummary(ouid, matchType, from, to);
        List<TopPlayerStat> topPlayers = matchDomainService.topPlayers(ouid, matchType, from, to, null, TOP_PLAYER_LIMIT);
        List<GoalTypeStatResponse> goalTypeDistribution = matchDomainService
                .goalTypeDistribution(ouid, matchType, from, to).stream()
                .map(gt -> new GoalTypeStatResponse(gt.shootType().label(), gt.count()))
                .toList();
        List<GoalTimeBucketResponse> goalTimeDistribution = matchDomainService
                .goalTimeDistribution(ouid, matchType, from, to).stream()
                .map(gt -> new GoalTimeBucketResponse(gt.bucketLabel(), gt.count()))
                .toList();
        List<GoalTimeBucketResponse> concededGoalTimeDistribution = matchDomainService
                .concededGoalTimeDistribution(ouid, matchType, from, to).stream()
                .map(gt -> new GoalTimeBucketResponse(gt.bucketLabel(), gt.count()))
                .toList();

        Map<String, String> playerNames = playerNamesOf(topPlayers);
        Map<String, Double> xgBySpId = xgBySpId(ouid, matchType, from, to, null);
        Map<String, Double> xaBySpId = xaBySpId(ouid, matchType, from, to, null);

        List<TopPlayerResponse> topPlayerResponses = topPlayers.stream()
                .map(stat -> new TopPlayerResponse(
                        stat.spId(),
                        playerNames.getOrDefault(stat.spId(), stat.spId()),
                        stat.appearances(),
                        stat.goals(), stat.assists(), stat.saves(),
                        stat.tackles(), stat.intercepts(), stat.blocks(),
                        stat.shootTotal(), stat.effectiveShoot(), stat.passTry(), stat.passSuccess(),
                        stat.dribbleTry(), stat.dribbleSuccess(), stat.dribbleDistance(),
                        stat.aerialTry(), stat.aerialSuccess(),
                        stat.avgRating(), stat.contributionScore(), xgBySpId.getOrDefault(stat.spId(), 0.0),
                        stat.goalsAgainst(), xaBySpId.getOrDefault(stat.spId(), 0.0)))
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
                concededGoalTimeDistribution,
                statsSummary.cleanSheets(),
                statsSummary.multiConcededGames(),
                statsSummary.highPossessionGames(),
                statsSummary.lowPossessionGames(),
                statsSummary.systemPauseTotal(),
                statsSummary.passTryTotal(),
                statsSummary.passSuccessTotal(),
                statsSummary.shortPassTryTotal(),
                statsSummary.shortPassSuccessTotal(),
                statsSummary.longPassTryTotal(),
                statsSummary.longPassSuccessTotal(),
                statsSummary.tackleTryTotal(),
                statsSummary.tackleSuccessTotal(),
                statsSummary.blockTryTotal(),
                statsSummary.blockSuccessTotal()
        );
    }

    /** 화면: 정렬 가능한 "전체 선수 스탯" 그리드, 최다 세이브 등 top-3 밖의 통계. */
    @Transactional(readOnly = true)
    public List<TopPlayerResponse> getAllPlayers(String ouid, MatchType matchType, Long seasonId) {
        return getAllPlayers(ouid, matchType, seasonId, null, null);
    }

    /**
     * opponentOuid를 지정하면 그 상대와의 경기만 집계한다 — "상대별 전적" 행을 펼쳤을 때
     * "이 상대전 최다 득점/도움/선방/수비 TOP3"를 계산하는 데 쓴다(프론트에서 goals/assists/
     * saves/tackles+intercepts+blocks 기준으로 각각 상위 3명을 클라이언트에서 뽑는다).
     */
    @Transactional(readOnly = true)
    public List<TopPlayerResponse> getAllPlayers(String ouid, MatchType matchType, Long seasonId, String opponentOuid) {
        return getAllPlayers(ouid, matchType, seasonId, opponentOuid, null);
    }

    /** teamPeriodId(선택) — "사용한 팀" 필터. Redis 캐시 대상(TTL 3시간) — getOverallRecord 주석 참고. */
    @Cacheable(CacheNames.ALL_PLAYERS)
    @Transactional(readOnly = true)
    public List<TopPlayerResponse> getAllPlayers(String ouid, MatchType matchType, Long seasonId, String opponentOuid,
                                                  Long teamPeriodId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        var range = teamPeriodRangeResolver.narrow(season, teamPeriodId);
        var from = range.from();
        var to = range.to();
        List<TopPlayerStat> players = matchDomainService.topPlayers(
                ouid, matchType, from, to, opponentOuid, ALL_PLAYERS_LIMIT);

        Map<String, String> playerNames = playerNamesOf(players);
        Map<String, Double> xgBySpId = xgBySpId(ouid, matchType, from, to, opponentOuid);
        Map<String, Double> xaBySpId = xaBySpId(ouid, matchType, from, to, opponentOuid);

        return players.stream()
                .map(stat -> new TopPlayerResponse(
                        stat.spId(),
                        playerNames.getOrDefault(stat.spId(), stat.spId()),
                        stat.appearances(),
                        stat.goals(), stat.assists(), stat.saves(),
                        stat.tackles(), stat.intercepts(), stat.blocks(),
                        stat.shootTotal(), stat.effectiveShoot(), stat.passTry(), stat.passSuccess(),
                        stat.dribbleTry(), stat.dribbleSuccess(), stat.dribbleDistance(),
                        stat.aerialTry(), stat.aerialSuccess(),
                        stat.avgRating(), stat.contributionScore(), xgBySpId.getOrDefault(stat.spId(), 0.0),
                        stat.goalsAgainst(), xaBySpId.getOrDefault(stat.spId(), 0.0)))
                .toList();
    }

    /** "전체 선수 스탯"의 xG/결정력 열용 — 선수별 슛 좌표를 spId로 묶어 xG를 합산한다. */
    private Map<String, Double> xgBySpId(String ouid, MatchType matchType, Instant from, Instant to,
                                          String opponentOuid) {
        Map<String, Double> result = new HashMap<>();
        for (PlayerShotPoint p : matchDomainService.playerShotPoints(ouid, matchType, from, to, opponentOuid)) {
            result.merge(p.spId(), ExpectedGoalsCalculator.calcXg(p.x(), p.y(), p.shootType().label()), Double::sum);
        }
        return result;
    }

    /** "전체 선수 스탯"의 xA 열용 — 어시스트 제공자별로 그 슛의 xG를 합산한다(노골 포함). */
    private Map<String, Double> xaBySpId(String ouid, MatchType matchType, Instant from, Instant to,
                                          String opponentOuid) {
        Map<String, Double> result = new HashMap<>();
        for (PlayerShotPoint p : matchDomainService.assistedShotPoints(ouid, matchType, from, to, opponentOuid)) {
            result.merge(p.spId(), ExpectedGoalsCalculator.calcXg(p.x(), p.y(), p.shootType().label()), Double::sum);
        }
        return result;
    }

    private Map<String, String> playerNamesOf(List<TopPlayerStat> topPlayers) {
        List<String> spIds = topPlayers.stream().map(TopPlayerStat::spId).toList();
        return playerMetaRepository.findBySpIdIn(spIds).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));
    }

    /** 좌표 히트맵(화면: 슛/득점 위치 시각화). goalsOnly=true면 득점한 슛만 반환한다. */
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getShotHeatmap(String ouid, MatchType matchType, Long seasonId, boolean goalsOnly) {
        return getShotHeatmap(ouid, matchType, seasonId, null, goalsOnly, null);
    }

    /** opponentOuid를 지정하면 그 상대와의 경기만("상대별 전적" 펼침의 평균 득점 xG값 계산용). */
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getShotHeatmap(String ouid, MatchType matchType, Long seasonId, String opponentOuid,
                                               boolean goalsOnly) {
        return getShotHeatmap(ouid, matchType, seasonId, opponentOuid, goalsOnly, null);
    }

    /** teamPeriodId(선택) — "사용한 팀" 필터. Redis 캐시 대상(TTL 3시간) — getOverallRecord 주석 참고. */
    @Cacheable(CacheNames.SHOT_HEATMAP)
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getShotHeatmap(String ouid, MatchType matchType, Long seasonId, String opponentOuid,
                                               boolean goalsOnly, Long teamPeriodId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        var range = teamPeriodRangeResolver.narrow(season, teamPeriodId);
        List<ShotPoint> points = matchDomainService.shotHeatmap(
                ouid, matchType, range.from(), range.to(), opponentOuid, goalsOnly);

        List<ShotPointResponse> pointResponses = points.stream()
                .map(p -> new ShotPointResponse(p.x(), p.y(), p.shootType().label(), p.result().name(),
                        p.result() == ShootResult.GOAL, p.matchId()))
                .toList();

        return new ShotHeatmapResponse(ouid, pointResponses);
    }

    /**
     * "실점 xG값"(플레이 성향 · 수비 성향)용 — 추적 대상 상대가 이 유저를 향해 쏜 슛 좌표.
     * 상대가 추적 대상이 아닌 매치는 결과에 포함되지 않는다(MatchDomainService 주석 참고).
     */
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getConcededShotHeatmap(String ouid, MatchType matchType, Long seasonId) {
        return getConcededShotHeatmap(ouid, matchType, seasonId, null, null);
    }

    /** opponentOuid를 지정하면 그 상대와의 경기만("상대별 전적" 펼침의 평균 실점 xG값 계산용). */
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getConcededShotHeatmap(String ouid, MatchType matchType, Long seasonId,
                                                        String opponentOuid) {
        return getConcededShotHeatmap(ouid, matchType, seasonId, opponentOuid, null);
    }

    /** teamPeriodId(선택) — "사용한 팀" 필터. Redis 캐시 대상(TTL 3시간) — getOverallRecord 주석 참고. */
    @Cacheable(CacheNames.CONCEDED_SHOT_HEATMAP)
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getConcededShotHeatmap(String ouid, MatchType matchType, Long seasonId,
                                                        String opponentOuid, Long teamPeriodId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        var range = teamPeriodRangeResolver.narrow(season, teamPeriodId);
        List<ShotPoint> points = matchDomainService.concededShotHeatmap(
                ouid, matchType, range.from(), range.to(), opponentOuid);

        List<ShotPointResponse> pointResponses = points.stream()
                .map(p -> new ShotPointResponse(p.x(), p.y(), p.shootType().label(), p.result().name(),
                        p.result() == ShootResult.GOAL, p.matchId()))
                .toList();

        return new ShotHeatmapResponse(ouid, pointResponses);
    }

    /**
     * 매치 상세 모달용 — 특정 매치 1건의 슛 이벤트 전체(누가 어디서 어떤 유형/결과로 쐈는지,
     * 골이면 시각과 어시스트 여부까지). 선수 이름은 spId/assistSpId를 모아 한 번에 조회해 붙인다.
     */
    @Cacheable(CacheNames.MATCH_SHOTS)
    @Transactional(readOnly = true)
    public MatchShotsResponse getMatchShots(String ouid, MatchType matchType, String matchId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        List<MatchShotDetail> myShots = matchDomainService.shotsByMatch(ouid, matchType, matchId);
        List<MatchShotDetail> concededShots = matchDomainService.concededShotsByMatch(ouid, matchType, matchId);

        Set<String> spIds = new HashSet<>();
        Stream.concat(myShots.stream(), concededShots.stream()).forEach(s -> {
            spIds.add(s.spId());
            if (s.assistSpId() != null) spIds.add(s.assistSpId());
        });
        Map<String, String> playerNames = playerMetaRepository.findBySpIdIn(List.copyOf(spIds)).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));

        return new MatchShotsResponse(
                toMatchShotResponses(myShots, playerNames),
                toMatchShotResponses(concededShots, playerNames));
    }

    /**
     * 매치 상세 모달의 MOM/Worst Player용 — 특정 매치 1건의 이 유저 스쿼드 전체(평점 포함).
     * Nexon API에 MOM 플래그가 없어(MatchSquadEntryRaw 클래스 주석 참고) 프론트가 이 rating을
     * 비교해서 MOM/Worst를 직접 뽑는다.
     */
    @Cacheable(CacheNames.MATCH_SQUAD)
    @Transactional(readOnly = true)
    public List<MatchSquadEntryResponse> getMatchSquad(String ouid, MatchType matchType, String matchId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        List<MatchSquadEntryRaw> squad = matchDetailRepository.findSquadByMatch(ouid, matchType, matchId);
        Map<String, String> playerNames = playerMetaRepository.findBySpIdIn(
                        squad.stream().map(MatchSquadEntryRaw::spId).toList()).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));

        return squad.stream()
                .map(s -> new MatchSquadEntryResponse(
                        s.spId(), playerNames.getOrDefault(s.spId(), s.spId()), s.spPosition(),
                        s.goal(), s.assist(), s.save(), s.tackle(), s.intercept(), s.block(),
                        s.substitute(), s.rating()))
                .toList();
    }

    /**
     * 매치 상세 모달의 "상대 스탯 비교"용 — 특정 매치 1건, 이 ouid 관점의 팀 스탯 한 행
     * (RecentMatchResponse를 그대로 재사용 — "최근 경기" 목록의 한 행과 필드가 완전히 같다).
     * ouid가 이 매치에 없으면(상대가 추적 대상이 아니거나 매치를 못 찾음) 예외를 던진다 —
     * 호출부(컨트롤러/프론트)가 "비교 데이터 없음"으로 처리한다.
     */
    @Cacheable(CacheNames.MATCH_STATS)
    @Transactional(readOnly = true)
    public RecentMatchResponse getMatchStats(String ouid, MatchType matchType, String matchId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));
        RecentMatchRaw raw = matchDetailRepository.findByOuidAndMatchId(ouid, matchType, matchId)
                .orElseThrow(() -> new DomainException("해당 매치를 찾을 수 없습니다: ouid=" + ouid + ", matchId=" + matchId));
        Map<String, List<UserTeamPeriod>> periodsByOuid = recentMatchMapper.teamPeriodsByOuid(Set.of(ouid, raw.opponentOuid()));
        return recentMatchMapper.toRecentMatchResponse(ouid, raw, periodsByOuid);
    }

    private List<MatchShotResponse> toMatchShotResponses(List<MatchShotDetail> shots, Map<String, String> playerNames) {
        return shots.stream()
                .map(s -> new MatchShotResponse(
                        s.spId(), playerNames.getOrDefault(s.spId(), s.spId()),
                        s.x(), s.y(), s.shootType().label(), s.result().name(),
                        s.result() == ShootResult.GOAL,
                        s.goalTimeMinutes(), s.period(),
                        Boolean.TRUE.equals(s.assist()), s.assistSpId(),
                        s.assistSpId() != null ? playerNames.getOrDefault(s.assistSpId(), s.assistSpId()) : null,
                        s.assistX(), s.assistY(), s.hitPost(), s.inPenalty()))
                .toList();
    }

    /** 어시스트 체인(화면: 누가 누구에게 어시스트해서 득점했는지). 상위 {@value #ASSIST_CHAIN_LIMIT}건. */
    @Transactional(readOnly = true)
    public List<AssistChainResponse> getAssistChains(String ouid, MatchType matchType, Long seasonId) {
        return getAssistChains(ouid, matchType, seasonId, null, null);
    }

    /**
     * limit이 null이면 기존 기본값({@value #ASSIST_CHAIN_LIMIT}건)을 쓴다.
     * limit을 직접 지정하면 1~{@value #ASSIST_CHAIN_MAX_LIMIT} 범위로 clamp한다 — "환상의 콤비"
     * 화면이 양방향 조합 합산 TOP5를 정확히 계산하려고 더 넓은 범위를 요청할 때 쓴다.
     */
    @Transactional(readOnly = true)
    public List<AssistChainResponse> getAssistChains(String ouid, MatchType matchType, Long seasonId, Integer limit) {
        return getAssistChains(ouid, matchType, seasonId, limit, null);
    }

    /** teamPeriodId(선택) — "사용한 팀" 필터. Redis 캐시 대상(TTL 3시간) — getOverallRecord 주석 참고. */
    @Cacheable(CacheNames.ASSIST_CHAINS)
    @Transactional(readOnly = true)
    public List<AssistChainResponse> getAssistChains(String ouid, MatchType matchType, Long seasonId, Integer limit,
                                                       Long teamPeriodId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        int effectiveLimit = limit == null
                ? ASSIST_CHAIN_LIMIT
                : Math.max(1, Math.min(limit, ASSIST_CHAIN_MAX_LIMIT));

        Season season = seasonRangeResolver.resolve(seasonId);
        var range = teamPeriodRangeResolver.narrow(season, teamPeriodId);
        List<AssistChainCount> chains = matchDomainService.assistChains(
                ouid, matchType, range.from(), range.to(), effectiveLimit);

        Map<String, String> playerNames = playerNamesOfChains(chains);

        return chains.stream()
                .map(c -> new AssistChainResponse(
                        c.assisterSpId(), playerNames.getOrDefault(c.assisterSpId(), c.assisterSpId()),
                        c.scorerSpId(), playerNames.getOrDefault(c.scorerSpId(), c.scorerSpId()),
                        c.goals()))
                .toList();
    }

    /**
     * spId별 카드 강화 단계(0~11강, 가장 최근 매치 기준). 선수 이름이 나오는 화면(TOP7/전체 선수
     * 스탯/환상의 콤비)이 공용으로 붙여 쓰는 조회다. 슛을 한 번도 안 쏜 선수는 목록에서 빠진다
     * (shoot_events 기반이라 — DomainService/Repository 주석 참고).
     */
    @Transactional(readOnly = true)
    public List<PlayerGradeResponse> getPlayerGrades(String ouid, MatchType matchType, Long seasonId) {
        return getPlayerGrades(ouid, matchType, seasonId, null);
    }

    /** teamPeriodId(선택) — "사용한 팀" 필터. Redis 캐시 대상(TTL 3시간) — getOverallRecord 주석 참고. */
    @Cacheable(CacheNames.PLAYER_GRADES)
    @Transactional(readOnly = true)
    public List<PlayerGradeResponse> getPlayerGrades(String ouid, MatchType matchType, Long seasonId, Long teamPeriodId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        var range = teamPeriodRangeResolver.narrow(season, teamPeriodId);
        return matchDomainService.latestSpGrades(ouid, matchType, range.from(), range.to())
                .stream()
                .map(g -> new PlayerGradeResponse(g.spId(), g.grade()))
                .toList();
    }

    /** 대시보드 "선수 랭킹"의 MOM 횟수 집계용 — 매치별 스쿼드 엔트리 평점 원시값(집계 없음). */
    @Transactional(readOnly = true)
    public List<MatchPlayerRatingResponse> getMatchPlayerRatings(String ouid, MatchType matchType, Long seasonId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        return matchDomainService.matchPlayerRatings(
                        ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull())
                .stream()
                .map(r -> new MatchPlayerRatingResponse(r.matchId(), r.spId(), r.rating()))
                .toList();
    }
    // 대시보드 배치(전체 9명 풀링)에서만 쓰는 메서드라 teamPeriodId는 아직 안 받는다 —
    // 필요해지면 위 다른 메서드들과 같은 패턴(오버로드 추가)으로 확장.

    /**
     * 상대 무관, 이 유저의 진짜 최신 경기 목록(화면: 최근 경기 — 더보기 페이징). 대시보드
     * "🕐 최근 경기 (전체 9명)" 섹션이 9명 각자에 대해 이 API를 매번 라이브로 호출하므로
     * (report.js) DB 조회 자체는 RecentMatchesPageCache가 캐싱한다(TTL 3시간) — 이 메서드는
     * Page 자체를 캐시하지 않는다(Page는 인터페이스라 역직렬화가 안 됨, CachedPage 주석 참고).
     */
    @Transactional(readOnly = true)
    public Page<RecentMatchResponse> getRecentMatches(String ouid, MatchType matchType, Long seasonId,
                                                        Pageable pageable) {
        return getRecentMatches(ouid, matchType, seasonId, null, pageable);
    }

    /**
     * teamPeriodId(선택) — "사용한 팀" 필터. 실제 DB 조회(원시 매치 목록 + 팀 기간)와 매핑은
     * RecentMatchesPageCache가 전부 맡는다(TTL 3시간) — Page 자체를 캐싱하지 않는 이유는
     * CachedPage 주석 참고.
     */
    @Transactional(readOnly = true)
    public Page<RecentMatchResponse> getRecentMatches(String ouid, MatchType matchType, Long seasonId,
                                                        Long teamPeriodId, Pageable pageable) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        var range = teamPeriodRangeResolver.narrow(season, teamPeriodId);
        return recentMatchesPageCache.fetch(ouid, matchType, range.from(), range.to(), pageable).toPage(pageable);
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
