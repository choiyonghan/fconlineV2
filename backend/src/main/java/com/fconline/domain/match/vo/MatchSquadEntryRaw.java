package com.fconline.domain.match.vo;

/**
 * 매치 상세 모달의 MOM/Worst Player 선정용 스쿼드 1명 원시값. squad_entries 컬럼을 그대로
 * 옮긴 값이라 선수 이름 매핑(spId -> 이름)은 응용 계층(RecordFacade)에서 한다.
 *
 * Nexon API 응답에는 "이 경기의 MOM은 누구다"를 알려주는 필드가 없다(참고:
 * {@link com.fconline.infrastructure.nexon.NexonApiClient}, {@link com.fconline.domain.match.SquadEntry}
 * 어디에도 그런 컬럼/필드가 없음 — 확인됨). 그래서 MOM/Worst는 항상 이 rating 값을 비교해서
 * 가장 높은/낮은 선수를 뽑는 방식으로만 계산한다(RecordFacade가 아니라 프론트 report.js가
 * 계산 — 응답은 원시 rating만 내려주면 됨).
 */
public record MatchSquadEntryRaw(
        String spId,
        int spPosition,
        int goal,
        int assist,
        int save,
        int tackle,
        int intercept,
        int block,
        boolean substitute,
        Double rating
) {
}
