package com.fconline.infrastructure.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오톡 대화 로그가 저장된 Supabase Storage(private 버킷) 접속 정보.
 * serviceRoleKey는 RLS를 우회하는 강한 권한이라 절대 커밋하지 않고 Render 환경변수
 * (SUPABASE_SERVICE_ROLE_KEY)로만 주입한다 — 로컬 기본값은 항상 빈 문자열.
 */
@ConfigurationProperties(prefix = "supabase.storage")
public record SupabaseChatStorageProperties(String url, String serviceRoleKey, String bucket, String objectPath) {
}
