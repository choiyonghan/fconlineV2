package com.fconline.app.dashboard.dto;

/** "환상의 콤비" 1위 — 양방향(A→B + B→A) 합산 골 수가 가장 큰 어시스트 조합. */
public record DashboardCombo(String playerAName, String playerBName, long goals) {
}
