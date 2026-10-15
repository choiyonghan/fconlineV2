package com.fconline.domain.match.service;

import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.AssistChainCount;
import com.fconline.domain.match.vo.FirstGoalResult;
import com.fconline.domain.match.vo.GoalTimeBuckets;
import com.fconline.domain.match.vo.GoalTimeCount;
import com.fconline.domain.match.vo.GoalTimeRaw;
import com.fconline.domain.match.vo.GoalTypeCount;
import com.fconline.domain.match.vo.PlayerShotPoint;
import com.fconline.domain.match.vo.MatchGoalEvent;
import com.fconline.domain.match.vo.MatchPlayerRating;
import com.fconline.domain.match.vo.MatchStatsSummary;
import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.MatchShotDetail;
import com.fconline.domain.match.vo.OpponentTally;
import com.fconline.domain.match.vo.PlayerGrade;
import com.fconline.domain.match.vo.ShotPoint;
import com.fconline.domain.match.vo.TopPlayerStat;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 종합 전적 카드(화면 3)에 필요한 집계를 조합하는 도메인 서비스.
 * v1은 이 집계 전부를 클라이언트 JS가 매번 계산했다 — v2는 QueryDSL GROUP BY로 DB에서
 * 끝낸 결과를 여기서 조합만 한다.
 */
@Service
public class MatchDomainService {

    private final MatchDetailRepository matchDetailRepository;

    public MatchDomainService(MatchDetailRepository matchDetailRepository) {
        this.matchDetailRepository = matchDetailRepository;
    }

    public MatchTally overallTally(String ouid, MatchType matchType, Instant from, Instant to) {
        return matchDetailRepository.aggregateTally(ouid, matchType, from, to, null);
    }

    public MatchStatsSummary statsSummary(String ouid, MatchType matchType, Instant from, Instant to) {
        return matchDetailRepository.aggregateStatsSummary(ouid, matchType, from, to);
    }

    public List<TopPlayerStat> topPlayers(String ouid, MatchType matchType, Instant from, Instant to,
                                           String opponentOuid, int limit) {
        return matchDetailRepository.aggregateTopPlayers(ouid, matchType, from, to, opponentOuid, limit);
    }

    public List<GoalTypeCount> goalTypeDistribution(String ouid, MatchType matchType, Instant from, Instant to) {
        return matchDetailRepository.aggregateGoalTypeDistribution(ouid, matchType, from, to);
    }

    public List<OpponentTally> opponentTallies(String ouid, MatchType matchType, Instant from, Instant to) {
        return matchDetailRepository.aggregateOpponentTallies(ouid, matchType, from, to)
                .stream()
                .sorted(Comparator.comparing(OpponentTally::opponentNickname))
                .toList();
    }

    public List<MatchPlayerRating> matchPlayerRatings(String ouid, MatchType matchType, Instant from, Instant to) {
        return matchDetailRepository.findMatchPlayerRatings(ouid, matchType, from, to);
    }

    /** 득점 시각(분) 원시값을 GoalTimeBuckets로 전반/후반/추가시간/연장전 버킷으로 집계한다. */
    public List<GoalTimeCount> goalTimeDistribution(String ouid, MatchType matchType, Instant from, Instant to) {
        List<GoalTimeRaw> raws = matchDetailRepository.findGoalMinutes(ouid, matchType, from, to);
        return bucketize(raws);
    }

    /** goalTimeDistribution의 실점판 — findConcededGoalMinutes(전체 상대 합산)로 같은 버킷을 채운다. */
    public List<GoalTimeCount> concededGoalTimeDistribution(String ouid, MatchType matchType, Instant from, Instant to) {
        List<GoalTimeRaw> raws = matchDetailRepository.findConcededGoalMinutes(ouid, matchType, from, to);
        return bucketize(raws);
    }

