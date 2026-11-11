package com.fconline.domain.streak;

import com.fconline.domain.match.vo.ChronologicalResult;
import com.fconline.domain.match.vo.MatchResult;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * 한 상대와의 "역대"(시즌 무관 전 기간) 최고 기록 4종 — 최다 연승 / 최다 무패(승·무) /
 * 최다 무승(무·패) / 최다 연패. 카운터 규칙은 {@link OpponentStreak#applyResult}와 같지만,
 * 그쪽은 시즌별로 저장되는 카운터만 들고 있고 이쪽은 "언제부터 언제까지"였는지 기간까지 낸다.
 * DB에 저장하지 않고 조회 때마다 계산한다(친구 그룹이라 상대 1명과의 전체 경기가 많아야 수백 건).
 *
 * 같은 길이의 기록이 여러 번 있으면 가장 최근 것을 고른다.
 */
public record StreakRecords(int totalMatches, Instant firstMatchDate, Instant lastMatchDate,
                             Run win, Run unbeaten, Run winless, Run lose) {

    /**
     * @param count   기록 길이(0이면 기록 없음 — from/to는 null)
     * @param from    기록이 시작된 경기 시각
     * @param to      기록이 끝난(마지막으로 이어진) 경기 시각
     * @param ongoing 그 기록이 가장 최근 경기까지 이어지고 있는지(= 아직 안 끊김)
     */
    public record Run(int count, Instant from, Instant to, boolean ongoing) {
        static final Run NONE = new Run(0, null, null, false);
    }

    /** results는 반드시 경기 시각 오름차순이어야 한다. */
    public static StreakRecords from(List<ChronologicalResult> results) {
        if (results.isEmpty()) {
            return new StreakRecords(0, null, null, Run.NONE, Run.NONE, Run.NONE, Run.NONE);
        }
        return new StreakRecords(
                results.size(),
                results.get(0).matchDate(),
                results.get(results.size() - 1).matchDate(),
                longestRun(results, r -> r == MatchResult.WIN),
                longestRun(results, r -> r != MatchResult.LOSE),
                longestRun(results, r -> r != MatchResult.WIN),
                longestRun(results, r -> r == MatchResult.LOSE));
    }

    private static Run longestRun(List<ChronologicalResult> results, Predicate<MatchResult> continues) {
        Run best = Run.NONE;
        int cur = 0;
        Instant curFrom = null;
        int last = results.size() - 1;
        for (int i = 0; i <= last; i++) {
            ChronologicalResult r = results.get(i);
            if (!continues.test(r.result())) {
                cur = 0;
                continue;
            }
            if (cur == 0) {
                curFrom = r.matchDate();
            }
            cur++;
            if (cur >= best.count()) {
                best = new Run(cur, curFrom, r.matchDate(), i == last);
            }
        }
        return best;
    }
}
