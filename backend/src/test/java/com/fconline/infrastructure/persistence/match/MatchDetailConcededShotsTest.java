package com.fconline.infrastructure.persistence.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fconline.domain.match.Match;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.ShootEvent;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchStats;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.match.vo.ShootType;
import com.fconline.domain.match.vo.ShotPoint;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * findConcededShotPoints()가 쓰는 "관계로 매핑되지 않은 엔티티 간 ON 조건 조인"(md → 상대 자신의
 * MatchDetail 행 → 그 행의 ShootEvent)이 실제 SQL로 정상 번역되는지 확인하는 통합 테스트.
 * H2(MODE=PostgreSQL, ddl-auto=create-drop)로 JPQL 번역 자체를 검증한다 — Flyway/jsonb처럼
 * Postgres 전용 문법이 아니라 표준 조인이라 H2로도 충분히 신뢰할 수 있는 검증이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MatchDetailConcededShotsTest {

    @Autowired
    private MatchDetailRepository matchDetailRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 상대도_추적_대상이면_상대_본인_행의_슛을_실점_슛으로_돌려준다() {
        Instant matchDate = Instant.parse("2026-01-01T00:00:00Z");
        Match match = Match.of("match-1", matchDate, MatchType.CUSTOM);
        entityManager.persist(match);

        MatchDetail mine = MatchDetail.of(match, "A", "B", "비상대", MatchResult.WIN,
                MatchStats.builder().build(), null, null, null);
        MatchDetail opponentsOwnRow = MatchDetail.of(match, "B", "A", "나", MatchResult.LOSE,
                MatchStats.builder().build(), null, null, null);

        ShootEvent shotAgainstMe = ShootEvent.of(opponentsOwnRow, ShootType.FINESSE, ShootResult.GOAL,
                20, 1, "sp-1", null, null, null, 0.9, 0.5, false, null, null, null, null, true);
        opponentsOwnRow.addShootEvent(shotAgainstMe);

        // 미끼(negative control) — 추적 대상이 아닌 상대와의 매치는 결과에서 빠져야 한다.
        Match otherMatch = Match.of("match-2", matchDate, MatchType.CUSTOM);
        entityManager.persist(otherMatch);
        MatchDetail vsUntracked = MatchDetail.of(otherMatch, "A", "C", "비추적유저", MatchResult.WIN,
                MatchStats.builder().build(), null, null, null);

        matchDetailRepository.save(mine);
        matchDetailRepository.save(opponentsOwnRow);
        matchDetailRepository.save(vsUntracked);
        entityManager.flush();
        entityManager.clear();

        List<ShotPoint> conceded = matchDetailRepository.findConcededShotPoints("A", MatchType.CUSTOM, null, null);

        assertThat(conceded).hasSize(1);
        ShotPoint point = conceded.get(0);
        assertThat(point.x()).isEqualTo(0.9);
        assertThat(point.y()).isEqualTo(0.5);
        assertThat(point.shootType()).isEqualTo(ShootType.FINESSE);
        assertThat(point.result()).isEqualTo(ShootResult.GOAL);
    }
}
