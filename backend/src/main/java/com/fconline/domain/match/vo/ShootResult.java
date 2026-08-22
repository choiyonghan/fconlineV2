package com.fconline.domain.match.vo;

/**
 * 슛 시도의 결과. GOAL만 득점 유형/시간대 분포 집계에 실제로 쓰인다.
 * Nexon 공식 문서로 shootDetail[].result 코드가 3종(1 ontarget, 2 offtarget, 3 goal)뿐임을
 * 확정했다 — 코드가 주지 않는 SAVED/BLOCKED/POST는 제거했다(어디에서도 참조되지 않았음).
 */
public enum ShootResult {
    ON_TARGET,
    OFF_TARGET,
    GOAL,
    UNKNOWN
}
