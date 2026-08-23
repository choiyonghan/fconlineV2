package com.fconline.app.insight.facade;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "닉네임:실명" 콤마 구분 목록. 실명은 이 리포지토리가 public이라 절대 git에 커밋하지 않고
 * Render 환경변수(TRACKED_USER_REAL_NAMES)로만 주입한다 — 로컬/기본값은 항상 빈 문자열.
 */
@ConfigurationProperties(prefix = "tracked-user")
public record TrackedUserAliasProperties(String realNames) {
}
