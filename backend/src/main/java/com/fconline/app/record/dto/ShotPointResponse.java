package com.fconline.app.record.dto;

/** x/y는 Nexon 원본 그대로(0~1 정규화 좌표, 경기장 전체 기준) — 프론트가 캔버스/SVG 크기에 맞춰 스케일링한다. */
public record ShotPointResponse(double x, double y, String shootType, String result, boolean goal) {
}
