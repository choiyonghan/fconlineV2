package com.fconline.domain.match.vo;

/**
 * 슛 시도의 결과. GOAL만 득점 유형/시간대 분포 집계에 실제로 쓰인다.
 * TODO(구현 착수 시 검증 필요): Nexon shootDetail[].result 실제 값 목록으로 보정할 것.
 */
public enum ShootResult {
    GOAL,
    SAVED,
    BLOCKED,
    OFF_TARGET,
    POST,
    UNKNOWN
}
