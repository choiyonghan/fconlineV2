package com.fconline.domain.shared;

import java.time.ZoneId;

/**
 * "오늘"/날짜 경계 계산에 쓰는 유일한 타임존 상수.
 * v1은 이 기준이 app.js/official.js/official.html 3곳에 서로 다르게 흩어져 있었다
 * (analysis 6.5) — v2는 이 상수 하나로 고정해 같은 문제가 재발하지 않게 한다.
 */
public final class KstZone {

    public static final ZoneId ID = ZoneId.of("Asia/Seoul");

    private KstZone() {
    }
}
