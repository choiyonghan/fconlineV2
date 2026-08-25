package com.fconline.app.record.dto;

/**
 * 매치 상세 모달의 MOM/Worst Player 선정용 — 스쿼드 1명의 평점+스탯. rating이 null이면
 * (결측) 프론트가 MOM/Worst 후보에서 제외한다.
 */
public record MatchSquadEntryResponse(String spId, String playerName, int spPosition,
                                       int goal, int assist, int save, int tackle, int intercept, int block,
                                       boolean substitute, Double rating) {
}
