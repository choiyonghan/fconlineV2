package com.fconline.infrastructure.personality;

import com.fconline.infrastructure.cache.CacheNames;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Claude Code가 카톡 대화 원본을 직접 읽고 손으로 써둔 친구 성격 리포트(.md, 실명 파일명)를
 * Supabase Storage(private bucket)에서 읽어온다 — InsightFacade.ask()가 전적 데이터뿐 아니라
 * 이 사람의 성격/말투/성향까지 참고해서 더 캐릭터에 맞는 답변을 하도록 붙이는 용도.
 * 아직 리포트가 안 써진 유저도 있어서(전원 완성된 게 아님) 없으면 조용히 빈 값으로 처리한다.
 */
@Component
public class PersonalityReportClient {

    private static final Logger log = LoggerFactory.getLogger(PersonalityReportClient.class);

    private final RestClient personalityReportRestClient;

    public PersonalityReportClient(RestClient personalityReportRestClient) {
        this.personalityReportRestClient = personalityReportRestClient;
    }

    /**
     * realName은 리포트 파일명(확장자 제외)과 정확히 일치해야 한다 — 예: "최용한" → "최용한.md".
     * Redis 캐시 대상(TTL 3시간, RedisCacheConfig.TTL) — 리포트 내용은 누가 새로 고쳐 올리기
     * 전까진 안 바뀌는 데이터라 매 질문마다 Supabase Storage를 다시 칠 필요가 없다.
     */
    @Cacheable(CacheNames.PERSONALITY_REPORTS)
    public Optional<String> fetch(String realName) {
        try {
            String body = personalityReportRestClient.get()
                    .uri("/{fileName}", realName + ".md")
                    .retrieve()
                    .body(String.class);
            return (body == null || body.isBlank()) ? Optional.empty() : Optional.of(body);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("성격 리포트 조회 실패, 스킵합니다: realName={}", realName, e);
            return Optional.empty();
        }
    }
}