    private List<GoalTimeCount> bucketize(List<GoalTimeRaw> raws) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String label : GoalTimeBuckets.ORDERED_LABELS) {
            counts.put(label, 0L);
        }

        for (GoalTimeRaw raw : raws) {
            String label = GoalTimeBuckets.labelFor(raw.minute(), raw.period());
            counts.merge(label, 1L, Long::sum);
        }

        return counts.entrySet().stream()
                .map(e -> new GoalTimeCount(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * 좌표 히트맵용 슛 위치 원시 목록. goalsOnly=true면 득점한 슛만.
     * opponentOuid가 null이면 전체 상대 합산, 지정하면 그 상대와의 경기만.
     */
    public List<ShotPoint> shotHeatmap(String ouid, MatchType matchType, Instant from, Instant to,
                                        String opponentOuid, boolean goalsOnly) {
        return matchDetailRepository.findShotPoints(ouid, matchType, from, to, opponentOuid, goalsOnly);
    }

    /** xA(기대 어시스트) 히트맵용 — 어시스트가 달린 슛 좌표 원시 목록(스코프 전체, 골 여부 무관). */
    public List<ShotPoint> assistedShotHeatmap(String ouid, MatchType matchType, Instant from, Instant to,
                                                String opponentOuid) {
        return matchDetailRepository.findAssistedShotPoints(ouid, matchType, from, to, opponentOuid);
    }

    /** "전체 선수 스탯"의 선수별 xG 합산용 — spId가 붙은 슛 좌표 전체. */
    public List<PlayerShotPoint> playerShotPoints(String ouid, MatchType matchType, Instant from, Instant to,
                                                   String opponentOuid) {
        return matchDetailRepository.findShotPointsByPlayer(ouid, matchType, from, to, opponentOuid);
    }

    /** "전체 선수 스탯"의 선수별 xA 합산용 — 어시스트 제공자 기준으로 묶은 슛 좌표(노골 포함). */
    public List<PlayerShotPoint> assistedShotPoints(String ouid, MatchType matchType, Instant from, Instant to,
                                                      String opponentOuid) {
        return matchDetailRepository.findAssistedShotPointsByPlayer(ouid, matchType, from, to, opponentOuid);
    }

    /**
     * "실점 xG값"용 — 추적 대상 상대가 이 유저를 향해 쏜 슛 좌표 목록(상대가 추적 대상인 매치만).
     * opponentOuid가 null이면 전체 상대 합산, 지정하면 그 상대와의 경기만.
     */
    public List<ShotPoint> concededShotHeatmap(String ouid, MatchType matchType, Instant from, Instant to,
                                                String opponentOuid) {
        return matchDetailRepository.findConcededShotPoints(ouid, matchType, from, to, opponentOuid);
    }

    /** 매치 상세 모달용 — 특정 매치 1건의 슛 이벤트 전체(위치/유형/결과/득점 시각/어시스트). */
    public List<MatchShotDetail> shotsByMatch(String ouid, MatchType matchType, String matchId) {
        return matchDetailRepository.findShotsByMatch(ouid, matchType, matchId);
    }

    /** 매치 상세 모달의 "실점 상세"용 — 상대가 추적 대상이어야 채워진다(아니면 빈 목록). */
    public List<MatchShotDetail> concededShotsByMatch(String ouid, MatchType matchType, String matchId) {
        return matchDetailRepository.findConcededShotsByMatch(ouid, matchType, matchId);
    }

    /** 어시스트 선수 -> 득점 선수 조합별 골 수, 내림차순 상위 limit건. */
    public List<AssistChainCount> assistChains(String ouid, MatchType matchType, Instant from, Instant to, int limit) {
        return matchDetailRepository.aggregateAssistChains(ouid, matchType, from, to, limit);
    }

    /** spId별 가장 최근 매치에서 관측된 카드 강화 단계(0~11강). */
    public List<PlayerGrade> latestSpGrades(String ouid, MatchType matchType, Instant from, Instant to) {
        return matchDetailRepository.findLatestSpGrades(ouid, matchType, from, to);
    }

    /**
     * 특정 상대와의 매치들에서 나온 골 이벤트 원시값(누구 골인지 + 시각) — AI 인사이트 스냅샷이
     * "선제골 요약"뿐 아니라 매치별 골 타임라인 원문까지 그대로 요약해 넣을 때 쓴다(질문마다
     * 새 집계 API를 만들지 않고, AI가 원시 타임라인을 보고 직접 분석할 수 있게 하기 위함).
     */
    public List<MatchGoalEvent> goalEventsVsOpponent(String ouid, MatchType matchType, Instant from, Instant to,
                                                       String opponentOuid) {
        return matchDetailRepository.findGoalEventsVsOpponent(ouid, matchType, from, to, opponentOuid);
    }

    /**
     * 특정 상대와의 매치별 "선제골"(누가 먼저 넣었는지) — AI 인사이트 스냅샷의 선제골 분석용.
     * 매치별로 골 이벤트를 절대 분(period 오프셋 반영)으로 환산해 가장 이른 골의 주체를 고른다.
     * 무득점으로 끝난 매치는 결과에 안 나온다(고를 골이 없으므로).
     */
    public List<FirstGoalResult> firstGoalScorers(String ouid, MatchType matchType, Instant from, Instant to,
                                                   String opponentOuid) {
        List<MatchGoalEvent> events = matchDetailRepository.findGoalEventsVsOpponent(ouid, matchType, from, to, opponentOuid);

        Map<String, MatchGoalEvent> earliestByMatch = new LinkedHashMap<>();
        for (MatchGoalEvent event : events) {
            int absoluteMinute = absoluteMinute(event.minute(), event.period());
            MatchGoalEvent current = earliestByMatch.get(event.matchId());
            if (current == null || absoluteMinute < absoluteMinute(current.minute(), current.period())) {
                earliestByMatch.put(event.matchId(), event);
            }
        }

        return earliestByMatch.entrySet().stream()
                .map(e -> new FirstGoalResult(e.getKey(), e.getValue().mine()))
                .toList();
    }

    /**
     * minute(period 시작 기준 경과분) + period를 "경기 시작 기준 누적 분"으로 환산한다 —
     * NexonApiClient의 goalTime 인코딩 주석과 동일한 period 정의(전반/후반/연장전반/연장후반/
     * 승부차기)를 따른다. period가 null이거나 1~5 밖이면(예전 데이터 결측) 오프셋 없이 그대로 쓴다.
     * (goalTimeDistribution의 15분 버킷팅은 전반/후반 추가시간을 구분해야 해서 GoalTimeBuckets를
     * 따로 쓴다 — 여기는 "가장 이른 골"을 고르는 단순 비교용이라 오프셋만 더하면 충분하다.)
     */
    private static final int[] PERIOD_OFFSET_MINUTES = {0, 0, 45, 90, 105, 120};

    public int absoluteMinute(Integer minute, Integer period) {
        return nz(minute) + periodOffset(period);
    }

    private static int periodOffset(Integer period) {
        if (period == null || period < 1 || period >= PERIOD_OFFSET_MINUTES.length) {
            return 0;
        }
        return PERIOD_OFFSET_MINUTES[period];
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
