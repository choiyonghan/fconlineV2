package com.fconline.domain.streak.repository;

import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.streak.OpponentStreak;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpponentStreakRepository extends JpaRepository<OpponentStreak, Long> {

    Optional<OpponentStreak> findByOuidAndOpponentOuidAndMatchTypeAndSeasonId(
            String ouid, String opponentOuid, MatchType matchType, Long seasonId);
}
