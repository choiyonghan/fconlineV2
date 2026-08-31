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
 * Claude Code가 카톡 대화 원본을 직접 읽고 손으로 써둔 친구 성격 리포트(.md)를 Supabase
 * Storage(private bucket)에서 읽어온다 — InsightFacade.ask()가 전적 데이터뿐 아니라 이
 * 사람의 성격/말투/성향까지 참고해서 더 캐릭터에 맞는 답변을 하도록 붙이는 용도.
 *
 * 파일 키는 ouid(예: "c4f8613f9995dc79c5413cc61fa1d6f2.md")를 쓴다 — 처음엔 실명(한글)을
 * 그대로 키로 썼다가 운영에서 실제로 겪은 문제: Supabase Storage 서버가 키에 한글(비-ASCII)이
 * 들어오면 URL 인코딩을 제대로 해도 "InvalidKey" 400으로 거절한다. ouid는 원래 영문+숫자라
 * 이 문제 자체가 없고, 이미 insight-snapshots(ouid_matchType.json)에서 쓰는 것과 같은 키 관례다.
 * 계정을 2개 쓰는 사람은 ouid마다 같은 리포트 파일을 각각 올려야 한다(InsightFacade가 매핑을
 * 알려줄 때 안내).
 */
@Component
public class PersonalityReportClient {

    private static final Logger log = LoggerFactory.getLogger(PersonalityReportClient.class);

    private final RestClient personalityReportRestClient;

    public PersonalityReportClient(RestClient personalityReportRestClient) {
        this.personalityReportRestClient = personalityReportRestClient;
    }

    /**
     * Redis 캐시 대상(TTL 3시간, RedisCacheConfig.TTL) — 리포트 내용은 누가 새로 고쳐 올리기
     * 전까진 안 바뀌는 데이터라 매 질문마다 Supabase Storage를 다시 칠 필요가 없다. 리포트가
     * 없는 유저(빈 Optional)는 캐싱하지 않는다(unless) — RedisCacheConfig가 null 값 저장을
     * 막아둬서 그대로 캐싱을 시도하면 매번 IllegalArgumentException 경고만 쌓인다.
     */
    @Cacheable(value = CacheNames.PERSONALITY_REPORTS, unless = "#result == null || #result.isEmpty()")
    public Optional<String> fetch(String ouid) {
        try {
            String body = personalityReportRestClient.get()
                    .uri("/{fileName}", ouid + ".md")
                    .retrieve()
                    .body(String.class);
            return (body == null || body.isBlank()) ? Optional.empty() : Optional.of(body);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("성격 리포트 조회 실패, 스킵합니다: ouid={}", ouid, e);
            return Optional.empty();
        }
    }
}
