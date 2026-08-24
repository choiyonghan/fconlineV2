package com.fconline.infrastructure.persistence.match;

import com.fconline.domain.match.vo.AssistChainCount;
import com.fconline.domain.match.vo.GoalTimeRaw;
import com.fconline.domain.match.vo.GoalTypeCount;
import com.fconline.domain.match.repository.MatchDetailRepositoryCustom;
import com.fconline.domain.match.vo.MatchShotDetail;
import com.fconline.domain.match.vo.MatchStatsSummary;
import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.match.vo.OpponentTally;
import com.fconline.domain.match.vo.PlayerGrade;
import com.fconline.domain.match.vo.RecentMatchRaw;
import com.fconline.domain.match.vo.ShotPoint;
import com.fconline.domain.match.QMatch;
import com.fconline.domain.match.QMatchDetail;
import com.fconline.domain.match.QShootEvent;
import com.fconline.domain.match.QSquadEntry;
import com.fconline.domain.match.vo.TopPlayerStat;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.match.vo.ShootType;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * {@link MatchDetailRepositoryCustom}의 QueryDSL 구현체.
 * 모든 메서드가 matchType을 필수 조인 조건으로 강제해 v1의 6.2절 오염 버그가
 * 쿼리 레벨에서 재발할 수 없게 한다.
 */
