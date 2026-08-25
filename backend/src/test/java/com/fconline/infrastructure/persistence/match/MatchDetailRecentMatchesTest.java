package com.fconline.infrastructure.persistence.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.fconline.domain.match.Match;
import com.fconline.domain.match.MatchDetail;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchStats;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.RecentMatchRaw;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * findRecentByOuid()/findByOuidAndOpponent()가 MatchDetail 전체 엔티티(특히 shoot_detail/
 * player_squad/raw_participant jsonb 원본, 목록 화면엔 필요 없는 무거운 컬럼) 대신 경량
 * 프로젝션(RecentMatchRaw)만 선택하도록 바꾼 뒤에도 값과 정렬(매치 날짜 내림차순)이 그대로
 * 맞는지 확인하는 통합 테스트. H2(MODE=PostgreSQL)로 충분히 검증 가능한 표준 조인/정렬이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MatchDetailRecentMatchesTest {

    @Autowired
    private MatchDetailRepository matchDetailRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 최근_경기_목록은_무거운_원본_컬럼_없이도_날짜_내림차순으로_정확한_값을_돌려준다() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-02T00:00:00Z");

        Match match1 = Match.of("match-1", older, MatchType.CUSTOM);
        Match match2 = Match.of("match-2", newer, MatchType.CUSTOM);
        entityManager.persist(match1);
        entityManager.persist(match2);

        MatchStats stats1 = MatchStats.builder().goalsFor(2).goalsAgainst(1).averageRating(7.5).possession(55).matchEndType(0).build();
        MatchStats stats2 = MatchStats.builder().goalsFor(3).goalsAgainst(0).averageRating(8.2).possession(60).matchEndType(0).build();

        MatchDetail detail1 = MatchDetail.of(match1, "A", "B", "상대1", MatchResult.WIN, stats1,
                "{\"raw\":\"shoot-detail-blob\"}", "{\"raw\":\"player-squad-blob\"}", "{\"raw\":\"participant-blob\"}");
        MatchDetail detail2 = MatchDetail.of(match2, "A", "B", "상대1", MatchResult.WIN, stats2,
                null, null, null);

        matchDetailRepository.save(detail1);
        matchDetailRepository.save(detail2);
        entityManager.flush();
        entityManager.clear();

        Page<RecentMatchRaw> recent = matchDetailRepository.findRecentByOuid(
                "A", MatchType.CUSTOM, null, null, PageRequest.of(0, 10));

        assertThat(recent.getTotalElements()).isEqualTo(2);
        assertThat(recent.getContent()).hasSize(2);

        RecentMatchRaw first = recent.getContent().get(0);
        assertThat(first.matchId()).isEqualTo("match-2");
        assertThat(first.matchDate()).isEqualTo(newer);
        assertThat(first.opponentNickname()).isEqualTo("상대1");
        assertThat(first.result()).isEqualTo(MatchResult.WIN);
        assertThat(first.goalsFor()).isEqualTo(3);
        assertThat(first.goalsAgainst()).isEqualTo(0);
        // averageRating은 DB 원본(5점 만점)의 2배로 나온다 — MatchDetailRepositoryImpl.doubled() 참고
        // (Nexon 원본이 5점 만점이라 개인 평점(10점 만점)과 스케일을 맞추려는 것).
        assertThat(first.averageRating()).isEqualTo(16.4);
        assertThat(first.possession()).isEqualTo(60);

        RecentMatchRaw second = recent.getContent().get(1);
        assertThat(second.matchId()).isEqualTo("match-1");
        assertThat(second.goalsFor()).isEqualTo(2);

        Page<RecentMatchRaw> vsOpponent = matchDetailRepository.findByOuidAndOpponent(
                "A", "B", MatchType.CUSTOM, null, null, PageRequest.of(0, 10));
        assertThat(vsOpponent.getTotalElements()).isEqualTo(2);
        assertThat(vsOpponent.getContent().get(0).matchId()).isEqualTo("match-2");
    }
}
