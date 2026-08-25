package com.fconline.infrastructure.persistence.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fconline.domain.match.Match;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.ShootEvent;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchGoalEvent;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchStats;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.match.vo.ShootType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * findGoalEventsVsOpponent()가 내 골과 상대 골(상대 본인 관점)을 둘 다 mine 플래그와 함께
 * 돌려주는지 확인하는 통합 테스트("선제골" AI 인사이트 분석용).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MatchDetailGoalEventsVsOpponentTest {

    @Autowired
    private MatchDetailRepository matchDetailRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 내_골과_상대_골을_mine_플래그와_함께_돌려준다() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        Match match = Match.of("match-1", date, MatchType.CUSTOM);
        entityManager.persist(match);

        MatchDetail mine = MatchDetail.of(match, "A", "B", "상대", MatchResult.LOSE,
                MatchStats.builder().matchEndType(0).build(), null, null, null);
        MatchDetail opponentsOwnRow = MatchDetail.of(match, "B", "A", "나", MatchResult.WIN,
                MatchStats.builder().matchEndType(0).build(), null, null, null);

        // 내 골: 후반(2) 10분 = 절대 55분
        mine.addShootEvent(ShootEvent.of(mine, ShootType.FINESSE, ShootResult.GOAL,
                10, 2, "sp-me", 7, null, false, 0.8, 0.5, false, null, null, null, null, true));
        // 상대 골(상대 본인 관점): 전반(1) 5분 = 절대 5분 — 상대가 더 빨리 넣었다.
        opponentsOwnRow.addShootEvent(ShootEvent.of(opponentsOwnRow, ShootType.HEADING, ShootResult.GOAL,
                5, 1, "sp-opponent", 6, null, false, 0.7, 0.5, false, null, null, null, null, true));

        matchDetailRepository.save(mine);
        matchDetailRepository.save(opponentsOwnRow);
        entityManager.flush();
        entityManager.clear();

        List<MatchGoalEvent> events = matchDetailRepository.findGoalEventsVsOpponent(
                "A", MatchType.CUSTOM, null, null, "B");

        assertThat(events).hasSize(2);
        MatchGoalEvent myEvent = events.stream().filter(MatchGoalEvent::mine).findFirst().orElseThrow();
        assertThat(myEvent.matchId()).isEqualTo("match-1");
        assertThat(myEvent.minute()).isEqualTo(10);
        assertThat(myEvent.period()).isEqualTo(2);

        MatchGoalEvent oppEvent = events.stream().filter(e -> !e.mine()).findFirst().orElseThrow();
        assertThat(oppEvent.matchId()).isEqualTo("match-1");
        assertThat(oppEvent.minute()).isEqualTo(5);
        assertThat(oppEvent.period()).isEqualTo(1);
    }
}
