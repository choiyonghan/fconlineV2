package com.fconline.domain.match.vo;

/**
 * 경기 결과. DB 컬럼(match_result)은 기존 v1 데이터와의 호환을 위해
 * '승'/'무'/'패' 문자열을 그대로 유지하고, MatchResultConverter가 이 enum과 매핑한다.
 */
public enum MatchResult {

    WIN("승"),
    DRAW("무"),
    LOSE("패");

    private final String label;

    MatchResult(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static MatchResult fromLabel(String label) {
        for (MatchResult result : values()) {
            if (result.label.equals(label)) {
                return result;
            }
        }
        throw new IllegalArgumentException("알 수 없는 match_result 값: " + label);
    }
}
