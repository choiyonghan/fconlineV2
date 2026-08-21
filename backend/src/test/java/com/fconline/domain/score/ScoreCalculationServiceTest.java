package com.fconline.domain.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fconline.domain.match.MatchTally;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * "욱식 점수"(v1 app.js:679-707의 하드코딩 규칙) 계산 로직 검증.
 */
@ExtendWith(MockitoExtension.class)
class ScoreCalculationServiceTest {

    @Mock
    private ScoreRuleRepository scoreRuleRepository;

    @Test
    void 규칙이_있으면_해당_가중치로_계산한다() {
        ScoreCalculationService service = new ScoreCalculationService(scoreRuleRepository);
        when(scoreRuleRepository.findByTargetOuid("wook-ouid"))
                .thenReturn(Optional.of(ScoreRule.of("wook-ouid", 5, 3, 1)));

        int score = service.calculate("wook-ouid", new MatchTally(2, 1, 1, 0, 0));

        // 2승*5 + 1무*3 + 1패*1 = 14
        assertThat(score).isEqualTo(14);
    }

    @Test
    void 규칙이_없으면_표준_축구_승점으로_계산한다() {
        ScoreCalculationService service = new ScoreCalculationService(scoreRuleRepository);
        when(scoreRuleRepository.findByTargetOuid("someone-else")).thenReturn(Optional.empty());

        int score = service.calculate("someone-else", new MatchTally(2, 1, 1, 0, 0));

        // 2승*3 + 1무*1 + 1패*0 = 7
        assertThat(score).isEqualTo(7);
    }
}
