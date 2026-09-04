package com.fconline.app.dashboard.dto;

/** summary = 이 스냅샷 파일의 스코프(CUSTOM 또는 OFFICIAL, DashboardSnapshotFile.matchType 참고) 서머리. */
public record DashboardUserSnapshot(String ouid, String nickname, DashboardScopeSummary summary) {
}
