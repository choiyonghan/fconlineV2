package com.fconline.infrastructure.persistence.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fconline.domain.match.Match;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.ShootEvent;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchStats;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.PlayerGrade;
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
 * findLatestSpGrades()가 spId별로 "가장 최근 매치"의 강화 단계만 골라내는지 확인하는 통합 테스트.
 * shoot_events.sp_grade는 이미 저장돼 있는 컬럼이라 마이그레이션 없이 그대로 조회한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MatchDetailPlayerGradesTest {

    @Autowired
    private MatchDetailRepository matchDetailRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void spId별로_가장_최근_매치의_강화_단계만_돌려준다() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-02T00:00:00Z");

        Match match1 = Match.of("match-1", older, MatchType.CUSTOM);
        Match match2 = Match.of("match-2", newer, MatchType.CUSTOM);
        entityManager.persist(match1);
        entityManager.persist(match2);

        MatchDetail detail1 = MatchDetail.of(match1, "A", "B", "상대", MatchResult.WIN,
                MatchStats.builder().build(), null, null, null);
        MatchDetail detail2 = MatchDetail.of(match2, "A", "B", "상대", MatchResult.WIN,
                MatchStats.builder().build(), null, null, null);

        // sp-1: 옛날 매치엔 5강, 최근 매치엔 8강으로 강화됨 — 최근 값(8)만 나와야 한다.
        detail1.addShootEvent(ShootEvent.of(detail1, ShootType.FINESSE, ShootResult.GOAL,
                10, 1, "sp-1", 5, null, false, 0.5, 0.5, false, null, null, null, null, true));
        detail2.addShootEvent(ShootEvent.of(detail2, ShootType.FINESSE, ShootResult.GOAL,
                10, 1, "sp-1", 8, null, false, 0.5, 0.5, false, null, null, null, null, true));
        // sp-2: 옛날 매치에서만 슛을 쐈다 — 그 값(3)이 그대로 "가장 최근"이다.
        detail1.addShootEvent(ShootEvent.of(detail1, ShootType.HEADING, ShootResult.OFF_TARGET,
                20, 1, "sp-2", 3, null, false, 0.4, 0.4, false, null, null, null, null, false));

        matchDetailRepository.save(detail1);
        matchDetailRepository.save(detail2);
        entityManager.flush();
        entityManager.clear();

        List<PlayerGrade> grades = matchDetailRepository.findLatestSpGrades("A", MatchType.CUSTOM, null, null);

        assertThat(grades).hasSize(2);
        assertThat(grades).filteredOn(g -> g.spId().equals("sp-1")).extracting(PlayerGrade::grade).containsExactly(8);
        assertThat(grades).filteredOn(g -> g.spId().equals("sp-2")).extracting(PlayerGrade::grade).containsExactly(3);
    }
}
