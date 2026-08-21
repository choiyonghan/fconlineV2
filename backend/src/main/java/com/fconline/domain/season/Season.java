package com.fconline.domain.season;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시즌 애그리게잇. v1은 시즌 기준일이 app.js/official.js/official.html 3곳에 서로 다르게
 * 하드코딩되어 있었다(analysis 6.4) — v2는 이 테이블이 유일한 출처(single source of truth)다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "seasons")
public class Season {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** null이면 진행 중인 시즌 */
    @Column(name = "end_date")
    private LocalDate endDate;

    public static Season open(String name, LocalDate startDate) {
        Season season = new Season();
        season.name = name;
        season.startDate = startDate;
        return season;
    }

    public void close(LocalDate endDate) {
        this.endDate = endDate;
    }

    public boolean isCurrent(LocalDate reference) {
        boolean startedAlready = !reference.isBefore(startDate);
        boolean notEndedYet = (endDate == null) || !reference.isAfter(endDate);
        return startedAlready && notEndedYet;
    }

    /**
     * 시즌 경계를 Instant 범위로 변환한다. 모든 날짜 경계 계산을 KST(Asia/Seoul) 기준
     * 하나로 고정해 v1의 타임존 처리 불일치(analysis 6.5 — getKstDateString/parseToKst/
     * formatDateToKstString이 서로 다른 기준을 썼던 문제)가 재발하지 않게 한다.
     */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public Instant startInstant() {
        return startDate.atStartOfDay(KST).toInstant();
    }

    /** 진행 중인 시즌(endDate == null)이면 상한 없음을 의미하는 null을 반환한다. */
    public Instant endInstantExclusiveOrNull() {
        return endDate == null ? null : endDate.plusDays(1).atStartOfDay(KST).toInstant();
    }
}
