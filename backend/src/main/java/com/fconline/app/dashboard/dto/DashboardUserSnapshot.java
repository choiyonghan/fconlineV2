package com.fconline.app.dashboard.dto;

/** summary = "모두의 커스텀"(matchType=CUSTOM, 현재시즌 날짜 범위) 서머리. 공식전은 대시보드에 안 남긴다. */
public record DashboardUserSnapshot(String ouid, String nickname, DashboardScopeSummary summary) {
}
