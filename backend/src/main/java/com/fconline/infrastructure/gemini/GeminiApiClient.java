package com.fconline.infrastructure.gemini;

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
 * Gemini(Google AI Studio 무료 티어) 연동 — Generative Language API(v1beta, generateContent).
 *
 * <p><b>마이그레이션 이력(2026-08-31)</b>: 원래 Gemini를 썼는데, 이 프로젝트의 (당시) API 키가
 * "신규 사용자"로 분류돼 gemini-3.6-flash 하나만 쓸 수 있고 무료 한도가 하루 20회뿐이라
 * Groq(OpenAI 호환)로 전환했다. 그런데 Groq 무료 티어는 반대로 분당 토큰(TPM) 한도가 우리
 * 프롬프트 크기(성격 리포트를 통째로 넣어 질문 1건당 약 1만~2만 토큰)에 비해 너무 작아서
 * (gpt-oss 계열·qwen 계열 TPM 8,000 고정, 대안이던 groq/compound도 내부적으로 그 모델로 라우팅될
 * 때가 있어 413/429가 계속 났다) 실사용이 불가능했다. 새 구글 계정으로 키를 다시 발급해보니
 * gemini-3.1-flash-lite(및 3.5-flash-lite)는 신규 사용자에게도 RPD 500 · TPM 250,000이 열려
 * 있어(*-flash-lite 계열만 그렇다 — 일반 -flash/-pro 계열은 여전히 RPD 20) 우리 프롬프트를
 * 여유롭게 감당한다는 걸 실측으로 확인해 다시 Gemini로 돌아왔다. 응답 매핑(candidates[0].
 * content.parts[0].text 추출)은 이 클래스의 책임이고, "무엇을 물어볼지"는
 * app.insight.facade.InsightFacade/app.dashboard.facade.DashboardSnapshotBuilder가 조립한다.
 */
@Component
public class GeminiApiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiApiClient.class);

    /** Gemini 무료 티어도 순간적으로 429(RESOURCE_EXHAUSTED)/503(UNAVAILABLE)이 날 수 있다 —
     * 하루 단위 쿼터 초과와 달리 몇 초면 풀리는 경우가 많아 시도마다 대기를 늘려가며 재시도한다. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 3000;

    private final RestClient geminiRestClient;
    private final GeminiApiProperties properties;

    public GeminiApiClient(RestClient geminiRestClient, GeminiApiProperties properties) {
        this.geminiRestClient = geminiRestClient;
        this.properties = properties;
    }

    /** systemInstruction(역할/제약)과 userPrompt(데이터+질문)를 분리해서 받는다. */
    public String ask(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, false);
    }

    /**
     * ask()와 같지만 responseMimeType=application/json을 강제해 코드블록/설명 없이 순수 JSON만
     * 받는다 — 응답을 그대로 파싱해야 하는 호출부(예: 대시보드 AI 랭킹)용.
     */
    public String askJson(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, true);
    }

    private String call(String systemInstruction, String userPrompt, boolean jsonMode) {
        if (properties.key() == null || properties.key().isBlank()) {
            throw new AiApiException("GEMINI_API_KEY가 설정되어 있지 않습니다.");
        }

        Map<String, Object> generationConfig = new java.util.HashMap<>(Map.of("temperature", 0.4));
        if (jsonMode) {
            generationConfig.put("responseMimeType", "application/json");
        }
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", userPrompt)))),
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "generationConfig", generationConfig);

        Function<UriBuilder, URI> uri = uriBuilder -> uriBuilder
                .path("/models/{model}:generateContent")
                .build(properties.model());

        JsonNode response = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                response = geminiRestClient.post().uri(uri).body(body).retrieve().body(JsonNode.class);
                break;
            } catch (HttpStatusCodeException e) {
                boolean retryable = (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE
                        || e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                        && attempt < MAX_ATTEMPTS;
                if (retryable) {
                    long delayMs = RETRY_DELAY_MS * attempt; // 시도마다 대기 늘림(3s→6s)
                    log.warn("Gemini API {}(일시적 과부하/속도 제한) — {}ms 후 재시도합니다 ({}/{})",
                            e.getStatusCode(), delayMs, attempt, MAX_ATTEMPTS);
                    sleepBeforeRetry(delayMs);
                    continue;
                }
                log.error("Gemini API 호출 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new AiApiException("Gemini API 호출에 실패했습니다.", e);
            } catch (RestClientException e) {
                // HttpStatusCodeException이 아닌 나머지(타임아웃 등 ResourceAccessException 포함) —
                // RestClientConfig가 읽기 타임아웃을 걸어둬서 응답이 안 오면 여기로 떨어진다.
                log.error("Gemini API 호출 중 오류(타임아웃 등): {}", e.toString());
                throw new AiApiException("Gemini API 응답이 너무 오래 걸려 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
            }
        }

        if (response == null) {
            throw new AiApiException("Gemini 응답이 비어있습니다.");
        }

        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String blockReason = response.path("promptFeedback").path("blockReason").asText(null);
            throw new AiApiException(
                    blockReason != null ? "Gemini가 안전 필터로 응답을 막았습니다: " + blockReason
                            : "Gemini 응답에 candidates가 없습니다.");
        }

        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            // finishReason=MAX_TOKENS 등으로 텍스트 파트 없이 끝난 경우 — 재질문 유도.
            String finishReason = candidates.get(0).path("finishReason").asText(null);
            throw new AiApiException(
                    "Gemini 응답에 텍스트가 없습니다" + (finishReason != null ? "(finishReason=" + finishReason + ")" : "") + ".");
        }
        return parts.get(0).path("text").asText("");
    }

    private static void sleepBeforeRetry(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiApiException("Gemini API 재시도 대기 중 인터럽트되었습니다.", ie);
        }
    }
}
