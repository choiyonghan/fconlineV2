package com.fconline.domain.score.service;

import com.fconline.domain.match.vo.MatchTally;
import com.fconline.domain.score.ScoreRule;
import com.fconline.domain.score.repository.ScoreRuleRepository;
import org.springframework.stereotype.Service;

/**
 * ScoreRule을 실제 전적(MatchTally)에 적용해 "욱식 점수"를 계산한다.
 * 규칙이 없는 유저는 표준 축구 승점(승3/무1/패0)을 기본값으로 사용한다.
 */
@Service
public class ScoreCalculationService {

    private static final ScoreRule DEFAULT_RULE = ScoreRule.of(null, 3, 1, 0);

    private final ScoreRuleRepository scoreRuleRepository;

    public ScoreCalculationService(ScoreRuleRepository scoreRuleRepository) {
        this.scoreRuleRepository = scoreRuleRepository;
    }

    public int calculate(String targetOuid, MatchTally tally) {
        ScoreRule rule = scoreRuleRepository.findByTargetOuid(targetOuid).orElse(DEFAULT_RULE);
        return tally.wins() * rule.getWinPoints()
                + tally.draws() * rule.getDrawPoints()
                + tally.losses() * rule.getLosePoints();
    }
}
