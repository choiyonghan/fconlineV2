package com.fconline.domain.score;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * "욱식 점수" 가중치 규칙. v1은 특정 닉네임(WOOK_NICKNAMES)에 대해 승=5점/무=3점/패=1점의
 * 비대칭 가중치를 app.js(:679-707)에 하드코딩했다 — v2는 대상 유저별 규칙을 테이블로 데이터화해
 * 코드 수정 없이 재미 요소 규칙을 추가/변경할 수 있게 한다(analysis 6.8, 7.8).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "score_rules")
public class ScoreRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_ouid", nullable = false, unique = true, length = 64)
    private String targetOuid;

    @Column(name = "win_points", nullable = false)
    private int winPoints;

    @Column(name = "draw_points", nullable = false)
    private int drawPoints;

    @Column(name = "lose_points", nullable = false)
    private int losePoints;

    public static ScoreRule of(String targetOuid, int winPoints, int drawPoints, int losePoints) {
        ScoreRule rule = new ScoreRule();
        rule.targetOuid = targetOuid;
        rule.winPoints = winPoints;
        rule.drawPoints = drawPoints;
        rule.losePoints = losePoints;
        return rule;
    }
}
