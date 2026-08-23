package com.fconline.domain.match.vo;

/**
 * 좌표 히트맵용 슛 1건. x/y는 Nexon 원본 그대로(0~1 정규화 좌표, 경기장 전체 기준).
 * matchId는 프론트가 "경기당 xG값 추이" 같은 매치 단위 집계를 만들 때, 이미 따로 받는
 * 매치 목록(날짜/득실점 포함)과 조인하는 키로 쓴다 — 이 record 자체엔 날짜를 담지 않는다.
 */
public record ShotPoint(Double x, Double y, ShootType shootType, ShootResult result, String matchId) {
}
