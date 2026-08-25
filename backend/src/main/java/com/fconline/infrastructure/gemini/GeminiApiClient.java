package com.fconline.infrastructure.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fconline.domain.shared.exception.GeminiApiException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Google Gemini API(무료 티어) 연동. NexonApiClient와 같은 이유로 인프라 계층에 둔다 —
 * 응답 매핑(candidates[0].content.parts[0].text 추출)은 이 클래스의 책임이고,
 * "무엇을 물어볼지"는 app.insight.facade.InsightFacade가 조립한다.
 */
@Component
public class GeminiApiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiApiClient.class);

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
     * ask()와 같지만 generationConfig.responseMimeType=application/json을 강제해 코드블록/설명
     * 없이 순수 JSON만 받는다 — 응답을 그대로 파싱해야 하는 호출부(예: 대시보드 AI 랭킹)용.
     */
    public String askJson(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, true);
    }

    private String call(String systemInstruction, String userPrompt, boolean jsonMode) {
        if (properties.key() == null || properties.key().isBlank()) {
            throw new GeminiApiException("GEMINI_API_KEY가 설정되어 있지 않습니다.");
        }

        Map<String, Object> generationConfig = jsonMode
                ? Map.of("temperature", 0.4, "responseMimeType", "application/json")
                : Map.of("temperature", 0.4);
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "contents", List.of(Map.of("parts", List.of(Map.of("text", userPrompt)))),
                "generationConfig", generationConfig
        );

        JsonNode response;
        try {
            response = geminiRestClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/models/{model}:generateContent")
                            .queryParam("key", properties.key())
                            .build(properties.model()))
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpStatusCodeException e) {
            log.error("Gemini API 호출 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new GeminiApiException("Gemini API 호출에 실패했습니다.", e);
        }

        if (response == null) {
            throw new GeminiApiException("Gemini 응답이 비어있습니다.");
        }

        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            // 안전 필터에 걸리면 candidates가 비고 promptFeedback.blockReason이 대신 채워진다.
            String blockReason = response.path("promptFeedback").path("blockReason").asText(null);
            throw new GeminiApiException(
                    blockReason != null ? "Gemini가 응답을 차단했습니다: " + blockReason : "Gemini 응답에 candidates가 없습니다.");
        }

        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new GeminiApiException("Gemini 응답에 텍스트가 없습니다.");
        }
        return parts.get(0).path("text").asText("");
    }
}
