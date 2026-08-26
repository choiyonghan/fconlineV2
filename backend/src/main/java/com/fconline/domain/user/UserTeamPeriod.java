package com.fconline.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * "이 시점에 이 유저가 쓰던 팀"(예: 서울쥐(첼시)) — Nexon 응답엔 없는 정보라 seasons와
 * 같은 패턴(기간 + endDate null=진행중)으로 직접 관리한다. 데이터는 사용자가 조사해서
 * 넘겨주는 대로 마이그레이션(INSERT)으로 채운다 — 이 엔티티/리포지토리는 읽기 전용으로만 쓴다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_team_periods")
public class UserTeamPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ouid", nullable = false, length = 64)
    private String ouid;

    @Column(name = "team_name", nullable = false)
    private String teamName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** null이면 현재까지 계속 쓰는 중. */
    @Column(name = "end_date")
    private LocalDate endDate;

    public boolean covers(LocalDate reference) {
        boolean startedAlready = !reference.isBefore(startDate);
        boolean notEndedYet = (endDate == null) || !reference.isAfter(endDate);
        return startedAlready && notEndedYet;
    }
}
