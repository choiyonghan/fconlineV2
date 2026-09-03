package com.fconline.app.search.facade;

import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.record.dto.MatchShotResponse;
import com.fconline.app.record.dto.MatchShotsResponse;
import com.fconline.app.record.dto.MatchSquadEntryResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.app.search.dto.SearchMatchStatsResponse;
import com.fconline.app.search.dto.SearchRecentMatchResponse;
import com.fconline.app.search.dto.SearchResultResponse;
import com.fconline.domain.match.gateway.NexonMatchData;
import com.fconline.domain.match.gateway.NexonMatchGateway;
import com.fconline.domain.match.gateway.NexonParticipantData;
import com.fconline.domain.match.gateway.NexonParticipantData.ShootEventData;
import com.fconline.domain.match.gateway.NexonParticipantData.SquadEntryData;
import com.fconline.domain.match.vo.ExpectedGoalsCalculator;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.meta.PlayerMeta;
import com.fconline.domain.meta.repository.PlayerMetaRepository;
import com.fconline.domain.shared.exception.DomainException;
import com.fconline.infrastructure.cache.CacheNames;
import com.fconline.infrastructure.search.SearchMatchDetailCache;
import java.util.ArrayList;
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
 * xG/xA/결정력 등 지표 공식은 새로 발명하지 않고 ExpectedGoalsCalculator·RecordFacade의
 * 로직(선수 기여도 점수, matchEndType=0 필터, 슛유형 배율)을 그대로 옮겼다 — DB 쿼리(QueryDSL
 * group-by) 대신 이 클래스 안에서 순수 Java로 직접 집계한다는 점만 다르다(매치 수가 최대
 * {@value #MAX_LIMIT}건이라 DB 없이도 충분히 가볍다).
 */
@Component
public class SearchFacade {

    /** 매치 1건당 Nexon 호출 1회(300ms 딜레이)라 너무 크게 잡으면 검색 한 번이 오래 걸린다 —
     * 기본 15건(약 5초), 최대 30건(약 9초)로 제한한다. */
    private static final int DEFAULT_LIMIT = 15;
    private static final int MAX_LIMIT = 30;
    /** SquadEntry.SUBSTITUTE_POSITION_CODE와 동일한 매직넘버 — 벤치에 머물다 안 뛴 선수 제외용. */
    private static final int SUBSTITUTE_POSITION_CODE = 28;

    private final NexonMatchGateway nexonMatchGateway;
    private final SearchMatchDetailCache matchDetailCache;
    private final PlayerMetaRepository playerMetaRepository;

    public SearchFacade(NexonMatchGateway nexonMatchGateway, SearchMatchDetailCache matchDetailCache,
                         PlayerMetaRepository playerMetaRepository) {
        this.nexonMatchGateway = nexonMatchGateway;
        this.matchDetailCache = matchDetailCache;
        this.playerMetaRepository = playerMetaRepository;
    }

    /**
     * 닉네임 → ouid 조회 후 최근 matchIds를 받아, 매치마다 Nexon match-detail을 조회(캐시 우선)해
     * 요약/선수 기여도/최근 경기 목록을 그 자리에서 집계한다. 존재하지 않는 닉네임이면
     * DomainException(404).
     */
    @Cacheable(CacheNames.SEARCH_PLAYER)
    @Transactional(readOnly = true)
    public SearchResultResponse search(String nickname, MatchType matchType, Integer limit) {
        int effectiveLimit = clampLimit(limit);
        String ouid = nexonMatchGateway.findOuid(nickname)
                .orElseThrow(() -> new DomainException("존재하지 않는 닉네임입니다: " + nickname));

        List<String> matchIds = nexonMatchGateway.findRecentMatchIds(ouid, matchType, effectiveLimit);

        int wins = 0;
        int draws = 0;
        int losses = 0;
        long goalsFor = 0;
        long goalsAgainst = 0;
        long assistsFor = 0;
        double ratingSum = 0;
        int ratingCount = 0;
        double possessionSum = 0;
        int possessionCount = 0;
        double xgFor = 0;
        double xaFor = 0;
        Map<String, Double> xgBySpId = new HashMap<>();
        Map<String, Double> xaBySpId = new HashMap<>();
        Map<String, PlayerAgg> squadAggBySpId = new LinkedHashMap<>();
        List<SearchRecentMatchResponse> recentMatches = new ArrayList<>();

        for (String matchId : matchIds) {
            NexonMatchData data = matchDetailCache.getOrFetch(matchId);
            NexonParticipantData me = findParticipant(data, ouid);
            if (me == null) {
                continue; // 방어적: 이론상 항상 있어야 하지만 응답이 깨진 경우 이 매치만 건너뜀
            }
            // 커스텀은 matchEndType=0(정상 종료)만 집계·목록 둘 다에 반영 — DB 쪽 baseWhere와
            // 동일한 규칙(MatchDetailRepositoryImpl 주석 참고). 공식전은 필터 없음.
            if (matchType == MatchType.CUSTOM && me.matchEndType() != null && me.matchEndType() != 0) {
                continue;
            }

            switch (me.result()) {
                case WIN -> wins++;
                case DRAW -> draws++;
                case LOSE -> losses++;
            }
            goalsFor += me.goalsFor();
            goalsAgainst += me.goalsAgainst();
            if (me.averageRating() != null) {
                ratingSum += me.averageRating();
                ratingCount++;
            }
            if (me.possession() != null) {
                possessionSum += me.possession();
                possessionCount++;
            }

            for (ShootEventData shot : me.shootEvents()) {
                if (shot.x() == null || shot.y() == null) {
                    continue;
                }
                double xg = ExpectedGoalsCalculator.calcXg(shot.x(), shot.y(), shot.shootType().label());
                xgFor += xg;
                xgBySpId.merge(shot.spId(), xg, Double::sum);
                if (Boolean.TRUE.equals(shot.assist()) && shot.assistSpId() != null) {
                    xaFor += xg;
                    xaBySpId.merge(shot.assistSpId(), xg, Double::sum);
                }
            }

            for (SquadEntryData entry : me.squadEntries()) {
                if (entry.spPosition() == SUBSTITUTE_POSITION_CODE) {
                    continue;
                }
                PlayerAgg agg = squadAggBySpId.computeIfAbsent(entry.spId(), k -> new PlayerAgg());
                agg.accumulate(entry, me.goalsAgainst());
                assistsFor += entry.assist();
            }

            recentMatches.add(new SearchRecentMatchResponse(
                    matchId, data.matchDate(), me.result().label(), me.goalsFor(), me.goalsAgainst(),
                    me.opponentNickname(), me.opponentOuid()));
        }

        Map<String, String> playerNames = playerMetaRepository.findBySpIdIn(squadAggBySpId.keySet()).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));

        List<TopPlayerResponse> topPlayers = squadAggBySpId.entrySet().stream()
                .map(e -> e.getValue().toResponse(e.getKey(), playerNames.getOrDefault(e.getKey(), e.getKey()),
                        xgBySpId.getOrDefault(e.getKey(), 0.0), xaBySpId.getOrDefault(e.getKey(), 0.0)))
                .sorted(Comparator.comparingDouble(TopPlayerResponse::contributionScore).reversed())
                .toList();

        MatchTallyResponse tally = new MatchTallyResponse(wins, draws, losses, goalsFor, goalsAgainst);

        return new SearchResultResponse(
                ouid, nickname, matchType.name(), recentMatches.size(), tally, assistsFor,
                ratingCount > 0 ? ratingSum / ratingCount : null,
                possessionCount > 0 ? possessionSum / possessionCount : null,
                xgFor, xaFor, goalsFor - xgFor,
                topPlayers, recentMatches);
    }

    /** 매치 상세 모달의 득점/실점 상세용 — search()가 이미 캐시에 채워둔 매치면 Nexon 재호출 없음. */
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
        Map<String, String> playerNames = playerMetaRepository.findBySpIdIn(List.copyOf(spIds)).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));

        return new MatchShotsResponse(
                toMatchShotResponses(me.shootEvents(), playerNames),
                opp != null ? toMatchShotResponses(opp.shootEvents(), playerNames) : List.of());
    }

    /** 매치 상세 모달의 MOM/Worst용 — ouid에 검색 대상 또는 그 상대(opponentOuid) 아무거나 넘겨도 된다. */
    @Transactional(readOnly = true)
    public List<MatchSquadEntryResponse> getMatchSquad(String ouid, String matchId) {
        NexonMatchData data = matchDetailCache.getOrFetch(matchId);
        NexonParticipantData me = requireParticipant(data, ouid, matchId);

        Map<String, String> playerNames = playerMetaRepository.findBySpIdIn(
                        me.squadEntries().stream().map(SquadEntryData::spId).toList()).stream()
                .collect(Collectors.toMap(PlayerMeta::getSpId, PlayerMeta::getSpName, (a, b) -> a));

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

    private RecentMatchResponse toRecentMatchResponse(String matchId, java.time.Instant matchDate,
                                                        NexonParticipantData p) {
        return new RecentMatchResponse(matchId, matchDate, p.opponentNickname(), p.opponentOuid(), p.result().label(),
                p.goalsFor(), p.goalsAgainst(), p.averageRating(), p.possession(), p.shootTotal(), p.effectiveShoot(),
                p.passTry(), p.passSuccess(), p.tackleTry(), p.tackleSuccess(), p.foul(), p.yellowCards(),
                p.redCards(), null, null);
    }

    private List<MatchShotResponse> toMatchShotResponses(List<ShootEventData> shots, Map<String, String> playerNames) {
        return shots.stream()
                .map(s -> new MatchShotResponse(
                        s.spId(), playerNames.getOrDefault(s.spId(), s.spId()),
                        s.x(), s.y(), s.shootType().label(), s.result().name(),
                        s.result() == com.fconline.domain.match.vo.ShootResult.GOAL,
                        s.goalTimeMinutes(), s.period(),
                        Boolean.TRUE.equals(s.assist()), s.assistSpId(),
                        s.assistSpId() != null ? playerNames.getOrDefault(s.assistSpId(), s.assistSpId()) : null,
                        s.assistX(), s.assistY(), s.hitPost(), s.inPenalty()))
                .toList();
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
