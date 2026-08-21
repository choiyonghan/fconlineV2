package com.fconline.domain.match.vo;

/**
 * 슛 유형의 정규화된 표시 라벨.
 *
 * v1은 이 라벨을 app.js(switch 문)와 official.js(객체 맵)에 각각 다르게 정의해
 * 같은 데이터로 "로빙슛"/"로빙 슛", "파워샷"/"파워 샷(Super)" 처럼 다른 문자열을 노출했다(analysis 6.7).
 * v2는 이 enum이 유일한 라벨 출처이며, Nexon 응답의 원본 type 코드 → 이 enum 매핑은
 * NexonMatchGateway 구현체(infrastructure)에서 1회만 수행한다.
 *
 * TODO(구현 착수 시 검증 필요): Nexon match-detail shootDetail[].type의 실제 값 전체 목록을
 * 확인해 아래 매핑을 보정할 것 (v1 코드에 등장한 값만으로 우선 채워둠).
 */
public enum ShootType {
    NORMAL("일반 슛"),
    LOBBING("로빙 슛"),
    POWER("파워 샷"),
    HEADING("헤딩 슛"),
    VOLLEY("발리 슛"),
    PENALTY_KICK("페널티킥"),
    FREE_KICK("프리킥"),
    UNKNOWN("기타");

    private final String label;

    ShootType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
