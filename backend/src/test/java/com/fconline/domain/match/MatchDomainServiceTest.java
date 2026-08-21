package com.fconline.domain.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

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
 */
@ExtendWith(MockitoExtension.class)
class MatchDomainServiceTest {

    @Mock
    private MatchDetailRepository matchDetailRepository;

    @Test
    void 득점_시각을_15분_단위_버킷으로_집계한다() {
        MatchDomainService service = new MatchDomainService(matchDetailRepository);
        when(matchDetailRepository.findGoalMinutes("me", MatchType.CUSTOM, null, null))
                .thenReturn(List.of(3, 14, 15, 16, 44, 45, 89, 90, 91, 120));

        List<GoalTimeCount> result = service.goalTimeDistribution("me", MatchType.CUSTOM, null, null);

        assertThat(result)
                .extracting(GoalTimeCount::bucketLabel, GoalTimeCount::count)
                .containsExactly(
                        tuple("1-15", 3L), // 3, 14, 15
                        tuple("16-30", 1L), // 16
                        tuple("31-45", 2L), // 44, 45
                        tuple("46-60", 0L),
                        tuple("61-75", 0L),
                        tuple("76-90", 2L), // 89, 90
                        tuple("연장전", 2L) // 91, 120
                );
    }
}
