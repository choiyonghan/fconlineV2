package com.fconline.domain.match.vo;

import java.time.Instant;

/** 특정 상대와의 경기 결과 한 건 + 그 경기 시각 — 역대 최고 기록(기간 포함) 계산용 경량 프로젝션. */
public record ChronologicalResult(MatchResult result, Instant matchDate) {
}
