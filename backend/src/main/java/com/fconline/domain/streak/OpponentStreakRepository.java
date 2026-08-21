package com.fconline.domain.streak;

import com.fconline.domain.match.vo.MatchType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpponentStreakRepository extends JpaRepository<OpponentStreak, Long> {

    Optional<OpponentStreak> findByOuidAndOpponentOuidAndMatchTypeAndSeasonId(
            String ouid, String opponentOuid, MatchType matchType, Long seasonId);
}
