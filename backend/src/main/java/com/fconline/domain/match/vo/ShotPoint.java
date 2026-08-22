package com.fconline.domain.match.vo;

/** 좌표 히트맵용 슛 1건. x/y는 Nexon 원본 그대로(0~1 정규화 좌표, 경기장 전체 기준). */
public record ShotPoint(Double x, Double y, ShootType shootType, ShootResult result) {
}
