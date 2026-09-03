package com.fconline.app.search.facade;

import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.GoalTimeBucketResponse;
import com.fconline.app.record.dto.GoalTypeStatResponse;
import com.fconline.app.record.dto.MatchShotResponse;
import com.fconline.app.record.dto.MatchShotsResponse;
import com.fconline.app.record.dto.MatchSquadEntryResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.ShotPointResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.app.search.dto.SearchMatchStatsResponse;
import com.fconline.domain.match.gateway.NexonMatchData;
import com.fconline.domain.match.gateway.NexonParticipantData;
import com.fconline.domain.match.gateway.NexonParticipantData.ShootEventData;
import com.fconline.domain.match.gateway.NexonParticipantData.SquadEntryData;
import com.fconline.domain.match.vo.ExpectedGoalsCalculator;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.meta.PlayerMeta;
import com.fconline.domain.meta.repository.PlayerMetaRepository;
import com.fconline.domain.shared.exception.DomainException;
import com.fconline.infrastructure.cache.CacheNames;
import com.fconline.infrastructure.search.SearchMatchDetailCache;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추적 대상 9명이 아닌 <b>임의 닉네임</b>을 검색하는 화면용. RecordFacade와 달리 DB를 전혀
 * 쓰지 않는다 — {@code tracked_users}에 없는 사람이라 sync 배치가 애초에 손댄 적이 없어서,
 * 닉네임→ouid부터 매치 상세까지 전부 그 자리에서 Nexon API로 직접 조회한다
 * (NexonMatchGateway, SearchMatchDetailCache — 이 클래스 주석 참고).
 *
 * <p>RecordFacade/RecordController처럼 화면(API)별로 메서드를 쪼갠다(요청 — SSR로 한 번에
 * 묶어 던지지 않고, 프론트가 report.js와 동일하게 CSR로 API마다 따로 호출한다). 각 메서드는
 * 독립적으로 {@code resolveOuid}→{@code resolveMatches}를 다시 수행하지만, 둘 다
 * SearchMatchDetailCache를 거치므로 실제 Nexon 호출은 "그 nickname/matchId를 처음 보는
 * 요청"만 비용을 낸다 — 같은 페이지 로드에서 여러 API가 겹치는 matchId를 봐도 캐시로 흡수된다.
 *
 * <p>지표 공식은 새로 발명하지 않고 ExpectedGoalsCalculator·RecordFacade/MatchDomainService/
 * MatchDetailRepositoryImpl의 로직(선수 기여도 점수, matchEndType=0 필터, 슛유형 배율,
 * 클린시트·다실점·고저점유율 임계값, 득점 시간대 15분 버킷)을 그대로 옮겼다 — DB 쿼리
 * (QueryDSL group-by) 대신 이 클래스 안에서 순수 Java로 직접 집계한다는 점만 다르다(매치
 * 수가 최대 {@value #MAX_LIMIT}건이라 DB 없이도 충분히 가볍다).
 */
@Component
public class SearchFacade {

    /** 매치 1건당 Nexon 호출 1회(300ms 딜레이)라 너무 크게 잡으면 검색 한 번이 오래 걸린다 —
     * 기본 15건(약 5초), 최대 30건(약 9초)로 제한한다. */
    private static final int DEFAULT_LIMIT = 15;
    private static final int MAX_LIMIT = 30;
    /** SquadEntry.SUBSTITUTE_POSITION_CODE와 동일한 매직넘버 — 벤치에 머물다 안 뛴 선수 제외용. */
    private static final int SUBSTITUTE_POSITION_CODE = 28;

    /** MatchDetailRepositoryImpl의 동명 상수와 동일한 값 — "플레이 성향"(수비 성향) 카드용. */
    private static final int MULTI_CONCEDED_THRESHOLD = 3;
    private static final int HIGH_POSSESSION_THRESHOLD = 55;
    private static final int LOW_POSSESSION_THRESHOLD = 45;

    /** MatchDomainService의 동명 상수와 동일 — 득점 시간대 15분 버킷. */
    private static final int[] BUCKET_UPPER_BOUNDS = {15, 30, 45, 60, 75, 90};
    private static final String EXTRA_TIME_LABEL = "연장전";
    private static final int[] PERIOD_OFFSET_MINUTES = {0, 0, 45, 90, 105, 120};

    private static final int DEFAULT_ASSIST_CHAIN_LIMIT = 10;
    private static final int MAX_ASSIST_CHAIN_LIMIT = 200;

    private final SearchMatchDetailCache matchDetailCache;
    private final PlayerMetaRepository playerMetaRepository;

    public SearchFacade(SearchMatchDetailCache matchDetailCache, PlayerMetaRepository playerMetaRepository) {
        this.matchDetailCache = matchDetailCache;
        this.playerMetaRepository = playerMetaRepository;
    }

    // ---------------- 화면별 조회 API (RecordController와 대응) ----------------

    /** "종합 스탯"(전적/평점/점유율/xG·결정력에 준하는 지표는 별도 API, 여긴 나머지 전부)용. */
    @Cacheable(CacheNames.SEARCH_OVERALL)
    @Transactional(readOnly = true)
    public OverallRecordResponse getOverall(String nickname, MatchType matchType, Integer limit) {
        String ouid = resolveOuid(nickname);
        List<MatchCtx> matches = resolveMatches(ouid, matchType, limit);

        int wins = 0;
        int draws = 0;
        int losses = 0;
        long goalsFor = 0;
        long goalsAgainst = 0;
        double ratingSum = 0;
        int ratingCount = 0;
        double possessionSum = 0;
        int possessionCount = 0;
        long foulTotal = 0;
        long yellowCards = 0;
        long redCards = 0;
        long systemPauseTotal = 0;
        long passTryTotal = 0;
        long passSuccessTotal = 0;
        long shortPassTryTotal = 0;
        long shortPassSuccessTotal = 0;
        long longPassTryTotal = 0;
        long longPassSuccessTotal = 0;
        long tackleTryTotal = 0;
        long tackleSuccessTotal = 0;
        long blockTryTotal = 0;
        long blockSuccessTotal = 0;
        long cleanSheets = 0;
        long multiConcededGames = 0;
        long highPossessionGames = 0;
        long lowPossessionGames = 0;

        for (MatchCtx ctx : matches) {
            NexonParticipantData me = ctx.me();
            switch (me.result()) {
                case WIN -> wins++;
                case DRAW -> draws++;
                case LOSE -> losses++;
            }
            goalsFor += nz(me.goalsFor());
            goalsAgainst += nz(me.goalsAgainst());
            if (me.averageRating() != null) {
                ratingSum += me.averageRating();
                ratingCount++;
            }
            if (me.possession() != null) {
                possessionSum += me.possession();
                possessionCount++;
                if (me.possession() >= HIGH_POSSESSION_THRESHOLD) highPossessionGames++;
                if (me.possession() <= LOW_POSSESSION_THRESHOLD) lowPossessionGames++;
            }
            if (nz(me.goalsAgainst()) == 0) cleanSheets++;
            if (nz(me.goalsAgainst()) >= MULTI_CONCEDED_THRESHOLD) multiConcededGames++;
            foulTotal += nz(me.foul());
            yellowCards += nz(me.yellowCards());
            redCards += nz(me.redCards());
            if (me.systemPause() != null) systemPauseTotal += me.systemPause();
            passTryTotal += nz(me.passTry());
            passSuccessTotal += nz(me.passSuccess());
            shortPassTryTotal += nz(me.shortPassTry());
            shortPassSuccessTotal += nz(me.shortPassSuccess());
            longPassTryTotal += nz(me.longPassTry());
            longPassSuccessTotal += nz(me.longPassSuccess());
            tackleTryTotal += nz(me.tackleTry());
            tackleSuccessTotal += nz(me.tackleSuccess());
            blockTryTotal += nz(me.blockTry());
            blockSuccessTotal += nz(me.blockSuccess());
        }

        return new OverallRecordResponse(
                ouid, nickname,
                new MatchTallyResponse(wins, draws, losses, goalsFor, goalsAgainst),
                ratingCount > 0 ? ratingSum / ratingCount : 0,
                possessionCount > 0 ? possessionSum / possessionCount : 0,
                foulTotal, yellowCards, redCards,
                aggregatePlayers(matches, TOP_PLAYER_TOP_N),
                goalTypeDistributionOf(matches),
                goalTimeDistributionOf(matches, false),
                goalTimeDistributionOf(matches, true),
                cleanSheets, multiConcededGames, highPossessionGames, lowPossessionGames,
                systemPauseTotal,
                passTryTotal, passSuccessTotal, shortPassTryTotal, shortPassSuccessTotal,
                longPassTryTotal, longPassSuccessTotal,
                tackleTryTotal, tackleSuccessTotal, blockTryTotal, blockSuccessTotal);
    }

    private static final int TOP_PLAYER_TOP_N = 3;

    /** "전체 선수 스탯" 그리드용 — 전원(제한 없음), contributionScore 내림차순. */
    @Cacheable(CacheNames.SEARCH_PLAYERS)
    @Transactional(readOnly = true)
    public List<TopPlayerResponse> getPlayers(String nickname, MatchType matchType, Integer limit) {
        String ouid = resolveOuid(nickname);
        List<MatchCtx> matches = resolveMatches(ouid, matchType, limit);
        return aggregatePlayers(matches, null);
    }

    /** "슈팅 위치 & 실제 xG값" 카드 · xG 타일용 — goalsOnly=true면 득점한 슛만. */
    @Cacheable(CacheNames.SEARCH_SHOT_HEATMAP)
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getShotHeatmap(String nickname, MatchType matchType, Integer limit,
                                               boolean goalsOnly) {
        String ouid = resolveOuid(nickname);
        List<MatchCtx> matches = resolveMatches(ouid, matchType, limit);
        List<ShotPointResponse> points = new ArrayList<>();
        for (MatchCtx ctx : matches) {
            for (ShootEventData shot : ctx.me().shootEvents()) {
                if (goalsOnly && shot.result() != ShootResult.GOAL) continue;
                points.add(toShotPointResponse(shot, ctx.data().matchId()));
            }
        }
        return new ShotHeatmapResponse(ouid, points);
    }

    /** "평균 실점 xG값"(수비 성향)용 — 상대가 나를 향해 쏜 슛. DB 기반과 달리 상대가 추적
     * 대상인지와 무관하게 항상 채워진다(Nexon match-detail이 양쪽 참가자를 다 주므로). */
    @Cacheable(CacheNames.SEARCH_CONCEDED_SHOT_HEATMAP)
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getConcededShotHeatmap(String nickname, MatchType matchType, Integer limit) {
        String ouid = resolveOuid(nickname);
        List<MatchCtx> matches = resolveMatches(ouid, matchType, limit);
        List<ShotPointResponse> points = new ArrayList<>();
        for (MatchCtx ctx : matches) {
            NexonParticipantData opp = findOpponent(ctx.data(), ouid);
            if (opp == null) continue;
            for (ShootEventData shot : opp.shootEvents()) {
                points.add(toShotPointResponse(shot, ctx.data().matchId()));
            }
        }
        return new ShotHeatmapResponse(ouid, points);
    }

    /** xA(기대 어시스트) 히트맵용 — 어시스트가 달린 슛(골 여부 무관)만. */
    @Cacheable(CacheNames.SEARCH_ASSISTED_SHOT_HEATMAP)
    @Transactional(readOnly = true)
    public ShotHeatmapResponse getAssistedShotHeatmap(String nickname, MatchType matchType, Integer limit) {
        String ouid = resolveOuid(nickname);
        List<MatchCtx> matches = resolveMatches(ouid, matchType, limit);
        List<ShotPointResponse> points = new ArrayList<>();
        for (MatchCtx ctx : matches) {
            for (ShootEventData shot : ctx.me().shootEvents()) {
                if (Boolean.TRUE.equals(shot.assist()) && shot.assistSpId() != null) {
                    points.add(toShotPointResponse(shot, ctx.data().matchId()));
                }
            }
        }
        return new ShotHeatmapResponse(ouid, points);
    }

    /** "환상의 콤비"용 — 어시스트→득점 조합 상위 chainLimit건(null이면 기본 10, 최대 200). */
    @Cacheable(CacheNames.SEARCH_ASSIST_CHAINS)
    @Transactional(readOnly = true)
    public List<AssistChainResponse> getAssistChains(String nickname, MatchType matchType, Integer limit,
                                                       Integer chainLimit) {
        String ouid = resolveOuid(nickname);
        List<MatchCtx> matches = resolveMatches(ouid, matchType, limit);
        int effectiveChainLimit = chainLimit == null
                ? DEFAULT_ASSIST_CHAIN_LIMIT
                : Math.max(1, Math.min(chainLimit, MAX_ASSIST_CHAIN_LIMIT));

        Map<String, Long> counts = new LinkedHashMap<>();
        for (MatchCtx ctx : matches) {
            for (ShootEventData shot : ctx.me().shootEvents()) {
                if (shot.result() == ShootResult.GOAL && Boolean.TRUE.equals(shot.assist())
                        && shot.assistSpId() != null && shot.spId() != null) {
                    counts.merge(shot.assistSpId() + "|" + shot.spId(), 1L, Long::sum);
                }
            }
        }

        Set<String> spIds = new HashSet<>();
        counts.keySet().forEach(key -> spIds.addAll(List.of(key.split("\\|"))));
        Map<String, String> playerNames = playerNamesFor(spIds);

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(effectiveChainLimit)
                .map(e -> {
                    String[] parts = e.getKey().split("\\|");
                    return new AssistChainResponse(
                            parts[0], playerNames.getOrDefault(parts[0], parts[0]),
                            parts[1], playerNames.getOrDefault(parts[1], parts[1]),
                            e.getValue());
                })
                .toList();
    }

    /** "최근 경기" 목록 · 플레이 성향/바이오리듬 추이 차트용 — RecentMatchResponse를 그대로
     * 재사용한다(평점/점유율/패스 등 추이 차트에 필요한 필드가 이미 다 있어서 별도 DTO를 새로
     * 만들 이유가 없다). team/opponentTeam은 user_team_periods가 DB 전용이라 항상 null. */
    @Cacheable(CacheNames.SEARCH_RECENT_MATCHES)
    @Transactional(readOnly = true)
    public List<RecentMatchResponse> getRecentMatches(String nickname, MatchType matchType, Integer limit) {
        String ouid = resolveOuid(nickname);
        List<MatchCtx> matches = resolveMatches(ouid, matchType, limit);
        return matches.stream()
                .map(ctx -> toRecentMatchResponse(ctx.data().matchId(), ctx.data().matchDate(), ctx.me()))
                .toList();
    }

    // ---------------- 매치 상세 모달용 (기존 그대로) ----------------

    /** 매치 상세 모달의 득점/실점 상세용 — search 계열이 이미 캐시에 채워둔 매치면 Nexon 재호출 없음. */
    @Transactional(readOnly = true)
    public MatchShotsResponse getMatchShots(String ouid, String matchId) {
        NexonMatchData data = matchDetailCache.getOrFetch(matchId);
        NexonParticipantData me = requireParticipant(data, ouid, matchId);
        NexonParticipantData opp = findOpponent(data, ouid);

        Set<String> spIds = new HashSet<>();
        Stream.concat(me.shootEvents().stream(), opp != null ? opp.shootEvents().stream() : Stream.empty())
                .forEach(s -> {
                    spIds.add(s.spId());
                    if (s.assistSpId() != null) {
                        spIds.add(s.assistSpId());
                    }
                });
        Map<String, String> playerNames = playerNamesFor(spIds);

        return new MatchShotsResponse(
                toMatchShotResponses(me.shootEvents(), playerNames),
                opp != null ? toMatchShotResponses(opp.shootEvents(), playerNames) : List.of());
    }

    /** 매치 상세 모달의 MOM/Worst용 — ouid에 검색 대상 또는 그 상대(opponentOuid) 아무거나 넘겨도 된다. */
    @Transactional(readOnly = true)
    public List<MatchSquadEntryResponse> getMatchSquad(String ouid, String matchId) {
        NexonMatchData data = matchDetailCache.getOrFetch(matchId);
        NexonParticipantData me = requireParticipant(data, ouid, matchId);

        Map<String, String> playerNames = playerNamesFor(
                me.squadEntries().stream().map(SquadEntryData::spId).toList());

        return me.squadEntries().stream()
                .map(entry -> new MatchSquadEntryResponse(
                        entry.spId(), playerNames.getOrDefault(entry.spId(), entry.spId()), entry.spPosition(),
                        entry.goal(), entry.assist(), entry.save(), entry.tackle(), entry.intercept(),
                        entry.block(), entry.spPosition() == SUBSTITUTE_POSITION_CODE, entry.rating()))
                .toList();
    }

    /** 매치 상세 모달 "상대 팀 비교"용 — DB 조회 없이 캐시된 원본에서 양쪽 팀 스탯을 그대로 뽑는다. */
    @Transactional(readOnly = true)
    public SearchMatchStatsResponse getMatchStats(String ouid, String matchId) {
        NexonMatchData data = matchDetailCache.getOrFetch(matchId);
        NexonParticipantData me = requireParticipant(data, ouid, matchId);
        NexonParticipantData opp = findOpponent(data, ouid);
        return new SearchMatchStatsResponse(
                toRecentMatchResponse(matchId, data.matchDate(), me),
                opp != null ? toRecentMatchResponse(matchId, data.matchDate(), opp) : null);
    }

    // ---------------- 공용 집계 헬퍼 ----------------

    private record MatchCtx(NexonMatchData data, NexonParticipantData me) {
    }

    private String resolveOuid(String nickname) {
        return matchDetailCache.findOuid(nickname)
                .orElseThrow(() -> new DomainException("존재하지 않는 닉네임입니다: " + nickname));
    }

    private List<MatchCtx> resolveMatches(String ouid, MatchType matchType, Integer limit) {
        int effectiveLimit = clampLimit(limit);
        List<String> matchIds = matchDetailCache.findRecentMatchIds(ouid, matchType, effectiveLimit);
        List<MatchCtx> result = new ArrayList<>();
        for (String matchId : matchIds) {
            NexonMatchData data = matchDetailCache.getOrFetch(matchId);
            NexonParticipantData me = findParticipant(data, ouid);
            if (me == null) {
                continue; // 방어적: 이론상 항상 있어야 하지만 응답이 깨진 경우 이 매치만 건너뜀
            }
            // 커스텀은 matchEndType=0(정상 종료)만 — DB 쪽 baseWhere와 동일한 규칙
            // (MatchDetailRepositoryImpl 주석 참고). 공식전은 필터 없음.
            if (matchType == MatchType.CUSTOM && me.matchEndType() != null && me.matchEndType() != 0) {
                continue;
            }
            result.add(new MatchCtx(data, me));
        }
        return result;
    }

    private List<TopPlayerResponse> aggregatePlayers(List<MatchCtx> matches, Integer cap) {
        Map<String, Double> xgBySpId = new HashMap<>();
        Map<String, Double> xaBySpId = new HashMap<>();
        Map<String, PlayerAgg> squadAggBySpId = new LinkedHashMap<>();

        for (MatchCtx ctx : matches) {
            NexonParticipantData me = ctx.me();
            for (ShootEventData shot : me.shootEvents()) {
                if (shot.x() == null || shot.y() == null) continue;
                double xg = ExpectedGoalsCalculator.calcXg(shot.x(), shot.y(), shot.shootType().label());
                xgBySpId.merge(shot.spId(), xg, Double::sum);
                if (Boolean.TRUE.equals(shot.assist()) && shot.assistSpId() != null) {
                    xaBySpId.merge(shot.assistSpId(), xg, Double::sum);
                }
            }
            for (SquadEntryData entry : me.squadEntries()) {
                if (entry.spPosition() == SUBSTITUTE_POSITION_CODE) continue;
                squadAggBySpId.computeIfAbsent(entry.spId(), k -> new PlayerAgg())
                        .accumulate(entry, nz(me.goalsAgainst()));
            }
        }

        Map<String, String> playerNames = playerNamesFor(squadAggBySpId.keySet());
        List<TopPlayerResponse> result = squadAggBySpId.entrySet().stream()
                .map(e -> e.getValue().toResponse(e.getKey(), playerNames.getOrDefault(e.getKey(), e.getKey()),
                        xgBySpId.getOrDefault(e.getKey(), 0.0), xaBySpId.getOrDefault(e.getKey(), 0.0)))
                .sorted(Comparator.comparingDouble(TopPlayerResponse::contributionScore).reversed())
                .toList();
        return cap == null ? result : result.stream().limit(cap).toList();
    }

    private List<GoalTypeStatResponse> goalTypeDistributionOf(List<MatchCtx> matches) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (MatchCtx ctx : matches) {
            for (ShootEventData shot : ctx.me().shootEvents()) {
                if (shot.result() == ShootResult.GOAL) {
                    counts.merge(shot.shootType().label(), 1L, Long::sum);
                }
            }
        }
        return counts.entrySet().stream().map(e -> new GoalTypeStatResponse(e.getKey(), e.getValue())).toList();
    }

    /** conceded=false면 득점 시간대, true면 상대(opponent)의 득점 시간대(=내 실점 시간대). */
    private List<GoalTimeBucketResponse> goalTimeDistributionOf(List<MatchCtx> matches, boolean conceded) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int upper : BUCKET_UPPER_BOUNDS) {
            counts.put(bucketLabel(upper), 0L);
        }
        counts.put(EXTRA_TIME_LABEL, 0L);

        for (MatchCtx ctx : matches) {
            NexonParticipantData source = conceded ? findOpponent(ctx.data(), ctx.me().ouid()) : ctx.me();
            if (source == null) continue;
            for (ShootEventData shot : source.shootEvents()) {
                if (shot.result() != ShootResult.GOAL || shot.goalTimeMinutes() == null) continue;
                int absoluteMinute = shot.goalTimeMinutes() + periodOffset(shot.period());
                counts.merge(bucketLabelFor(absoluteMinute), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream().map(e -> new GoalTimeBucketResponse(e.getKey(), e.getValue())).toList();
    }

    private int periodOffset(Integer period) {
        if (period == null || period < 1 || period >= PERIOD_OFFSET_MINUTES.length) {
            return 0;
        }
        return PERIOD_OFFSET_MINUTES[period];
    }

    private String bucketLabelFor(int minute) {
        for (int upper : BUCKET_UPPER_BOUNDS) {
            if (minute <= upper) {
                return bucketLabel(upper);
            }
        }
        return EXTRA_TIME_LABEL;
    }

    private String bucketLabel(int upper) {
        return (upper - 14) + "-" + upper;
    }

    private ShotPointResponse toShotPointResponse(ShootEventData shot, String matchId) {
        return new ShotPointResponse(shot.x(), shot.y(), shot.shootType().label(), shot.result().name(),
                shot.result() == ShootResult.GOAL, matchId);
    }

    private RecentMatchResponse toRecentMatchResponse(String matchId, Instant matchDate, NexonParticipantData p) {
        return new RecentMatchResponse(matchId, matchDate, p.opponentNickname(), p.opponentOuid(), p.result().label(),
                nz(p.goalsFor()), nz(p.goalsAgainst()), p.averageRating(), p.possession(), p.shootTotal(),
                p.effectiveShoot(), p.passTry(), p.passSuccess(), p.tackleTry(), p.tackleSuccess(), p.foul(),
                p.yellowCards(), p.redCards(), null, null);
    }

    private List<MatchShotResponse> toMatchShotResponses(List<ShootEventData> shots, Map<String, String> playerNames) {
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

    private Map<String, String> playerNamesFor(Collection<String> spIds) {
        return playerMetaRepository.findBySpIdIn(spIds).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));
    }

    private NexonParticipantData findParticipant(NexonMatchData data, String ouid) {
        return data.participants().stream().filter(p -> ouid.equals(p.ouid())).findFirst().orElse(null);
    }

    private NexonParticipantData requireParticipant(NexonMatchData data, String ouid, String matchId) {
        NexonParticipantData participant = findParticipant(data, ouid);
        if (participant == null) {
            throw new DomainException("해당 매치에서 유저를 찾을 수 없습니다: ouid=" + ouid + ", matchId=" + matchId);
        }
        return participant;
    }

    private NexonParticipantData findOpponent(NexonMatchData data, String ouid) {
        return data.participants().stream().filter(p -> !ouid.equals(p.ouid())).findFirst().orElse(null);
    }

    private int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** 선수 1명의 매치 여러 건 누적치 — MatchDetailRepositoryImpl.aggregateTopPlayers와 동일한 필드/공식. */
    private static final class PlayerAgg {
        int appearances;
        int goals;
        int assists;
        int saves;
        int tackles;
        int intercepts;
        int blocks;
        int shootTotal;
        int effectiveShoot;
        int passTry;
        int passSuccess;
        int dribbleTry;
        int dribbleSuccess;
        int dribbleDistance;
        int aerialTry;
        int aerialSuccess;
        double ratingSum;
        int ratingCount;
        int goalsAgainst;

        void accumulate(SquadEntryData entry, int matchGoalsAgainst) {
            appearances++;
            goals += entry.goal();
            assists += entry.assist();
            saves += entry.save();
            tackles += entry.tackle();
            intercepts += entry.intercept();
            blocks += entry.block();
            shootTotal += entry.shootTotal();
            effectiveShoot += entry.effectiveShoot();
            passTry += entry.passTry();
            passSuccess += entry.passSuccess();
            dribbleTry += entry.dribbleTry();
            dribbleSuccess += entry.dribbleSuccess();
            dribbleDistance += entry.dribbleDistance();
            aerialTry += entry.aerialTry();
            aerialSuccess += entry.aerialSuccess();
            if (entry.rating() != null) {
                ratingSum += entry.rating();
                ratingCount++;
            }
            goalsAgainst += matchGoalsAgainst;
        }

        TopPlayerResponse toResponse(String spId, String playerName, double xg, double xa) {
            double contributionScore = goals * 3.0 + assists * 2.0 + (tackles + intercepts + blocks + saves) * 0.5;
            Double avgRating = ratingCount > 0 ? ratingSum / ratingCount : null;
            return new TopPlayerResponse(spId, playerName, appearances, goals, assists, saves, tackles, intercepts,
                    blocks, shootTotal, effectiveShoot, passTry, passSuccess, dribbleTry, dribbleSuccess,
                    dribbleDistance, aerialTry, aerialSuccess, avgRating, contributionScore, xg, goalsAgainst, xa);
        }
    }
}
