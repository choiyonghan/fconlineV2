package com.fconline.infrastructure.personality;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supabase Storage(private bucket)에 올려둔 카톡 성격 리포트(.md) 접근 정보. 파일명 자체가
 * 실명이라(예: "최용한.md") public GitHub 저장소에는 절대 올리지 않고, Render 환경변수로만
 * 주입한다(TRACKED_USER_REAL_NAMES와 같은 원칙). serviceRoleKey는 RLS를 우회하는 백엔드
 * 전용 키라 브라우저에는 절대 노출되면 안 된다 — 이 프로퍼티를 프론트로 내려주는 API는
 * 만들지 않는다.
 */
@ConfigurationProperties(prefix = "supabase.storage")
public record PersonalityReportProperties(String url, String serviceRoleKey, String bucket) {
}
