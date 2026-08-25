package com.fconline.app.dashboard.support;

import com.fconline.app.record.dto.ShotPointResponse;
import java.util.List;

/**
 * 거리·각도 로지스틱 회귀 근사 xG — {@code site-root/report.js}의 {@code calcXg}와 반드시 같은
 * 공식이어야 한다(한쪽을 고치면 다른 쪽도 맞춰야 한다, 언어가 달라 자동 동기화 안 됨).
 *
 * 이전 버전(ApproxXgTable)은 전 유저 슈팅 표본을 모아 "이 구역에서 실제로 골이 난 비율"을
 * 쓰는 경험적 방식이었다. 지금은 골대까지 거리·시야각만으로 계산하는 순수 함수라 표본을 모을
 * 필요가 없다 — DashboardSnapshotBuilder도 더는 9명 슛 좌표를 미리 풀링하지 않는다.
 *
 * 정식 xG 모델은 아니다(수비수 배치·압박 등은 반영하지 않는 거리·각도만의 근사치).
 */
public final class ExpectedGoalsCalculator {

    private static final double PITCH_LENGTH_M = 105.0;
    private static final double PITCH_WIDTH_M = 68.0;
    private static final double GOAL_WIDTH_M = 7.32;
    private static final double GOAL_Y_MIN_M = (PITCH_WIDTH_M - GOAL_WIDTH_M) / 2;
    private static final double GOAL_Y_MAX_M = (PITCH_WIDTH_M + GOAL_WIDTH_M) / 2;
    private static final double GOAL_CENTER_Y_M = PITCH_WIDTH_M / 2;

    private ExpectedGoalsCalculator() {
    }

    /** 정규화 좌표(x,y ∈ [0,1], x=1이 상대 골대 방향) 1건의 xG. */
    public static double calcXg(double x, double y) {
        double xm = x * PITCH_LENGTH_M;
        double ym = y * PITCH_WIDTH_M;
        double dist = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_CENTER_Y_M - ym, 2));
        double d1 = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_Y_MIN_M - ym, 2));
        double d2 = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_Y_MAX_M - ym, 2));
        double cosAngle = (d1 * d1 + d2 * d2 - GOAL_WIDTH_M * GOAL_WIDTH_M) / (2 * d1 * d2);
        cosAngle = Math.max(-1, Math.min(1, cosAngle)); // 부동소수 오차로 [-1,1] 살짝 벗어나는 것 방지
        double angleDeg = Math.toDegrees(Math.acos(cosAngle));
        double logit = 0.5 - 0.15 * dist + 0.05 * angleDeg;
        return 1 / (1 + Math.exp(-logit));
    }

    /** 슛 포인트 목록의 합산 기대 득점(xG). */
    public static double expectedGoals(List<ShotPointResponse> points) {
        double sum = 0;
        for (ShotPointResponse p : points) {
            sum += calcXg(p.x(), p.y());
        }
        return sum;
    }
}
