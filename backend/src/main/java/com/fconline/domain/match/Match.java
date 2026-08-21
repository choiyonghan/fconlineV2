package com.fconline.domain.match;

import com.fconline.domain.match.vo.MatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 얇은 애그리게잇 루트. 식별(matchId/matchDate/matchType)만 갖고,
 * 동기화 시 "이미 수집된 경기인지"를 배치(IN절)로 확인하는 용도로만 쓰인다.
 * 실제 통계 조회는 전부 {@link MatchDetail}을 대상으로 한다 — Match는 역방향 컬렉션을 갖지 않는다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "matches")
public class Match {

    @Id
    @Column(name = "match_id", nullable = false, updatable = false, length = 64)
    private String matchId;

    @Column(name = "match_date", nullable = false)
    private Instant matchDate;

    @Column(name = "match_type", nullable = false)
    private MatchType matchType;

    public static Match of(String matchId, Instant matchDate, MatchType matchType) {
        Match match = new Match();
        match.matchId = matchId;
        match.matchDate = matchDate;
        match.matchType = matchType;
        return match;
    }
}
