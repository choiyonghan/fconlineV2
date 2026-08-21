package com.fconline.domain.shared.exception;

/**
 * Nexon Open API 호출이 실패했을 때 던지는 예외.
 * v1은 실패 시 null을 반환해 "데이터 없음"과 "네트워크 장애"를 구분할 수 없었다(6.9절 문제).
 * v2는 이 타입드 예외로 실패를 명시적으로 전파한다.
 */
public class NexonApiException extends RuntimeException {

    private final int statusCode;

    public NexonApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public NexonApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
