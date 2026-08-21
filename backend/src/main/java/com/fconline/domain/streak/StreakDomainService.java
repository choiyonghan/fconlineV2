package com.fconline.domain.streak;

import com.fconline.domain.match.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchType;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 상대별 연승/연패/무패/무승 계산의 유일한 구현.
 * v1은 이 로직이 fetch_and_store.js(updateUserOpponentStreaks, :83-210)와
 * fetch_official.js(:75-185)에 거의 동일하게 복제되어 있었고, 커스텀 쪽은 matchType
 * 필터가 빠져 두 파이프라인이 같은 행을 서로 다른 범위로 덮어쓰는 오염 버그가 있었다
 * (analysis 6.2). matchType/seasonId를 모든 호출에서 필수로 강제해 재발을 막는다.
 */
@Service
public class StreakDomainService {

    private final OpponentStreakRepository streakRepository;
    private final MatchDetailRepository matchDetailRepository;

    public StreakDomainService(OpponentStreakRepository streakRepository,
                                MatchDetailRepository matchDetailRepository) {
        this.streakRepository = streakRepository;
        this.matchDetailRepository = matchDetailRepository;
    }

    /**
     * 해당 시즌 구간의 전 경기를 시간순으로 다시 재생(replay)해 스트릭을 재계산한다.
     * v1처럼 매번 전량 재계산하지만, 정규화 테이블 덕분에 조회 대상이 (ouid, opponentOuid, result,
     * matchDate)뿐인 가벼운 프로젝션이라 shoot_detail/player_squad까지 끌고 오던 v1과 달리
     * 비용이 크지 않다.
     */
    public OpponentStreak recalculate(String ouid, String opponentOuid, MatchType matchType,
                                       Long seasonId, Instant seasonFrom, Instant seasonTo) {
        List<MatchResult> chronologicalResults = matchDetailRepository.findChronologicalResults(
                ouid, opponentOuid, matchType, seasonFrom, seasonTo);

        OpponentStreak streak = streakRepository
                .findByOuidAndOpponentOuidAndMatchTypeAndSeasonId(ouid, opponentOuid, matchType, seasonId)
                .orElseGet(() -> OpponentStreak.init(ouid, opponentOuid, matchType, seasonId));

        streak.reset();
        chronologicalResults.forEach(streak::applyResult);

        return streakRepository.save(streak);
    }
}
