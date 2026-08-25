package com.fconline.app.dashboard.support;

import com.fconline.app.record.dto.ShotPointResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code site-root/report.js}의 "근사 xG 구역 집계"(zoneKey/coarseZoneKey/FINE_ZONE_MIN_SAMPLE)를
 * 그대로 Java로 옮긴 것이다. 라이브 페이지는 브라우저에서 이 표를 매번 새로 만들지만, 대시보드
 * 스냅샷 배치는 Java 프로세스(GitHub Actions 배치)로 돌아서 그 JS 코드를 재사용할 수 없어
 * 별도로 포팅했다.
 *
 * <p><b>주의</b>: 이 알고리즘을 고치면 {@code report.js}의 {@code zoneKey}/{@code coarseZoneKey}/
 * {@code FINE_ZONE_MIN_SAMPLE}도 반드시 같이 고쳐야 한다 — 언어가 달라 자동으로 동기화되지
 * 않는다(의도된 트레이드오프). 정식 xG 모델이 아니라 "우리 데이터 기반" 근사치이며, 표본이
 * 적은 세밀 구역(8건 미만)은 넓은 구역(coarse)의 전환율로 대체한다.
 */
public final class ApproxXgTable {

    private static final int FINE_ZONE_MIN_SAMPLE = 8;

    private static final double BOX_X_MIN = 343.0 / 400;
    private static final double BOX_Y_MIN = 75.0 / 260;
    private static final double BOX_Y_MAX = 185.0 / 260;
    private static final double SIX_X_MIN = 376.0 / 400;

    private record DistanceBand(double min, String label) {
    }

    private static final List<DistanceBand> FINE_DISTANCE_BANDS = List.of(
            new DistanceBand(0.96, "골키퍼 코앞"),
            new DistanceBand(0.92, "6야드 부근"),
            new DistanceBand(0.875, "페널티스팟 부근"),
            new DistanceBand(0.80, "박스 안(먼 쪽)"),
            new DistanceBand(0.70, "박스 바로 앞"),
            new DistanceBand(0.60, "중거리(가까운 쪽)"),
            new DistanceBand(0.45, "중거리(먼 쪽)"),
            new DistanceBand(-1, "장거리")
    );

    /** zone -> [shots, goals]. */
    private final Map<String, int[]> fineCounts = new HashMap<>();
    private final Map<String, int[]> coarseCounts = new HashMap<>();
    private final Map<String, String> fineToCoarse = new HashMap<>();
    private Map<String, Double> rateMap;

    public void add(double x, double y, boolean goal) {
        String fineKey = zoneKey(x, y);
        String coarseKey = coarseZoneKey(x, y);
        bump(fineCounts, fineKey, goal);
        bump(coarseCounts, coarseKey, goal);
        fineToCoarse.putIfAbsent(fineKey, coarseKey);
    }

    public void addAll(List<ShotPointResponse> points) {
        for (ShotPointResponse p : points) {
            add(p.x(), p.y(), p.goal());
        }
    }

    /** 표본을 전부 add()한 뒤 한 번 호출해 zone별 전환율을 확정한다. */
    public void build() {
        rateMap = new HashMap<>();
        for (Map.Entry<String, int[]> e : fineCounts.entrySet()) {
            String zone = e.getKey();
            int shots = e.getValue()[0];
            int goals = e.getValue()[1];
            double rate;
            if (shots >= FINE_ZONE_MIN_SAMPLE) {
                rate = (double) goals / shots;
            } else {
                int[] coarse = coarseCounts.get(fineToCoarse.get(zone));
                rate = (coarse != null && coarse[0] > 0) ? (double) coarse[1] / coarse[0]
                        : (shots > 0 ? (double) goals / shots : 0);
            }
            rateMap.put(zone, rate);
        }
    }

    /** 슛 포인트 목록의 합산 기대 득점(xG). build() 이후에만 정확하다. */
    public double expectedGoals(List<ShotPointResponse> points) {
        double sum = 0;
        for (ShotPointResponse p : points) {
            Double rate = rateMap == null ? null : rateMap.get(zoneKey(p.x(), p.y()));
            if (rate != null) sum += rate;
        }
        return sum;
    }

    private static void bump(Map<String, int[]> counts, String key, boolean goal) {
        int[] entry = counts.computeIfAbsent(key, k -> new int[2]);
        entry[0]++;
        if (goal) entry[1]++;
    }

    private static String zoneKey(double x, double y) {
        String distanceLabel = FINE_DISTANCE_BANDS.get(FINE_DISTANCE_BANDS.size() - 1).label();
        for (DistanceBand band : FINE_DISTANCE_BANDS) {
            if (x >= band.min()) {
                distanceLabel = band.label();
                break;
            }
        }
        double boxCenter = (BOX_Y_MIN + BOX_Y_MAX) / 2;
        double boxHalfWidth = (BOX_Y_MAX - BOX_Y_MIN) / 2;
        double offCenter = Math.abs(y - boxCenter) / boxHalfWidth;
        String lateralLabel = offCenter <= 0.5 ? "중앙" : (offCenter <= 1.3 ? "중앙 인접" : "측면");
        return distanceLabel + " · " + lateralLabel;
    }

    private static String coarseZoneKey(double x, double y) {
        boolean isCenter = y >= BOX_Y_MIN && y <= BOX_Y_MAX;
        String band;
        if (x >= SIX_X_MIN) band = "초근접(6야드 부근)";
        else if (x >= BOX_X_MIN) band = "박스 안";
        else if (x >= 0.70) band = "박스 근처";
        else if (x >= 0.55) band = "중거리";
        else band = "장거리";
        return band + " · " + (isCenter ? "중앙" : "측면");
    }
}
