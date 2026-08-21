package com.fconline.domain.shared.exception;

/**
 * Nexon Open API가 429(Too Many Requests)를 반환했을 때 던지는 타입드 예외.
 * v1은 err.message.includes("429") 문자열 매칭으로 429를 판별했다(취약함) — v2는 타입으로 판별한다.
 */
public class RateLimitException extends NexonApiException {

    public RateLimitException(String message) {
        super(message, 429);
    }
}
