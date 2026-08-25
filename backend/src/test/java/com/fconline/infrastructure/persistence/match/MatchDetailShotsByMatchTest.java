package com.fconline.infrastructure.persistence.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fconline.domain.match.Match;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.ShootEvent;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchShotDetail;
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
 * findShotsByMatch()가 딱 그 matchId+ouid 시점의 슛만 돌려주는지(다른 매치/다른 참가자 행은
 * 섞이지 않는지), 어시스트 정보까지 그대로 나오는지 확인하는 통합 테스트(매치 상세 모달용).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MatchDetailShotsByMatchTest {

    @Autowired
    private MatchDetailRepository matchDetailRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 특정_매치의_슛_이벤트만_어시스트_정보와_함께_돌려준다() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        Match targetMatch = Match.of("match-1", date, MatchType.CUSTOM);
        Match otherMatch = Match.of("match-2", date.plusSeconds(3600), MatchType.CUSTOM);
        entityManager.persist(targetMatch);
        entityManager.persist(otherMatch);

        MatchDetail target = MatchDetail.of(targetMatch, "A", "B", "상대", MatchResult.WIN,
                MatchStats.builder().matchEndType(0).build(), null, null, null);
        MatchDetail other = MatchDetail.of(otherMatch, "A", "B", "상대", MatchResult.WIN,
                MatchStats.builder().matchEndType(0).build(), null, null, null);
        // 미끼 — 같은 매치라도 "상대 자신의" 참가자 행(ouid=B)은 섞이면 안 된다.
        MatchDetail opponentsOwnRow = MatchDetail.of(targetMatch, "B", "A", "나", MatchResult.LOSE,
                MatchStats.builder().matchEndType(0).build(), null, null, null);

        target.addShootEvent(ShootEvent.of(target, ShootType.FINESSE, ShootResult.GOAL,
                23, 1, "sp-scorer", 7, null, false, 0.8, 0.5, true, "sp-assister", 0.6, 0.4, false, true));
        other.addShootEvent(ShootEvent.of(other, ShootType.HEADING, ShootResult.GOAL,
                10, 1, "sp-other-match", 5, null, false, 0.7, 0.5, false, null, null, null, null, false));
        opponentsOwnRow.addShootEvent(ShootEvent.of(opponentsOwnRow, ShootType.FINESSE, ShootResult.OFF_TARGET,
                5, 1, "sp-opponent", 3, null, false, 0.3, 0.5, false, null, null, null, null, false));

        matchDetailRepository.save(target);
        matchDetailRepository.save(other);
        matchDetailRepository.save(opponentsOwnRow);
        entityManager.flush();
        entityManager.clear();

        List<MatchShotDetail> shots = matchDetailRepository.findShotsByMatch("A", MatchType.CUSTOM, "match-1");

        assertThat(shots).hasSize(1);
        MatchShotDetail shot = shots.get(0);
        assertThat(shot.spId()).isEqualTo("sp-scorer");
        assertThat(shot.result()).isEqualTo(ShootResult.GOAL);
        assertThat(shot.assist()).isTrue();
        assertThat(shot.assistSpId()).isEqualTo("sp-assister");
        assertThat(shot.goalTimeMinutes()).isEqualTo(23);
    }
}
