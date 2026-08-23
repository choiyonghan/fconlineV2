package com.fconline.app.insight.dto;

import java.util.Map;

/**
 * 인사이트 스냅샷 한 건의 내용.
 * summaryText는 질문마다 항상 쓰는 공통 데이터(종합 전적, 선수단 전체 기여도, 어시스트 체인,
 * 최근 경기, 상대별 전적 목록) 요약이고, opponentDetailByNickname은 상대 닉네임 → 그 상대와의
 * 경기별 상세 기록 텍스트로, 질문에 그 닉네임이 등장할 때만 summaryText 뒤에 덧붙인다.
 */
public record InsightSnapshotContent(String summaryText, Map<String, String> opponentDetailByNickname) {
}
