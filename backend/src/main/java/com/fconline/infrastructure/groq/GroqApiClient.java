package com.fconline.infrastructure.groq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fconline.domain.shared.exception.AiApiException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * Groq(무료 티어) 연동 — OpenAI 호환 Chat Completions API. 원래 Gemini를 썼는데, 이 프로젝트의
 * Gemini API 키가 "신규 사용자"로 분류돼 gemini-3.6-flash 하나만 쓸 수 있고 무료 한도가 하루
 * 20회로 너무 타이트해서(운영에서 실제로 RESOURCE_EXHAUSTED 겪음) Groq로 완전히 갈아탔다 —
 * 신용카드 없이 하루 14,400회(모델별로 다름)까지 무료라 훨씬 넉넉함.
 * 응답 매핑(choices[0].message.content 추출)은 이 클래스의 책임이고, "무엇을 물어볼지"는
 * app.insight.facade.InsightFacade/app.dashboard.facade.DashboardSnapshotBuilder가 조립한다.
 */
@Component
public class GroqApiClient {

    private static final Logger log = LoggerFactory.getLogger(GroqApiClient.class);

    /** Groq 무료 티어는 분당 요청 한도(RPM)가 있어서 순간적으로 429/503이 날 수 있다 —
     * 한 번만 짧게 쉬었다 재시도한다(하루 단위 쿼터 초과와 달리 몇 초면 풀리는 경우가 많음). */
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 2000;

    private final RestClient groqRestClient;
    private final GroqApiProperties properties;

    public GroqApiClient(RestClient groqRestClient, GroqApiProperties properties) {
        this.groqRestClient = groqRestClient;
        this.properties = properties;
    }

    /** systemInstruction(역할/제약)과 userPrompt(데이터+질문)를 분리해서 받는다. */
    public String ask(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, false);
    }

    /**
     * ask()와 같지만 response_format=json_object를 강제해 코드블록/설명 없이 순수 JSON만 받는다 —
     * 응답을 그대로 파싱해야 하는 호출부(예: 대시보드 AI 랭킹)용.
     */
    public String askJson(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, true);
    }

    private String call(String systemInstruction, String userPrompt, boolean jsonMode) {
        if (properties.key() == null || properties.key().isBlank()) {
            throw new AiApiException("GROQ_API_KEY가 설정되어 있지 않습니다.");
        }

        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "model", properties.model(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemInstruction),
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", 0.4
        ));
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        Function<UriBuilder, URI> uri = uriBuilder -> uriBuilder.path("/chat/completions").build();

        JsonNode response = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                response = groqRestClient.post().uri(uri).body(body).retrieve().body(JsonNode.class);
                break;
            } catch (HttpStatusCodeException e) {
                boolean retryable = (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE
                        || e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                        && attempt < MAX_ATTEMPTS;
                if (retryable) {
                    log.warn("Groq API {}(일시적 과부하/속도 제한) — {}ms 후 재시도합니다 ({}/{})",
                            e.getStatusCode(), RETRY_DELAY_MS, attempt, MAX_ATTEMPTS);
                    sleepBeforeRetry();
                    continue;
                }
                log.error("Groq API 호출 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new AiApiException("Groq API 호출에 실패했습니다.", e);
            } catch (RestClientException e) {
                // HttpStatusCodeException이 아닌 나머지(타임아웃 등 ResourceAccessException 포함) —
                // RestClientConfig가 읽기 타임아웃을 걸어둬서 응답이 안 오면 여기로 떨어진다.
                log.error("Groq API 호출 중 오류(타임아웃 등): {}", e.toString());
                throw new AiApiException("Groq API 응답이 너무 오래 걸려 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
            }
        }

        if (response == null) {
            throw new AiApiException("Groq 응답이 비어있습니다.");
        }

        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            String errorMessage = response.path("error").path("message").asText(null);
            throw new AiApiException(
                    errorMessage != null ? "Groq가 오류를 반환했습니다: " + errorMessage : "Groq 응답에 choices가 없습니다.");
        }

        return choices.get(0).path("message").path("content").asText("");
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiApiException("Groq API 재시도 대기 중 인터럽트되었습니다.", ie);
        }
    }
}
