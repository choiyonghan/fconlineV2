package com.fconline.domain.streak;

import static org.assertj.core.api.Assertions.assertThat;

import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchType;
import org.junit.jupiter.api.Test;

/**
 * v1의 스트릭 계산 버그(analysis 6.2)가 재발하지 않는지 확인하는 핵심 도메인 로직 테스트.
 */
class OpponentStreakTest {

    @Test
    void 연승이_이어지면_현재와_최다_연승이_함께_증가한다() {
        OpponentStreak streak = OpponentStreak.init("me", "opp", MatchType.CUSTOM, 1L);

        streak.applyResult(MatchResult.WIN);
        streak.applyResult(MatchResult.WIN);
        streak.applyResult(MatchResult.WIN);

        assertThat(streak.getCurWin()).isEqualTo(3);
        assertThat(streak.getMaxWin()).isEqualTo(3);
        assertThat(streak.getCurLose()).isZero();
    }

    @Test
    void 패배가_섞이면_현재_연승은_끊기지만_최다_연승_기록은_유지된다() {
        OpponentStreak streak = OpponentStreak.init("me", "opp", MatchType.CUSTOM, 1L);

        streak.applyResult(MatchResult.WIN);
        streak.applyResult(MatchResult.WIN);
        streak.applyResult(MatchResult.LOSE);

        assertThat(streak.getCurWin()).isZero();
        assertThat(streak.getMaxWin()).isEqualTo(2);
        assertThat(streak.getCurLose()).isEqualTo(1);
        assertThat(streak.getMaxLose()).isEqualTo(1);
    }

    @Test
    void 무승부는_무패와_무승_카운트를_함께_올린다() {
        OpponentStreak streak = OpponentStreak.init("me", "opp", MatchType.CUSTOM, 1L);

        streak.applyResult(MatchResult.WIN);
        streak.applyResult(MatchResult.DRAW);
        streak.applyResult(MatchResult.DRAW);

        assertThat(streak.getCurUnbeaten()).isEqualTo(3); // 승+무+무 = 무패 유지
        assertThat(streak.getCurWinless()).isEqualTo(2); // 무+무 = 무승 2
        assertThat(streak.getCurWin()).isZero(); // 마지막이 무승부라 연승은 끊김
    }

    @Test
    void reset_후에는_모든_값이_0이다() {
        OpponentStreak streak = OpponentStreak.init("me", "opp", MatchType.CUSTOM, 1L);
        streak.applyResult(MatchResult.WIN);
        streak.applyResult(MatchResult.WIN);

        streak.reset();

        assertThat(streak.getCurWin()).isZero();
        assertThat(streak.getMaxWin()).isZero();
    }
}
