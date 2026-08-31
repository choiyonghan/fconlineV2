package com.fconline.domain.match.vo;

import java.util.Map;

/**
 * 거리·각도 로지스틱 회귀 근사 xG — {@code site-root/report.js}의 {@code calcXg}와 반드시 같은
 * 공식이어야 한다(한쪽을 고치면 다른 쪽도 맞춰야 한다, 언어가 달라 자동 동기화 안 됨).
 *
 * 2026-08-31에 실제로 어긋난 걸 발견해서 고쳤다 — 거리·각도 로지스틱 회귀 자체는 두 구현이
 * 처음부터 같았지만, report.js에만 있던 {@code XG_SHOT_TYPE_MULTIPLIER}(슛 유형별 난이도 배율,
 * 헤더 0.70배 등)를 이쪽은 포팅하지 않아 "전체 선수 스탯"의 xG/결정력 열과 대시보드 요약(AI
 * 랭킹 프롬프트에도 들어감)이 헤더/바이시클킥 골이 많은 선수의 xG를 과대평가하고 있었다.
 *
 * 이전 버전(ApproxXgTable)은 전 유저 슈팅 표본을 모아 "이 구역에서 실제로 골이 난 비율"을
 * 쓰는 경험적 방식이었다. 지금은 골대까지 거리·시야각·슛 유형만으로 계산하는 순수 함수라
 * 표본을 모을 필요가 없다 — DashboardSnapshotBuilder도 더는 9명 슛 좌표를 미리 풀링하지 않는다.
 *
 * 정식 xG 모델은 아니다(수비수 배치·압박 등은 반영하지 않는 거리·각도·슛유형만의 근사치).
 */
public final class ExpectedGoalsCalculator {

    private static final double PITCH_LENGTH_M = 105.0;
    private static final double PITCH_WIDTH_M = 68.0;
    private static final double GOAL_WIDTH_M = 7.32;
    private static final double GOAL_Y_MIN_M = (PITCH_WIDTH_M - GOAL_WIDTH_M) / 2;
    private static final double GOAL_Y_MAX_M = (PITCH_WIDTH_M + GOAL_WIDTH_M) / 2;
    private static final double GOAL_CENTER_Y_M = PITCH_WIDTH_M / 2;

    /** report.js의 XG_SHOT_TYPE_MULTIPLIER와 반드시 같아야 한다 — 키는 ShootType.label()과 동일한
     * 한글 라벨. 여기 없는 유형(일반/ZD/DD/파워샷 등)은 기준(1.0)으로 취급. */
    private static final Map<String, Double> SHOT_TYPE_MULTIPLIER = Map.of(
            "헤더", 0.70,
            "발리", 0.82,
            "바이시클킥", 0.55,
            "플레어샷", 0.85,
            "무회전", 0.85,
            "프리킥", 0.80,
            "PK", 1.15);

    private ExpectedGoalsCalculator() {
    }

    /**
     * 정규화 좌표(x,y ∈ [0,1], x=1이 상대 골대 방향) + 슛 유형 1건의 xG.
     * report.js의 calcXg와 동일하게 [0,1]로 캡하고 소숫점 둘째 자리로 반올림한다 — 여러 건을
     * 합산할 때(xgBySpId, sumXg) 양쪽 언어의 합계가 어긋나지 않으려면 반올림 시점도 맞아야 한다.
     */
    public static double calcXg(double x, double y, String shootType) {
        double xm = x * PITCH_LENGTH_M;
        double ym = y * PITCH_WIDTH_M;
        double dist = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_CENTER_Y_M - ym, 2));
        double d1 = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_Y_MIN_M - ym, 2));
        double d2 = Math.sqrt(Math.pow(PITCH_LENGTH_M - xm, 2) + Math.pow(GOAL_Y_MAX_M - ym, 2));
        double cosAngle = (d1 * d1 + d2 * d2 - GOAL_WIDTH_M * GOAL_WIDTH_M) / (2 * d1 * d2);
        cosAngle = Math.max(-1, Math.min(1, cosAngle)); // 부동소수 오차로 [-1,1] 살짝 벗어나는 것 방지
        double angleDeg = Math.toDegrees(Math.acos(cosAngle));
        double logit = 0.5 - 0.15 * dist + 0.05 * angleDeg;
        double base = 1 / (1 + Math.exp(-logit));
        double mult = SHOT_TYPE_MULTIPLIER.getOrDefault(shootType, 1.0);
        return Math.round(Math.min(1, base * mult) * 100) / 100.0;
    }
}
