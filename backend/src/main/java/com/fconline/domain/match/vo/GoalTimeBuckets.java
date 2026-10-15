package com.fconline.domain.match.vo;

import java.util.List;

/**
 * 득점 시각을 15분 단위 버킷으로 나누는 로직의 단일 구현. MatchDomainService(DB 적재분)와
 * SearchFacade(추적 대상이 아닌 임의 닉네임, Nexon API 직접 조회분)가 똑같은 집계 로직을
 * 각자 복제해서 쓰고 있던 걸(v1 app.js/official.js 중복과 같은 패턴) 여기로 합친다.
 *
 * period 1(전반)/2(후반)은 로컬 경과분이 45를 넘으면(추가시간) "전반추가시간"/"후반추가시간"
 * 버킷으로 따로 집계한다 — 예전엔 절대분(= period 오프셋 + 로컬분)만 보고 90을 넘으면 무조건
 * "연장전"으로 묻었는데, 후반 추가시간이 충분히 길면(예: 후반 45+6분 → 절대분 96) 실제로는
 * 정규시간(후반) 골인데도 연장전 득점으로 잘못 집계되는 버그가 있었다.
 */
public final class GoalTimeBuckets {

    private GoalTimeBuckets() {
    }

    private static final String FIRST_HALF_INJURY_LABEL = "전반추가시간";
    private static final String SECOND_HALF_INJURY_LABEL = "후반추가시간";
    private static final String EXTRA_TIME_LABEL = "연장전";

    /** 집계 맵을 0으로 초기화할 때 쓰는 고정 순서 — 차트가 이 순서 그대로 그린다. */
    public static final List<String> ORDERED_LABELS = List.of(
            "1-15", "16-30", "31-45", FIRST_HALF_INJURY_LABEL,
            "46-60", "61-75", "76-90", SECOND_HALF_INJURY_LABEL,
            EXTRA_TIME_LABEL
    );

    /**
     * period(1 전반~5 승부차기)와 그 period 시작 기준 경과분(minute)으로 버킷 라벨을 고른다.
     * period가 null이거나 1~5 밖이면(결측 레거시 데이터) period 구분 없이 절대분 그대로
     * 버킷팅한다(이 경우 전반/후반 추가시간을 구분할 수 없어 "연장전"으로 묻힌다).
     */
    public static String labelFor(int minute, Integer period) {
        if (period != null && period == 1) {
            return withinHalf(minute, 0, FIRST_HALF_INJURY_LABEL);
        }
        if (period != null && period == 2) {
            return withinHalf(minute, 45, SECOND_HALF_INJURY_LABEL);
        }
        if (period != null && period >= 3 && period <= 5) {
            return EXTRA_TIME_LABEL;
        }
        return legacyLabelFor(minute);
    }

    private static String withinHalf(int minute, int offset, String injuryLabel) {
        if (minute <= 15) {
            return (offset + 1) + "-" + (offset + 15);
        }
        if (minute <= 30) {
            return (offset + 16) + "-" + (offset + 30);
        }
        if (minute <= 45) {
            return (offset + 31) + "-" + (offset + 45);
        }
        return injuryLabel;
    }

    private static String legacyLabelFor(int minute) {
        int[] uppers = {15, 30, 45, 60, 75, 90};
        for (int upper : uppers) {
            if (minute <= upper) {
                return (upper - 14) + "-" + upper;
            }
        }
        return EXTRA_TIME_LABEL;
    }
}
