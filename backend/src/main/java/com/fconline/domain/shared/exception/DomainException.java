package com.fconline.domain.shared.exception;

/**
 * 도메인 규칙 위반을 나타내는 예외의 최상위 타입.
 * app.common.exception.GlobalExceptionHandler가 이 타입 계층을 HTTP 응답으로 매핑한다.
 */
public class DomainException extends RuntimeException {

    public DomainException(String message) {
        super(message);
    }

    public DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
