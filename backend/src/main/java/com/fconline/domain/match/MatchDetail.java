package com.fconline.domain.match;

import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchStats;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 참가자 1명 관점의 경기 상세 — 실제로 무거운(rich) 애그리게잇 루트.
 * 모든 조회 화면(종합 전적/상대별 카드/상대 상세)은 Match가 아니라 이 엔티티를
 * ouid/matchType(Match 조인)/기간(Season 범위) 기준으로 직접 필터링한다.
 *
 * Match와는 단방향 @ManyToOne만 유지한다 — Match → MatchDetail 역방향 컬렉션을 두지 않아
 * 대량 집계 쿼리에서 불필요한 그래프 로딩이 생기지 않게 한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "match_details",
        uniqueConstraints = @UniqueConstraint(columnNames = {"match_id", "ouid"})
)
public class MatchDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private Match match;

    @Column(name = "ouid", nullable = false, length = 64)
    private String ouid;

    @Column(name = "opponent_ouid", nullable = false, length = 64)
    private String opponentOuid;

    @Column(name = "opponent_nickname", nullable = false)
    private String opponentNickname;

    @Column(name = "match_result", nullable = false)
    private MatchResult result;

    @Embedded
    private MatchStats stats;

    /**
     * Nexon 응답의 shootDetail[]/player[] 원본을 그대로 보존한다 (v1의 shoot_detail/player_squad
     * jsonb 컬럼과 같은 역할). shoot_events/squad_entries(정규화 테이블)는 집계 쿼리 성능을 위해
     * 그대로 유지하고, 이 두 컬럼은 아직 매핑 안 한 필드·향후 응답에 추가되는 필드를 재동기화
     * 없이 나중에 꺼내 쓰기 위한 원본 백업이다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shoot_detail")
    private String shootDetailRaw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "player_squad")
    private String playerSquadRaw;

    @OneToMany(mappedBy = "matchDetail", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ShootEvent> shootEvents = new ArrayList<>();

    @OneToMany(mappedBy = "matchDetail", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SquadEntry> squadEntries = new ArrayList<>();

    public static MatchDetail of(Match match, String ouid, String opponentOuid, String opponentNickname,
                                  MatchResult result, MatchStats stats, String shootDetailRaw, String playerSquadRaw) {
        MatchDetail detail = new MatchDetail();
        detail.match = match;
        detail.ouid = ouid;
        detail.opponentOuid = opponentOuid;
        detail.opponentNickname = opponentNickname;
        detail.result = result;
        detail.stats = stats;
        detail.shootDetailRaw = shootDetailRaw;
        detail.playerSquadRaw = playerSquadRaw;
        return detail;
    }

    public void addShootEvent(ShootEvent shootEvent) {
        this.shootEvents.add(shootEvent);
    }

    public void addSquadEntry(SquadEntry squadEntry) {
        this.squadEntries.add(squadEntry);
    }
}
