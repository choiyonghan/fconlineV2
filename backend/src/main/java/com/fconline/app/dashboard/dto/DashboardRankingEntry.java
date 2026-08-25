package com.fconline.app.dashboard.dto;

/** AI(Gemini)에게 "9명 종합 실력 순위를 매겨달라"고 물어본 결과 1행. */
public record DashboardRankingEntry(String ouid, String nickname, int rank, String reason) {
}
