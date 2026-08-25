package com.fconline.infrastructure.persistence.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fconline.domain.match.Match;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.SquadEntry;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchStats;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.TopPlayerStat;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * aggregateTopPlayers(..., opponentOuid, ...)가 opponentOuid를 지정했을 때 그 상대와의 경기만
 * 집계하는지 확인하는 통합 테스트("상대별 전적" 행을 펼쳤을 때 보여주는 상대전 TOP3용).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MatchDetailTopPlayersVsOpponentTest {

    @Autowired
    private MatchDetailRepository matchDetailRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void opponentOuid를_지정하면_그_상대와의_경기만_집계한다() {
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        Match matchVsB = Match.of("match-vs-b", date, MatchType.CUSTOM);
        Match matchVsC = Match.of("match-vs-c", date.plusSeconds(3600), MatchType.CUSTOM);
        entityManager.persist(matchVsB);
        entityManager.persist(matchVsC);

        MatchDetail detailVsB = MatchDetail.of(matchVsB, "A", "B", "상대B", MatchResult.WIN,
                MatchStats.builder().matchEndType(0).build(), null, null, null);
        MatchDetail detailVsC = MatchDetail.of(matchVsC, "A", "C", "상대C", MatchResult.WIN,
                MatchStats.builder().matchEndType(0).build(), null, null, null);

        // B전: sp-1이 2골, C전: sp-1이 5골 — opponentOuid="B"로 필터링하면 2골만 나와야 한다.
        detailVsB.addSquadEntry(SquadEntry.of(detailVsB, "sp-1", 1,
                2, 0, 0, 0, 0, 0, 3, 2, 10, 8, 0, 0, 0, 0, 0, 7.5));
        detailVsC.addSquadEntry(SquadEntry.of(detailVsC, "sp-1", 1,
                5, 0, 0, 0, 0, 0, 6, 5, 12, 10, 0, 0, 0, 0, 0, 8.5));

        matchDetailRepository.save(detailVsB);
        matchDetailRepository.save(detailVsC);
        entityManager.flush();
        entityManager.clear();

        List<TopPlayerStat> vsB = matchDetailRepository.aggregateTopPlayers(
                "A", MatchType.CUSTOM, null, null, "B", 10);
        assertThat(vsB).hasSize(1);
        assertThat(vsB.get(0).spId()).isEqualTo("sp-1");
        assertThat(vsB.get(0).goals()).isEqualTo(2);

        List<TopPlayerStat> overall = matchDetailRepository.aggregateTopPlayers(
                "A", MatchType.CUSTOM, null, null, null, 10);
        assertThat(overall).hasSize(1);
        assertThat(overall.get(0).goals()).isEqualTo(7); // B전 2골 + C전 5골 합산
    }
}