@Repository
public class MatchDetailRepositoryImpl implements MatchDetailRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    public MatchDetailRepositoryImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public MatchTally aggregateTally(String ouid, MatchType matchType, Instant from, Instant to, String opponentOuid) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;

        BooleanBuilder where = baseWhere(md, m, ouid, matchType, from, to);
        if (opponentOuid != null) {
            where.and(md.opponentOuid.eq(opponentOuid));
        }

        NumberExpression<Integer> winCount = winCase(md);
        NumberExpression<Integer> drawCount = drawCase(md);
        NumberExpression<Integer> loseCount = loseCase(md);

        Tuple result = queryFactory
                .select(winCount, drawCount, loseCount, md.stats.goalsFor.sumAggregate(), md.stats.goalsAgainst.sumAggregate())
                .from(md)
                .join(md.match, m)
                .where(where)
                .fetchOne();

        if (result == null) {
            return MatchTally.EMPTY;
        }

        return new MatchTally(
                nz(result.get(winCount)),
                nz(result.get(drawCount)),
                nz(result.get(loseCount)),
                nz(result.get(md.stats.goalsFor.sumAggregate())),
                nz(result.get(md.stats.goalsAgainst.sumAggregate()))
        );
    }

    @Override
    public MatchStatsSummary aggregateStatsSummary(String ouid, MatchType matchType, Instant from, Instant to) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;

        NumberExpression<Integer> cleanSheetCount = cleanSheetCase(md);
        NumberExpression<Integer> multiConcededCount = multiConcededCase(md);
        NumberExpression<Integer> highPossessionCount = highPossessionCase(md);
        NumberExpression<Integer> lowPossessionCount = lowPossessionCase(md);

        Tuple result = queryFactory
                .select(md.stats.averageRating.avg(), md.stats.possession.avg(),
                        md.stats.foul.sumAggregate(), md.stats.yellowCards.sumAggregate(), md.stats.redCards.sumAggregate(),
                        cleanSheetCount, multiConcededCount, highPossessionCount, lowPossessionCount)
                .from(md)
                .join(md.match, m)
                .where(baseWhere(md, m, ouid, matchType, from, to))
                .fetchOne();

        if (result == null) {
            return MatchStatsSummary.EMPTY;
        }

        return new MatchStatsSummary(
                nzd(result.get(md.stats.averageRating.avg())),
                nzd(result.get(md.stats.possession.avg())),
                nz(result.get(md.stats.foul.sumAggregate())),
                nz(result.get(md.stats.yellowCards.sumAggregate())),
                nz(result.get(md.stats.redCards.sumAggregate())),
                nz(result.get(cleanSheetCount)),
                nz(result.get(multiConcededCount)),
                nz(result.get(highPossessionCount)),
                nz(result.get(lowPossessionCount))
        );
    }

    @Override
    public List<TopPlayerStat> aggregateTopPlayers(String ouid, MatchType matchType, Instant from, Instant to,
                                                     String opponentOuid, int limit) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;
        QSquadEntry se = QSquadEntry.squadEntry;

        BooleanBuilder where = baseWhere(md, m, ouid, matchType, from, to).and(se.substitute.isFalse());
        if (opponentOuid != null) {
            where.and(md.opponentOuid.eq(opponentOuid));
        }

        List<Tuple> rows = queryFactory
                .select(se.spId, se.id.count(),
                        se.goal.sumAggregate(), se.assist.sumAggregate(), se.save.sumAggregate(),
                        se.tackle.sumAggregate(), se.intercept.sumAggregate(), se.block.sumAggregate(),
                        se.shootTotal.sumAggregate(), se.effectiveShoot.sumAggregate(),
                        se.passTry.sumAggregate(), se.passSuccess.sumAggregate(),
                        se.dribbleTry.sumAggregate(), se.dribbleSuccess.sumAggregate(),
                        se.aerialTry.sumAggregate(), se.aerialSuccess.sumAggregate(),
                        se.rating.avg())
                .from(se)
                .join(se.matchDetail, md)
                .join(md.match, m)
                .where(where)
                .groupBy(se.spId)
                .fetch();

        return rows.stream()
                .map(row -> {
                    int appearances = (int) nzl(row.get(se.id.count()));
                    int goals = nz(row.get(se.goal.sumAggregate()));
                    int assists = nz(row.get(se.assist.sumAggregate()));
                    int saves = nz(row.get(se.save.sumAggregate()));
                    int tackles = nz(row.get(se.tackle.sumAggregate()));
                    int intercepts = nz(row.get(se.intercept.sumAggregate()));
                    int blocks = nz(row.get(se.block.sumAggregate()));
                    int shootTotal = nz(row.get(se.shootTotal.sumAggregate()));
                    int effectiveShoot = nz(row.get(se.effectiveShoot.sumAggregate()));
                    int passTry = nz(row.get(se.passTry.sumAggregate()));
                    int passSuccess = nz(row.get(se.passSuccess.sumAggregate()));
                    int dribbleTry = nz(row.get(se.dribbleTry.sumAggregate()));
                    int dribbleSuccess = nz(row.get(se.dribbleSuccess.sumAggregate()));
                    int aerialTry = nz(row.get(se.aerialTry.sumAggregate()));
                    int aerialSuccess = nz(row.get(se.aerialSuccess.sumAggregate()));
                    Double avgRating = row.get(se.rating.avg());
                    double score = (goals * 3.0) + (assists * 2.0) + (tackles + intercepts + blocks + saves) * 0.5;
                    return new TopPlayerStat(row.get(se.spId), appearances, goals, assists, saves, tackles,
                            intercepts, blocks, shootTotal, effectiveShoot, passTry, passSuccess,
                            dribbleTry, dribbleSuccess, aerialTry, aerialSuccess, avgRating, score);
                })
                .sorted(Comparator.comparingDouble(TopPlayerStat::contributionScore).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<GoalTypeCount> aggregateGoalTypeDistribution(String ouid, MatchType matchType, Instant from, Instant to) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;
        QShootEvent se = QShootEvent.shootEvent;

        List<Tuple> rows = queryFactory
                .select(se.shootType, se.id.count())
                .from(se)
                .join(se.matchDetail, md)
                .join(md.match, m)
                .where(baseWhere(md, m, ouid, matchType, from, to).and(se.result.eq(ShootResult.GOAL)))
                .groupBy(se.shootType)
                .fetch();

        return rows.stream()
                .map(row -> new GoalTypeCount(row.get(se.shootType), nzl(row.get(se.id.count()))))
                .toList();
    }

    @Override
    public List<GoalTimeRaw> findGoalMinutes(String ouid, MatchType matchType, Instant from, Instant to) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;
        QShootEvent se = QShootEvent.shootEvent;

        List<Tuple> rows = queryFactory
                .select(se.goalTimeMinutes, se.period)
                .from(se)
                .join(se.matchDetail, md)
                .join(md.match, m)
                .where(baseWhere(md, m, ouid, matchType, from, to)
                        .and(se.result.eq(ShootResult.GOAL))
                        .and(se.goalTimeMinutes.isNotNull()))
                .fetch();

        return rows.stream()
                .map(row -> new GoalTimeRaw(row.get(se.goalTimeMinutes), row.get(se.period)))
                .toList();
    }

    @Override
    public List<OpponentTally> aggregateOpponentTallies(String ouid, MatchType matchType, Instant from, Instant to) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;

        NumberExpression<Integer> winCount = winCase(md);
        NumberExpression<Integer> drawCount = drawCase(md);
        NumberExpression<Integer> loseCount = loseCase(md);

        List<Tuple> rows = queryFactory
                .select(md.opponentOuid, md.opponentNickname, winCount, drawCount, loseCount)
                .from(md)
                .join(md.match, m)
                .where(baseWhere(md, m, ouid, matchType, from, to))
                .groupBy(md.opponentOuid, md.opponentNickname)
                .fetch();

        return rows.stream()
                .map(row -> new OpponentTally(
                        row.get(md.opponentOuid),
                        row.get(md.opponentNickname),
                        nz(row.get(winCount)),
                        nz(row.get(drawCount)),
                        nz(row.get(loseCount))
                ))
                .toList();
    }

    @Override
    public List<MatchResult> findChronologicalResults(String ouid, String opponentOuid, MatchType matchType,
                                                        Instant from, Instant to) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;

        return queryFactory
                .select(md.result)
                .from(md)
                .join(md.match, m)
                .where(baseWhere(md, m, ouid, matchType, from, to).and(md.opponentOuid.eq(opponentOuid)))
                .orderBy(m.matchDate.asc())
                .fetch();
    }

    @Override
    public Page<RecentMatchRaw> findByOuidAndOpponent(String ouid, String opponentOuid, MatchType matchType,
                                                        Instant from, Instant to, Pageable pageable) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;

        BooleanBuilder where = baseWhere(md, m, ouid, matchType, from, to).and(md.opponentOuid.eq(opponentOuid));

        List<RecentMatchRaw> content = recentMatchQuery(md, m, where)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch().stream()
                .map(MatchDetailRepositoryImpl::toRecentMatchRaw)
                .toList();

        long total = nzl(queryFactory
                .select(md.count())
                .from(md)
                .join(md.match, m)
                .where(where)
                .fetchOne());

        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public Page<RecentMatchRaw> findRecentByOuid(String ouid, MatchType matchType, Instant from, Instant to,
                                                  Pageable pageable) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;

        BooleanBuilder where = baseWhere(md, m, ouid, matchType, from, to);

        List<RecentMatchRaw> content = recentMatchQuery(md, m, where)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch().stream()
                .map(MatchDetailRepositoryImpl::toRecentMatchRaw)
                .toList();

        long total = nzl(queryFactory
                .select(md.count())
                .from(md)
                .join(md.match, m)
                .where(where)
                .fetchOne());

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * "최근 경기" 계열 목록 조회 공통 SELECT. MatchDetail 전체(특히 shoot_detail/player_squad/
     * raw_participant jsonb 원본 3종, 참가자당 수 KB~수십 KB)를 끌어오지 않고 목록 화면에 실제로
     * 쓰이는 스칼라 컬럼만 선택한다 — size=1000짜리 "표본 전체" 호출(플레이 성향 추이 차트)에서
     * 특히 효과가 크다.
     */
    private JPAQuery<Tuple> recentMatchQuery(QMatchDetail md, QMatch m, BooleanBuilder where) {
        return queryFactory
                .select(m.matchId, m.matchDate, md.opponentNickname, md.result,
                        md.stats.goalsFor, md.stats.goalsAgainst, md.stats.averageRating, md.stats.possession,
                        md.stats.shootTotal, md.stats.effectiveShoot, md.stats.passTry, md.stats.passSuccess,
                        md.stats.tackleTry, md.stats.tackleSuccess, md.stats.foul, md.stats.yellowCards,
                        md.stats.redCards)
                .from(md)
                .join(md.match, m)
                .where(where)
                .orderBy(m.matchDate.desc());
    }

    private static RecentMatchRaw toRecentMatchRaw(Tuple row) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;
        return new RecentMatchRaw(
                row.get(m.matchId),
                row.get(m.matchDate),
                row.get(md.opponentNickname),
                row.get(md.result),
                row.get(md.stats.goalsFor),
                row.get(md.stats.goalsAgainst),
                row.get(md.stats.averageRating),
                row.get(md.stats.possession),
                row.get(md.stats.shootTotal),
                row.get(md.stats.effectiveShoot),
                row.get(md.stats.passTry),
                row.get(md.stats.passSuccess),
                row.get(md.stats.tackleTry),
                row.get(md.stats.tackleSuccess),
                row.get(md.stats.foul),
                row.get(md.stats.yellowCards),
                row.get(md.stats.redCards)
        );
    }

    @Override
    public List<ShotPoint> findShotPoints(String ouid, MatchType matchType, Instant from, Instant to,
                                           String opponentOuid, boolean goalsOnly) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;
        QShootEvent se = QShootEvent.shootEvent;

        BooleanBuilder where = baseWhere(md, m, ouid, matchType, from, to)
                .and(se.x.isNotNull())
                .and(se.y.isNotNull());
        if (opponentOuid != null) {
            where.and(md.opponentOuid.eq(opponentOuid));
        }
        if (goalsOnly) {
            where.and(se.result.eq(ShootResult.GOAL));
        }

        return queryFactory
                .select(se.x, se.y, se.shootType, se.result, m.matchId)
                .from(se)
                .join(se.matchDetail, md)
                .join(md.match, m)
                .where(where)
                .fetch().stream()
                .map(row -> new ShotPoint(row.get(se.x), row.get(se.y), row.get(se.shootType), row.get(se.result),
                        row.get(m.matchId)))
                .toList();
    }

    /**
     * "실점 xG값"(플레이 성향 · 수비 성향)용 — 우리 DB엔 상대가 나를 향해 쏜 슛 좌표가 직접 저장돼
     * 있지 않다(각 참가자는 자기 자신의 shoot_detail만 동기화한다). 대신 상대도 추적 대상 유저라면,
     * "그 상대 본인 관점"으로 동기화된 같은 매치 행이 DB에 이미 있고 그 행의 shootEvents가 곧
     * "나에게 쏜 슛"이다 — match_id + opponent_ouid로 그 행을 찾아 join한다.
     * 상대가 추적 대상이 아닌 매치는 결과에서 조용히 빠진다(추적 대상끼리만 커스텀매치를 돌리는
     * 구조라 대부분 커버되지만, 100%는 아니다 — 프론트 캡션에 이 한계를 명시한다).
     */
    @Override
    public List<ShotPoint> findConcededShotPoints(String ouid, MatchType matchType, Instant from, Instant to,
                                                    String opponentOuid) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;

        // 1) 이 유저 관점 매치들에서 (상대 ouid, matchId) 쌍을 모은다. opponentOuid가 지정되면
        // 그 상대와의 매치로만 좁힌다 — 이후 단계는 그대로라 자연스럽게 그 상대 실점만 나온다.
        BooleanBuilder myMatchesWhere = baseWhere(md, m, ouid, matchType, from, to);
        if (opponentOuid != null) {
            myMatchesWhere.and(md.opponentOuid.eq(opponentOuid));
        }
        List<Tuple> myMatches = queryFactory
                .select(md.opponentOuid, m.matchId)
                .from(md)
                .join(md.match, m)
                .where(myMatchesWhere)
                .fetch();
        if (myMatches.isEmpty()) {
            return List.of();
        }

        Map<String, Set<String>> matchIdsByOpponent = new HashMap<>();
        for (Tuple row : myMatches) {
            matchIdsByOpponent
                    .computeIfAbsent(row.get(md.opponentOuid), key -> new HashSet<>())
                    .add(row.get(m.matchId));
        }

        // 2) "상대 자신의 관점" 행(ouid=상대, matchId가 위 목록에 속함)을 찾아 그 슛을 가져온다.
        //    관계로 매핑 안 된 엔티티 간 ON 조인 대신, 이미 다른 메서드들이 다 쓰는 표준 연관관계
        //    조인(se.matchDetail, opp.match)만 사용한다 — 조인은 상대 후보 수(최대 몇 명)만큼
        //    OR로 묶는다.
        QMatchDetail opp = new QMatchDetail("concededOpponentDetail");
        QMatch oppMatch = new QMatch("concededOpponentMatch");
        QShootEvent se = new QShootEvent("concededShootEvent");

        BooleanBuilder opponentWhere = new BooleanBuilder();
        for (Map.Entry<String, Set<String>> entry : matchIdsByOpponent.entrySet()) {
            opponentWhere.or(opp.ouid.eq(entry.getKey()).and(oppMatch.matchId.in(entry.getValue())));
        }

        return queryFactory
                .select(se.x, se.y, se.shootType, se.result, oppMatch.matchId)
                .from(se)
                .join(se.matchDetail, opp)
                .join(opp.match, oppMatch)
                .where(opponentWhere.and(se.x.isNotNull()).and(se.y.isNotNull()))
                .fetch().stream()
                .map(row -> new ShotPoint(row.get(se.x), row.get(se.y), row.get(se.shootType), row.get(se.result),
                        row.get(oppMatch.matchId)))
                .toList();
    }

    @Override
    public List<MatchShotDetail> findShotsByMatch(String ouid, MatchType matchType, String matchId) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;
        QShootEvent se = QShootEvent.shootEvent;

        return queryFactory
                .select(se.spId, se.x, se.y, se.shootType, se.result, se.goalTimeMinutes, se.period,
                        se.assist, se.assistSpId)
                .from(se)
                .join(se.matchDetail, md)
                .join(md.match, m)
                .where(md.ouid.eq(ouid).and(m.matchType.eq(matchType)).and(m.matchId.eq(matchId)))
                .orderBy(se.goalTimeMinutes.asc().nullsLast())
                .fetch().stream()
                .map(row -> new MatchShotDetail(
                        row.get(se.spId), row.get(se.x), row.get(se.y), row.get(se.shootType), row.get(se.result),
                        row.get(se.goalTimeMinutes), row.get(se.period), row.get(se.assist), row.get(se.assistSpId)))
                .toList();
    }

    @Override
    public List<MatchShotDetail> findConcededShotsByMatch(String ouid, MatchType matchType, String matchId) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;

        // 1) 이 유저의 이 매치 행에서 상대 ouid를 찾는다.
        String opponentOuid = queryFactory
                .select(md.opponentOuid)
                .from(md)
                .join(md.match, m)
                .where(md.ouid.eq(ouid).and(m.matchType.eq(matchType)).and(m.matchId.eq(matchId)))
                .fetchFirst();
        if (opponentOuid == null) {
            return List.of();
        }

        // 2) 상대 본인 관점 행(ouid=상대, 같은 matchId)의 슛을 가져온다 — 그 상대가 추적 대상이
        // 아니면(자기 경기를 동기화한 적 없으면) 결과가 그냥 빈 목록이 된다.
        QMatchDetail opp = new QMatchDetail("concededByMatchOpponentDetail");
        QMatch oppMatch = new QMatch("concededByMatchOpponentMatch");
        QShootEvent se = new QShootEvent("concededByMatchShootEvent");

        return queryFactory
                .select(se.spId, se.x, se.y, se.shootType, se.result, se.goalTimeMinutes, se.period,
                        se.assist, se.assistSpId)
                .from(se)
                .join(se.matchDetail, opp)
                .join(opp.match, oppMatch)
                .where(opp.ouid.eq(opponentOuid).and(oppMatch.matchType.eq(matchType)).and(oppMatch.matchId.eq(matchId)))
                .orderBy(se.goalTimeMinutes.asc().nullsLast())
                .fetch().stream()
                .map(row -> new MatchShotDetail(
                        row.get(se.spId), row.get(se.x), row.get(se.y), row.get(se.shootType), row.get(se.result),
                        row.get(se.goalTimeMinutes), row.get(se.period), row.get(se.assist), row.get(se.assistSpId)))
                .toList();
    }

    @Override
    public List<AssistChainCount> aggregateAssistChains(String ouid, MatchType matchType, Instant from, Instant to,
                                                          int limit) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;
        QShootEvent se = QShootEvent.shootEvent;

        List<Tuple> rows = queryFactory
                .select(se.assistSpId, se.spId, se.id.count())
                .from(se)
                .join(se.matchDetail, md)
                .join(md.match, m)
                .where(baseWhere(md, m, ouid, matchType, from, to)
                        .and(se.result.eq(ShootResult.GOAL))
                        .and(se.assist.isTrue())
                        .and(se.assistSpId.isNotNull())
                        .and(se.spId.isNotNull()))
                .groupBy(se.assistSpId, se.spId)
                .orderBy(se.id.count().desc())
                .limit(limit)
                .fetch();

        return rows.stream()
                .map(row -> new AssistChainCount(row.get(se.assistSpId), row.get(se.spId), nzl(row.get(se.id.count()))))
                .toList();
    }

    @Override
    public List<PlayerGrade> findLatestSpGrades(String ouid, MatchType matchType, Instant from, Instant to) {
        QMatchDetail md = QMatchDetail.matchDetail;
        QMatch m = QMatch.match;
        QShootEvent se = QShootEvent.shootEvent;

        // 매치 날짜 내림차순으로 가져온 뒤, spId별로 처음 만난 행(=가장 최근 매치)만 남긴다.
        List<Tuple> rows = queryFactory
                .select(se.spId, se.spGrade, m.matchDate)
                .from(se)
                .join(se.matchDetail, md)
                .join(md.match, m)
                .where(baseWhere(md, m, ouid, matchType, from, to)
                        .and(se.spId.isNotNull())
                        .and(se.spGrade.isNotNull()))
                .orderBy(m.matchDate.desc())
                .fetch();

        Map<String, PlayerGrade> latestBySpId = new LinkedHashMap<>();
        for (Tuple row : rows) {
            latestBySpId.putIfAbsent(row.get(se.spId), new PlayerGrade(row.get(se.spId), row.get(se.spGrade)));
        }
        return new ArrayList<>(latestBySpId.values());
    }

    /** from은 포함(>=), to는 배제(<) — Season.endInstantExclusiveOrNull()과 짝을 이루는 규약. */
    private BooleanBuilder baseWhere(QMatchDetail md, QMatch m, String ouid, MatchType matchType,
                                      Instant from, Instant to) {
        BooleanBuilder builder = new BooleanBuilder();
        builder.and(md.ouid.eq(ouid));
        builder.and(m.matchType.eq(matchType));
        if (from != null) {
            builder.and(m.matchDate.goe(from));
        }
        if (to != null) {
            builder.and(m.matchDate.lt(to));
        }
        return builder;
    }

    private NumberExpression<Integer> winCase(QMatchDetail md) {
        return new CaseBuilder().when(md.result.eq(MatchResult.WIN)).then(1).otherwise(0).sumAggregate();
    }

    private NumberExpression<Integer> drawCase(QMatchDetail md) {
        return new CaseBuilder().when(md.result.eq(MatchResult.DRAW)).then(1).otherwise(0).sumAggregate();
    }

    private NumberExpression<Integer> loseCase(QMatchDetail md) {
        return new CaseBuilder().when(md.result.eq(MatchResult.LOSE)).then(1).otherwise(0).sumAggregate();
    }

    /** "플레이 성향" 카드(수비 성향)용 — 임계값은 프론트 캡션에도 그대로 노출한다. */
    private static final int MULTI_CONCEDED_THRESHOLD = 3;
    private static final int HIGH_POSSESSION_THRESHOLD = 55;
    private static final int LOW_POSSESSION_THRESHOLD = 45;

    private NumberExpression<Integer> cleanSheetCase(QMatchDetail md) {
        return new CaseBuilder().when(md.stats.goalsAgainst.eq(0)).then(1).otherwise(0).sumAggregate();
    }

    private NumberExpression<Integer> multiConcededCase(QMatchDetail md) {
        return new CaseBuilder().when(md.stats.goalsAgainst.goe(MULTI_CONCEDED_THRESHOLD)).then(1).otherwise(0).sumAggregate();
    }

    private NumberExpression<Integer> highPossessionCase(QMatchDetail md) {
        return new CaseBuilder().when(md.stats.possession.goe(HIGH_POSSESSION_THRESHOLD)).then(1).otherwise(0).sumAggregate();
    }

    private NumberExpression<Integer> lowPossessionCase(QMatchDetail md) {
        return new CaseBuilder().when(md.stats.possession.loe(LOW_POSSESSION_THRESHOLD)).then(1).otherwise(0).sumAggregate();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nzl(Long value) {
        return value == null ? 0L : value;
    }

    private static double nzd(Double value) {
        return value == null ? 0.0 : value;
    }
}
