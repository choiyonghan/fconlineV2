package com.fconline.domain.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * match_details.player_squad(JSON 배열)를 정규화한 자식 엔티티.
 * TOP3 선수 집계가 QueryDSL "GROUP BY sp_id"로 DB에서 끝나도록 한다 — v1은 이 집계 루프가
 * app.js에 2벌, official.js에 1벌, 총 3벌 중복되어 있었다(analysis 6.7).
 *
 * substitute: v1은 spPosition === 28을 매직넘버로 직접 비교해 교체 선수를 제외했다(analysis 6.8).
 * v2는 적재 시점에 이 의미를 명시적 boolean으로 변환해 매직넘버가 조회 코드에 다시 나타나지 않게 한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "squad_entries")
public class SquadEntry {

    private static final int SUBSTITUTE_POSITION_CODE = 28;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_detail_id", nullable = false)
    private MatchDetail matchDetail;

    @Column(name = "sp_id", nullable = false, length = 32)
    private String spId;

    @Column(name = "sp_position", nullable = false)
    private int spPosition;

    @Column(name = "goal", nullable = false)
    private int goal;

    @Column(name = "assist", nullable = false)
    private int assist;

    @Column(name = "save_count", nullable = false)
    private int save;

    @Column(name = "tackle", nullable = false)
    private int tackle;

    @Column(name = "intercept", nullable = false)
    private int intercept;

    @Column(name = "block", nullable = false)
    private int block;

    @Column(name = "substitute", nullable = false)
    private boolean substitute;

    public static SquadEntry of(MatchDetail matchDetail, String spId, int spPosition,
                                 int goal, int assist, int save, int tackle, int intercept, int block) {
        SquadEntry entry = new SquadEntry();
        entry.matchDetail = matchDetail;
        entry.spId = spId;
        entry.spPosition = spPosition;
        entry.goal = goal;
        entry.assist = assist;
        entry.save = save;
        entry.tackle = tackle;
        entry.intercept = intercept;
        entry.block = block;
        entry.substitute = spPosition == SUBSTITUTE_POSITION_CODE;
        return entry;
    }

    /** 골+어시스트+수비 기여(태클/인터셉트/블록/세이브)를 단순 가중합으로 환산한 기여도 점수. */
    public double contributionScore() {
        return (goal * 4.0) + (assist * 3.0) + (tackle + intercept + block + save) * 0.5;
    }
}
