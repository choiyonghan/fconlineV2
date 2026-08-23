package com.fconline.app.record.dto;

/** matchId는 프론트가 매치 단위 집계(경기당 xG값 추이 등)를 만들 때 매치 목록과 조인하는 키다. */
public record ShotPointResponse(double x, double y, String shootType, String result, boolean goal, String matchId) {
}
