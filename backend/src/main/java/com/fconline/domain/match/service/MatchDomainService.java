package com.fconline.domain.match.service;

import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.AssistChainCount;
import com.fconline.domain.match.vo.GoalTimeCount;
import com.fconline.domain.match.vo.GoalTimeRaw;
import com.fconline.domain.match.vo.GoalTypeCount;
import com.fconline.domain.match.vo.MatchStatsSummary;
import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.OpponentTally;
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

    /** 90분(+연장)을 15분 단위로 나눈 시간대 라벨. v1의 시간대 분포 집계 로직을 단일화. */
    private static final int[] BUCKET_UPPER_BOUNDS = {15, 30, 45, 60, 75, 90};
    private static final String EXTRA_TIME_LABEL = "연장전";

    /**
     * ShootEvent.goalTimeMinutes는 period(1 전반~5 승부차기) 시작 시점 기준 경과분이라, 절대
     * "경기 시작 기준 누적 분"으로 바꾸려면 이 오프셋을 더해야 한다 — NexonApiClient의 goalTime
     * 인코딩 주석과 동일한 period 정의(전반/후반/연장전반/연장후반/승부차기)를 그대로 따른다.
     * period가 null이거나 1~5 밖이면(예전 데이터 결측) 오프셋 없이 그대로 쓴다.
     */
    private static final int[] PERIOD_OFFSET_MINUTES = {0, 0, 45, 90, 105, 120};

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

    public List<TopPlayerStat> topPlayers(String ouid, MatchType matchType, Instant from, Instant to, int limit) {
        return matchDetailRepository.aggregateTopPlayers(ouid, matchType, from, to, limit);
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

    /** 득점 시각(분) 원시값을 절대 누적 분으로 환산해 15분 단위 버킷으로 집계한다. */
    public List<GoalTimeCount> goalTimeDistribution(String ouid, MatchType matchType, Instant from, Instant to) {
        List<GoalTimeRaw> raws = matchDetailRepository.findGoalMinutes(ouid, matchType, from, to);

        Map<String, Long> counts = new LinkedHashMap<>();
        for (int upper : BUCKET_UPPER_BOUNDS) {
            counts.put(bucketLabel(upper), 0L);
        }
        counts.put(EXTRA_TIME_LABEL, 0L);

        for (GoalTimeRaw raw : raws) {
            int absoluteMinute = raw.minute() + periodOffset(raw.period());
            String label = bucketLabelFor(absoluteMinute);
            counts.merge(label, 1L, Long::sum);
        }

        return counts.entrySet().stream()
                .map(e -> new GoalTimeCount(e.getKey(), e.getValue()))
                .toList();
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
        int lower = upper - 14;
        return lower + "-" + upper;
    }

    /** 좌표 히트맵용 슛 위치 원시 목록. goalsOnly=true면 득점한 슛만. */
    public List<ShotPoint> shotHeatmap(String ouid, MatchType matchType, Instant from, Instant to, boolean goalsOnly) {
        return matchDetailRepository.findShotPoints(ouid, matchType, from, to, goalsOnly);
    }

    /** "실점 xG값"용 — 추적 대상 상대가 이 유저를 향해 쏜 슛 좌표 목록(상대가 추적 대상인 매치만). */
    public List<ShotPoint> concededShotHeatmap(String ouid, MatchType matchType, Instant from, Instant to) {
        return matchDetailRepository.findConcededShotPoints(ouid, matchType, from, to);
    }

    /** 어시스트 선수 -> 득점 선수 조합별 골 수, 내림차순 상위 limit건. */
    public List<AssistChainCount> assistChains(String ouid, MatchType matchType, Instant from, Instant to, int limit) {
        return matchDetailRepository.aggregateAssistChains(ouid, matchType, from, to, limit);
    }
}
