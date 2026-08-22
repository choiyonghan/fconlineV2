package com.fconline.app.common.exception;

import com.fconline.domain.shared.exception.DomainException;
import com.fconline.domain.shared.exception.GeminiApiException;
import com.fconline.domain.shared.exception.NexonApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(DomainException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("DOMAIN_ERROR", e.getMessage()));
    }

    @ExceptionHandler(NexonApiException.class)
    public ResponseEntity<ApiErrorResponse> handleNexonApiException(NexonApiException e) {
        log.error("Nexon API 연동 오류", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiErrorResponse.of("NEXON_API_ERROR", e.getMessage()));
    }

    @ExceptionHandler(GeminiApiException.class)
    public ResponseEntity<ApiErrorResponse> handleGeminiApiException(GeminiApiException e) {
        log.error("Gemini API 연동 오류", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiErrorResponse.of("GEMINI_API_ERROR", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "일시적인 오류가 발생했습니다."));
    }
}
