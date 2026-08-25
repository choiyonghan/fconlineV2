package com.fconline.app.dashboard.dto;

/** custom = "모두의 커스텀"(matchType=CUSTOM, 전체 기간), season = "현재시즌"(matchType=OFFICIAL, 현재시즌). */
public record DashboardUserSnapshot(String ouid, String nickname,
                                     DashboardScopeSummary custom, DashboardScopeSummary season) {
}
