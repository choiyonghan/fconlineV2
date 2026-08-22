package com.fconline.domain.shared.exception;

/**
 * Google Gemini API 호출이 실패했을 때 던지는 예외.
 * NexonApiException과 같은 이유로 타입드 예외를 쓴다 — 실패를 null/빈 문자열로 감추지 않고
 * 명시적으로 전파해서 "AI 응답 없음"과 "API 자체 실패"를 구분할 수 있게 한다.
 */
public class GeminiApiException extends RuntimeException {

    public GeminiApiException(String message) {
        super(message);
    }

    public GeminiApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
