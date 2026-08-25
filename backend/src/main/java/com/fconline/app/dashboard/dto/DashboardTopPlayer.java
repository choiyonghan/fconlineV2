package com.fconline.app.dashboard.dto;

/** TOP3 카테고리(최다골/도움/공격포인트/태클+인터셉트/선방) 1행. */
public record DashboardTopPlayer(String playerName, int value) {
}
