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
 * findConcededShotsByMatch()가 특정 매치 1건에서 "상대 본인 관점" 행의 슛만 돌려주는지(상대가
 * 추적 대상일 때), 추적 대상이 아니면 빈 목록을 돌려주는지 확인하는 통합 테스트(매치 상세 모달의
 * "실점 상세"용).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MatchDetailConcededShotsByMatchTest {

    @Autowired
    private MatchDetailRepository matchDetailRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 상대도_추적_대상이면_그_매치의_상대_슛만_돌려준다() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        Match match = Match.of("match-1", date, MatchType.CUSTOM);
        entityManager.persist(match);

        MatchDetail mine = MatchDetail.of(match, "A", "B", "상대", MatchResult.LOSE,
                MatchStats.builder().build(), null, null, null);
        MatchDetail opponentsOwnRow = MatchDetail.of(match, "B", "A", "나", MatchResult.WIN,
                MatchStats.builder().build(), null, null, null);

        opponentsOwnRow.addShootEvent(ShootEvent.of(opponentsOwnRow, ShootType.FINESSE, ShootResult.GOAL,
                30, 1, "sp-opponent-scorer", 6, null, false, 0.85, 0.5, false, null, null, null, null, true));

        matchDetailRepository.save(mine);
        matchDetailRepository.save(opponentsOwnRow);
        entityManager.flush();
        entityManager.clear();

        List<MatchShotDetail> conceded = matchDetailRepository.findConcededShotsByMatch("A", MatchType.CUSTOM, "match-1");

        assertThat(conceded).hasSize(1);
        assertThat(conceded.get(0).spId()).isEqualTo("sp-opponent-scorer");
        assertThat(conceded.get(0).result()).isEqualTo(ShootResult.GOAL);
    }

    @Test
    void 상대가_추적_대상이_아니면_빈_목록을_돌려준다() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        Match match = Match.of("match-2", date, MatchType.CUSTOM);
        entityManager.persist(match);

        MatchDetail mine = MatchDetail.of(match, "A", "untracked-opponent", "비추적유저", MatchResult.WIN,
                MatchStats.builder().build(), null, null, null);
        matchDetailRepository.save(mine);
        entityManager.flush();
        entityManager.clear();

        List<MatchShotDetail> conceded = matchDetailRepository.findConcededShotsByMatch("A", MatchType.CUSTOM, "match-2");

        assertThat(conceded).isEmpty();
    }
}
