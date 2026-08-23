package com.fconline.infrastructure.chat;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 카카오톡 대화 내보내기(.txt) 원본을 Supabase Storage의 private 버킷에서 읽어온다.
 * 이 리포지토리는 public이라 대화 원문을 git에는 절대 두지 않는다 — service_role 키로만
 * 접근 가능한 private 버킷에 올려두고, 백엔드가 그때그때 필요할 때만 가져온다.
 */
@Component
public class KakaoChatArchiveClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoChatArchiveClient.class);

    private final RestClient supabaseStorageRestClient;
    private final SupabaseChatStorageProperties properties;

    public KakaoChatArchiveClient(RestClient supabaseStorageRestClient, SupabaseChatStorageProperties properties) {
        this.supabaseStorageRestClient = supabaseStorageRestClient;
        this.properties = properties;
    }

    /** 설정이 안 됐거나(키/버킷 미등록) 조회에 실패하면 empty — 호출부가 안내 메시지로 대체한다. */
    public Optional<String> fetchChatLog() {
        if (properties.serviceRoleKey() == null || properties.serviceRoleKey().isBlank()
                || properties.url() == null || properties.url().isBlank()) {
            return Optional.empty();
        }
        try {
            String body = supabaseStorageRestClient.get()
                    .uri("/storage/v1/object/{bucket}/{path}", properties.bucket(), properties.objectPath())
                    .header("Authorization", "Bearer " + properties.serviceRoleKey())
                    .header("apikey", properties.serviceRoleKey())
                    .retrieve()
                    .body(String.class);
            return Optional.ofNullable(body).filter(s -> !s.isBlank());
        } catch (Exception e) {
            log.warn("카카오톡 대화 로그 조회 실패 (bucket={}, path={})", properties.bucket(), properties.objectPath(), e);
            return Optional.empty();
        }
    }
}
