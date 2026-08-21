package com.fconline.domain.score.repository;

import com.fconline.domain.score.ScoreRule;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoreRuleRepository extends JpaRepository<ScoreRule, Long> {

    Optional<ScoreRule> findByTargetOuid(String targetOuid);
}
