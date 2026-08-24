package com.fconline.domain.match.vo;

/**
 * 슛 유형의 정규화된 표시 라벨.
 *
 * v1은 이 라벨을 app.js(switch 문)와 official.js(객체 맵)에 각각 다르게 정의해
 * 같은 데이터로 "로빙슛"/"로빙 슛", "파워샷"/"파워 샷(Super)" 처럼 다른 문자열을 노출했다(analysis 6.7).
 * v2는 이 enum이 유일한 라벨 출처이며, Nexon 응답의 원본 type 코드 → 이 enum 매핑은
 * NexonMatchGateway 구현체(infrastructure)에서 1회만 수행한다.
 *
 * Nexon 공식 문서(shootDetail[].type)로 코드 1~12 전체 매핑을 확정했다:
 * 1 normal, 2 finesse, 3 header, 4 lob, 5 flare, 6 low, 7 volley,
 * 8 free-kick, 9 penalty, 10 knuckle, 11 bicycle, 12 super.
 *
 * 라벨은 유저 커뮤니티에서 실제로 부르는 조작키/약칭 스타일을 따른다(2026-08 변경,
 * 기존 "피네스 슛"/"낮은 슛"/"페널티킥" 등 사전식 표기 대신).
 */
public enum ShootType {
    NORMAL("일반"),
    FINESSE("ZD"),
    HEADING("헤더"),
    LOBBING("칩샷"),
    FLARE("플레어샷"),
    LOW("DD"),
    VOLLEY("발리"),
    FREE_KICK("프리킥"),
    PENALTY_KICK("PK"),
    KNUCKLE("무회전"),
    BICYCLE_KICK("바이시클킥"),
    POWER("파워샷"),
    UNKNOWN("기타");

    private final String label;

    ShootType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
