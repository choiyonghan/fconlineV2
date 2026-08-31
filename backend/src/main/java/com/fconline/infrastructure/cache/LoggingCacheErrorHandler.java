package com.fconline.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;

/**
 * 무료 Redis(Upstash 등)는 일일 커맨드 한도·순간 장애가 있을 수 있어서, 캐시 조회/저장/삭제가
 * 실패해도 예외를 그대로 던지지 않고 로그만 남긴 뒤 넘어간다 — 호출부는 캐시가 없던 것처럼
 * 그대로 DB(또는 AI API)를 타서 정상 응답한다. 캐시는 어디까지나 최적화이지 가용성의
 * 전제조건이 아니라는 게 이 클래스의 핵심 판단이다(RedisCacheConfig가 이 핸들러를 등록).
 */
@Component
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("캐시 조회 실패 [{}] key={} — 원본 조회로 폴백: {}", cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("캐시 저장 실패 [{}] key={}: {}", cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("캐시 삭제 실패 [{}] key={}: {}", cache.getName(), key, exception.toString());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("캐시 전체 삭제 실패 [{}]: {}", cache.getName(), exception.toString());
    }
}
