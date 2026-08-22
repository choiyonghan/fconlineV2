package com.fconline.domain.match.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * match_details의 평면 통계 컬럼 20여 개를 하나의 값객체로 응집한다.
 * v1의 detailPayload 매핑(fetch_and_store.js:353-387)에 대응.
 *
 * offside: v1 프론트가 읽지만 v1 DB에는 컬럼이 없어 항상 0으로 표시되던 필드(analysis 6.3).
 * Nexon 공식 문서로 확인한 실제 응답 필드명은 matchDetail.offsideCount다
 * (게이트웨이 구현체에서 이 이름으로 읽는다 — "offside" 단독 필드는 존재하지 않는다).
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Embeddable
public class MatchStats {

    @Column(name = "controller")
    private String controller;

    @Column(name = "average_rating")
    private Double averageRating;

    @Column(name = "goals_for")
    private Integer goalsFor;

    @Column(name = "goals_against")
    private Integer goalsAgainst;

    @Column(name = "shoot_total")
    private Integer shootTotal;

    @Column(name = "effective_shoot")
    private Integer effectiveShoot;

    @Column(name = "goal_in_penalty")
    private Integer goalInPenalty;

    @Column(name = "goal_out_penalty")
    private Integer goalOutPenalty;

    @Column(name = "shoot_heading")
    private Integer shootHeading;

    @Column(name = "own_goal")
    private Integer ownGoal;

    @Column(name = "possession")
    private Integer possession;

    @Column(name = "pass_try")
    private Integer passTry;

    @Column(name = "pass_success")
    private Integer passSuccess;

    @Column(name = "short_pass_try")
    private Integer shortPassTry;

    @Column(name = "through_pass_try")
    private Integer throughPassTry;

    @Column(name = "through_pass_success")
    private Integer throughPassSuccess;

    @Column(name = "tackle_try")
    private Integer tackleTry;

    @Column(name = "tackle_success")
    private Integer tackleSuccess;

    @Column(name = "foul")
    private Integer foul;

    @Column(name = "yellow_cards")
    private Integer yellowCards;

    @Column(name = "red_cards")
    private Integer redCards;

    /** Nexon 응답의 matchDetail.offsideCount에서 옮겨온다 (클래스 주석 참고). */
    @Column(name = "offside")
    private Integer offside;
}
