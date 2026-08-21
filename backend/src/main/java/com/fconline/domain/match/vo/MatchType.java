package com.fconline.domain.match.vo;

import java.util.Arrays;

/**
 * Nexon 매치 타입. DB에는 정수(40/50)로 저장되지만 REST 계약과 도메인 코드에서는
 * 이 enum으로만 다뤄 매직넘버가 컨트롤러/서비스 어디에도 노출되지 않게 한다.
 */
public enum MatchType {

    CUSTOM(40),
    OFFICIAL(50);

    private final int code;

    MatchType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MatchType fromCode(int code) {
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 matchType 코드: " + code));
    }
}
