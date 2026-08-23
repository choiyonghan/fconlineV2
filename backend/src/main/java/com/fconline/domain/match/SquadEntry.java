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

    /** V11에서 추가 — player[].status의 나머지 지표(슛정확/패스/드리블/공중볼/평점 계산용). */
    @Column(name = "shoot_total", nullable = false)
    private int shootTotal;

    @Column(name = "effective_shoot", nullable = false)
    private int effectiveShoot;

    @Column(name = "pass_try", nullable = false)
    private int passTry;

    @Column(name = "pass_success", nullable = false)
    private int passSuccess;

    @Column(name = "dribble_try", nullable = false)
    private int dribbleTry;

    @Column(name = "dribble_success", nullable = false)
    private int dribbleSuccess;

    @Column(name = "aerial_try", nullable = false)
    private int aerialTry;

    @Column(name = "aerial_success", nullable = false)
    private int aerialSuccess;

    /** 매치 1건의 평점. 결측 가능(nullable) — 전체 선수 스탯의 "평점"은 이 값들의 평균이다. */
    @Column(name = "rating")
    private Double rating;

    public static SquadEntry of(MatchDetail matchDetail, String spId, int spPosition,
                                 int goal, int assist, int save, int tackle, int intercept, int block,
                                 int shootTotal, int effectiveShoot, int passTry, int passSuccess,
                                 int dribbleTry, int dribbleSuccess, int aerialTry, int aerialSuccess,
                                 Double rating) {
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
        entry.shootTotal = shootTotal;
        entry.effectiveShoot = effectiveShoot;
        entry.passTry = passTry;
        entry.passSuccess = passSuccess;
        entry.dribbleTry = dribbleTry;
        entry.dribbleSuccess = dribbleSuccess;
        entry.aerialTry = aerialTry;
        entry.aerialSuccess = aerialSuccess;
        entry.rating = rating;
        return entry;
    }

    /**
     * 골+어시스트+수비 기여(태클/인터셉트/블록/세이브)를 단순 가중합으로 환산한 기여도 점수(원점수,
     * 그룹 내 최댓값 100점 기준 재조정은 응용 계층(RecordFacade/프론트)에서 한다).
     */
    public double contributionScore() {
        return (goal * 3.0) + (assist * 2.0) + (tackle + intercept + block + save) * 0.5;
    }
}
