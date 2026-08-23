package com.fconline.app.record.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.GoalTimeBucketResponse;
import com.fconline.app.record.dto.GoalTypeStatResponse;
import com.fconline.app.record.dto.MatchShotResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.PlayerGradeResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.ShotPointResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.service.MatchDomainService;
import com.fconline.domain.match.vo.AssistChainCount;
import com.fconline.domain.match.vo.MatchShotDetail;
import com.fconline.domain.match.vo.MatchStatsSummary;
import com.fconline.domain.match.vo.MatchTally;
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
import com.fconline.domain.user.repository.TrackedUserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        List<TopPlayerStat> topPlayers = matchDomainService.topPlayers(ouid, matchType, from, to, null, TOP_PLAYER_LIMIT);
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
        return getAllPlayers(ouid, matchType, seasonId, null);
    }

    /**
     * opponentOuid를 지정하면 그 상대와의 경기만 집계한다 — "상대별 전적" 행을 펼쳤을 때
     * "이 상대전 최다 득점/도움/선방/수비 TOP3"를 계산하는 데 쓴다(프론트에서 goals/assists/
     * saves/tackles+intercepts+blocks 기준으로 각각 상위 3명을 클라이언트에서 뽑는다).
     */
    @Transactional(readOnly = true)
    public List<TopPlayerResponse> getAllPlayers(String ouid, MatchType matchType, Long seasonId, String opponentOuid) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        List<TopPlayerStat> players = matchDomainService.topPlayers(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), opponentOuid, ALL_PLAYERS_LIMIT);

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
        return getShotHeatmap(ouid, matchType, seasonId, null, goalsOnly);
    }

    /** opponentOuid를 지정하면 그 상대와의 경기만("상대별 전적" 펼침의 평균 득점 xG값 계산용). */
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getShotHeatmap(String ouid, MatchType matchType, Long seasonId, String opponentOuid,
                                               boolean goalsOnly) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        List<ShotPoint> points = matchDomainService.shotHeatmap(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), opponentOuid, goalsOnly);

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
        return getConcededShotHeatmap(ouid, matchType, seasonId, null);
    }

    /** opponentOuid를 지정하면 그 상대와의 경기만("상대별 전적" 펼침의 평균 실점 xG값 계산용). */
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getConcededShotHeatmap(String ouid, MatchType matchType, Long seasonId,
                                                        String opponentOuid) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        List<ShotPoint> points = matchDomainService.concededShotHeatmap(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), opponentOuid);

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
    @Transactional(readOnly = true)
    public List<MatchShotResponse> getMatchShots(String ouid, MatchType matchType, String matchId) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        List<MatchShotDetail> shots = matchDomainService.shotsByMatch(ouid, matchType, matchId);

        Set<String> spIds = new HashSet<>();
        shots.forEach(s -> {
            spIds.add(s.spId());
            if (s.assistSpId() != null) spIds.add(s.assistSpId());
        });
        Map<String, String> playerNames = playerMetaRepository.findBySpIdIn(List.copyOf(spIds)).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));

        return shots.stream()
                .map(s -> new MatchShotResponse(
                        s.spId(), playerNames.getOrDefault(s.spId(), s.spId()),
                        s.x(), s.y(), s.shootType().label(), s.result().name(),
                        s.result() == ShootResult.GOAL,
                        s.goalTimeMinutes(), s.period(),
                        Boolean.TRUE.equals(s.assist()), s.assistSpId(),
                        s.assistSpId() != null ? playerNames.getOrDefault(s.assistSpId(), s.assistSpId()) : null))
                .toList();
    }

    /** 어시스트 체인(화면: 누가 누구에게 어시스트해서 득점했는지). 상위 {@value #ASSIST_CHAIN_LIMIT}건. */
    @Transactional(readOnly = true)
    public List<AssistChainResponse> getAssistChains(String ouid, MatchType matchType, Long seasonId) {
        return getAssistChains(ouid, matchType, seasonId, null);
    }

    /**
     * limit이 null이면 기존 기본값({@value #ASSIST_CHAIN_LIMIT}건)을 쓴다.
     * limit을 직접 지정하면 1~{@value #ASSIST_CHAIN_MAX_LIMIT} 범위로 clamp한다 — "환상의 콤비"
     * 화면이 양방향 조합 합산 TOP5를 정확히 계산하려고 더 넓은 범위를 요청할 때 쓴다.
     */
    @Transactional(readOnly = true)
    public List<AssistChainResponse> getAssistChains(String ouid, MatchType matchType, Long seasonId, Integer limit) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        int effectiveLimit = limit == null
                ? ASSIST_CHAIN_LIMIT
                : Math.max(1, Math.min(limit, ASSIST_CHAIN_MAX_LIMIT));

        Season season = seasonRangeResolver.resolve(seasonId);
        List<AssistChainCount> chains = matchDomainService.assistChains(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), effectiveLimit);

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
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        return matchDomainService.latestSpGrades(
                        ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull())
                .stream()
                .map(g -> new PlayerGradeResponse(g.spId(), g.grade()))
                .toList();
    }

    /** 상대 무관, 이 유저의 진짜 최신 경기 목록(화면: 최근 경기 — 더보기 페이징). */
    @Transactional(readOnly = true)
    public Page<RecentMatchResponse> getRecentMatches(String ouid, MatchType matchType, Long seasonId,
                                                        Pageable pageable) {
        trackedUserRepository.findById(ouid)
                .orElseThrow(() -> new DomainException("추적 대상이 아닌 유저입니다: " + ouid));

        Season season = seasonRangeResolver.resolve(seasonId);
        Page<RecentMatchRaw> page = matchDetailRepository.findRecentByOuid(
                ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), pageable);

        return page.map(this::toRecentMatchResponse);
    }

    private RecentMatchResponse toRecentMatchResponse(RecentMatchRaw raw) {
        return new RecentMatchResponse(
                raw.matchId(),
                raw.matchDate(),
                raw.opponentNickname(),
                raw.result().label(),
                nz(raw.goalsFor()),
                nz(raw.goalsAgainst()),
                raw.averageRating(),
                raw.possession(),
                raw.shootTotal(),
                raw.effectiveShoot(),
                raw.passTry(),
                raw.passSuccess(),
                raw.tackleTry(),
                raw.tackleSuccess(),
                raw.foul(),
                raw.yellowCards(),
                raw.redCards()
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
