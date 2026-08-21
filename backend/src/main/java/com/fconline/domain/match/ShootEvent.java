package com.fconline.domain.match;

import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.match.vo.ShootType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * match_details.shoot_detail(JSON 배열)을 정규화한 자식 엔티티.
 * "득점 유형별 분포", "시간대별 득점 분포" 집계가 QueryDSL GROUP BY로 DB에서 끝나도록 한다
 * (v1은 클라이언트가 JSON을 순회하며 집계했다 — analysis 6.10/7.3).
 *
 * goalTimeMinutes는 적재 시점(NexonMatchGateway 구현체)에 1회만 파싱한다.
 * v1은 app.js/official.js에 서로 다른 goalTime 파서가 2벌 있었다(analysis 6.7).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "shoot_events")
public class ShootEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_detail_id", nullable = false)
    private MatchDetail matchDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "shoot_type", nullable = false, length = 32)
    private ShootType shootType;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private ShootResult result;

    /** 경기 시작 기준 누적 분(分). 시간대 버킷(0-15, 16-30 ...) 집계에 사용. */
    @Column(name = "goal_time_minutes")
    private Integer goalTimeMinutes;

    @Column(name = "period")
    private Integer period;

    public static ShootEvent of(MatchDetail matchDetail, ShootType shootType, ShootResult result,
                                 Integer goalTimeMinutes, Integer period) {
        ShootEvent event = new ShootEvent();
        event.matchDetail = matchDetail;
        event.shootType = shootType;
        event.result = result;
        event.goalTimeMinutes = goalTimeMinutes;
        event.period = period;
        return event;
    }
}
