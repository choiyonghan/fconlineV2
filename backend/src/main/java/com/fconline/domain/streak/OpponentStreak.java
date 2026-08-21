package com.fconline.domain.streak;

import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * (ouid, opponentOuid, matchType, seasonId) 단위 애그리게잇.
 * v1은 유니크 키가 (ouid, opponent_ouid)뿐이라 커스텀/공식 매치의 스트릭 집계가
 * 같은 행을 서로 다른 범위로 덮어써 값이 오염됐다(analysis 6.2) — matchType/seasonId를
 * 키에 포함시켜 이 문제를 구조적으로 차단한다.
 *
 * 결과 반영은 반드시 {@link #applyResult(MatchResult)}를 통해서만 이뤄지도록 해
 * 스트릭 갱신 규칙이 이 클래스 밖으로 새지 않게 한다(StreakDomainService는 이 메서드만 호출).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "opponent_streaks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ouid", "opponent_ouid", "match_type", "season_id"})
)
public class OpponentStreak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ouid", nullable = false, length = 64)
    private String ouid;

    @Column(name = "opponent_ouid", nullable = false, length = 64)
    private String opponentOuid;

    @Column(name = "match_type", nullable = false)
    private MatchType matchType;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "cur_win", nullable = false)
    private int curWin;

    @Column(name = "cur_lose", nullable = false)
    private int curLose;

    @Column(name = "cur_winless", nullable = false)
    private int curWinless;

    @Column(name = "cur_unbeaten", nullable = false)
    private int curUnbeaten;

    @Column(name = "max_win", nullable = false)
    private int maxWin;

    @Column(name = "max_lose", nullable = false)
    private int maxLose;

    @Column(name = "max_winless", nullable = false)
    private int maxWinless;

    @Column(name = "max_unbeaten", nullable = false)
    private int maxUnbeaten;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static OpponentStreak init(String ouid, String opponentOuid, MatchType matchType, Long seasonId) {
        OpponentStreak streak = new OpponentStreak();
        streak.ouid = ouid;
        streak.opponentOuid = opponentOuid;
        streak.matchType = matchType;
        streak.seasonId = seasonId;
        streak.updatedAt = Instant.now();
        return streak;
    }

    /** 경기 결과 하나를 시간순으로 반영해 현재/역대 연승·연패·무패·무승을 갱신한다. */
    public void applyResult(MatchResult result) {
        switch (result) {
            case WIN -> {
                curWin++;
                curUnbeaten++;
                curLose = 0;
                curWinless = 0;
                maxWin = Math.max(maxWin, curWin);
                maxUnbeaten = Math.max(maxUnbeaten, curUnbeaten);
            }
            case LOSE -> {
                curLose++;
                curWinless++;
                curWin = 0;
                curUnbeaten = 0;
                maxLose = Math.max(maxLose, curLose);
                maxWinless = Math.max(maxWinless, curWinless);
            }
            case DRAW -> {
                curUnbeaten++;
                curWinless++;
                curWin = 0;
                curLose = 0;
                maxUnbeaten = Math.max(maxUnbeaten, curUnbeaten);
                maxWinless = Math.max(maxWinless, curWinless);
            }
        }
        this.updatedAt = Instant.now();
    }

    public void reset() {
        curWin = 0;
        curLose = 0;
        curWinless = 0;
        curUnbeaten = 0;
        maxWin = 0;
        maxLose = 0;
        maxWinless = 0;
        maxUnbeaten = 0;
        this.updatedAt = Instant.now();
    }
}
