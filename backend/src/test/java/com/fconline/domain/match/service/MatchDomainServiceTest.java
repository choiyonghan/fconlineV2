package com.fconline.domain.match.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.FirstGoalResult;
import com.fconline.domain.match.vo.GoalTimeCount;
import com.fconline.domain.match.vo.GoalTimeRaw;
import com.fconline.domain.match.vo.MatchGoalEvent;
import com.fconline.domain.match.vo.MatchType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 시간대별 득점 분포 버킷팅 로직 검증. v1은 이 집계를 app.js/official.js가 각자 클라이언트에서
 * 계산했다(analysis 6.10) — v2는 이 도메인 서비스 하나가 유일한 구현이다.
 *
 * minute은 period(전반=1/후반=2/연장전반=3/연장후반=4/승부차기=5) 시작 시점 기준 경과분이라,
 * "경기 시작 기준 누적 분"으로 바꾸려면 period별 오프셋(0/45/90/105/120)을 더해야 한다 —
 * 이 오프셋을 빼먹으면 후반/연장 골이 전부 0~45분대로 잘못 집계되는 버그가 났었다(아래
 * (period=2, minute=1) 케이스가 그 회귀 방지용: 오프셋 없이는 "1-15"에, 있으면 "46-60"에 들어가야 한다).
 */
@ExtendWith(MockitoExtension.class)
class MatchDomainServiceTest {

    @Mock
    private MatchDetailRepository matchDetailRepository;

    @Test
    void 득점_시각을_period_오프셋을_더한_뒤_15분_단위_버킷으로_집계한다() {
        MatchDomainService service = new MatchDomainService(matchDetailRepository);
        when(matchDetailRepository.findGoalMinutes("me", MatchType.CUSTOM, null, null))
                .thenReturn(List.of(
                        new GoalTimeRaw(3, 1),   // 전반 3분 -> 3
                        new GoalTimeRaw(16, 1),  // 전반 16분 -> 16
                        new GoalTimeRaw(44, 1),  // 전반 44분 -> 44
                        new GoalTimeRaw(1, 2),   // 후반 시작 1분 -> 45+1=46 (오프셋 없으면 "1-15"로 오집계되던 케이스)
                        new GoalTimeRaw(30, 2),  // 후반 30분 -> 45+30=75
                        new GoalTimeRaw(45, 2),  // 후반 45분 -> 45+45=90
                        new GoalTimeRaw(5, 3),   // 연장전반 5분 -> 90+5=95
                        new GoalTimeRaw(0, 5),   // 승부차기 -> 120+0=120
                        new GoalTimeRaw(91, null) // period 결측 — 오프셋 없이 그대로
                ));

        List<GoalTimeCount> result = service.goalTimeDistribution("me", MatchType.CUSTOM, null, null);

        assertThat(result)
                .extracting(GoalTimeCount::bucketLabel, GoalTimeCount::count)
                .containsExactly(
                        tuple("1-15", 1L), // 3
                        tuple("16-30", 1L), // 16
                        tuple("31-45", 1L), // 44
                        tuple("전반추가시간", 0L),
                        tuple("46-60", 1L), // 46
                        tuple("61-75", 1L), // 75
                        tuple("76-90", 1L), // 90
                        tuple("후반추가시간", 0L),
                        tuple("연장전", 3L) // 95, 120, 91
                );
    }

    /**
     * 후반 추가시간이 길면(예: 후반 45+3분) 절대분이 90을 넘는다 — 예전엔 이 경우를 그냥
     * "연장전"으로 묻었는데, period=2(후반)인 이상 실제 연장전이 아니라 정규시간 추가시간
     * 득점이다. GoalTimeBuckets.labelFor가 절대분이 아니라 period+로컬분을 직접 보고
     * "전반추가시간"/"후반추가시간"으로 따로 집계하는지 검증한다.
     */
    @Test
    void 전후반_추가시간_골은_연장전이_아니라_추가시간_버킷으로_집계한다() {
        MatchDomainService service = new MatchDomainService(matchDetailRepository);
        when(matchDetailRepository.findGoalMinutes("me", MatchType.CUSTOM, null, null))
                .thenReturn(List.of(
                        new GoalTimeRaw(47, 1), // 전반 45+2분 추가시간
                        new GoalTimeRaw(48, 2)  // 후반 45+3분 추가시간 -> 절대분 93(과거엔 "연장전"으로 오집계)
                ));

        List<GoalTimeCount> result = service.goalTimeDistribution("me", MatchType.CUSTOM, null, null);

        assertThat(result)
                .filteredOn(c -> c.count() > 0)
                .extracting(GoalTimeCount::bucketLabel, GoalTimeCount::count)
                .containsExactly(
                        tuple("전반추가시간", 1L),
                        tuple("후반추가시간", 1L)
                );
    }

    @Test
    void 매치별로_절대_분이_가장_이른_골의_주체를_선제골로_고른다() {
        MatchDomainService service = new MatchDomainService(matchDetailRepository);
        when(matchDetailRepository.findGoalEventsVsOpponent("me", MatchType.CUSTOM, null, null, "opp"))
                .thenReturn(List.of(
                        // match-1: 내 골(후반 10분=55) vs 상대 골(전반 5분=5) — 상대가 더 빠름
                        new MatchGoalEvent("match-1", 10, 2, true),
                        new MatchGoalEvent("match-1", 5, 1, false),
                        // match-2: 내 골만 있음(전반 20분) — 내가 선제골
                        new MatchGoalEvent("match-2", 20, 1, true),
                        // match-3: 내가 먼저(전반 3분), 나중에 상대(전반 40분) — 그래도 내가 선제골
                        new MatchGoalEvent("match-3", 3, 1, true),
                        new MatchGoalEvent("match-3", 40, 1, false)
                ));

        List<FirstGoalResult> result = service.firstGoalScorers("me", MatchType.CUSTOM, null, null, "opp");

        assertThat(result)
                .extracting(FirstGoalResult::matchId, FirstGoalResult::mine)
                .containsExactlyInAnyOrder(
                        tuple("match-1", false),
                        tuple("match-2", true),
                        tuple("match-3", true)
                );
    }
}
