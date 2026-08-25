package com.fconline.app.dashboard.dto;

/**
 * AI(Gemini)에게 "9명 종합 실력 순위를 매겨달라"고 물어본 결과 1행.
 * displayName = "닉네임(실명)"(TrackedUserAliasResolver로 실명이 설정된 경우) 또는 닉네임 그대로 —
 * 프론트는 이 필드로 표시한다. nickname은 매칭/폴백용 원본 값.
 */
public record DashboardRankingEntry(String ouid, String nickname, String displayName, int rank, String reason) {
}
