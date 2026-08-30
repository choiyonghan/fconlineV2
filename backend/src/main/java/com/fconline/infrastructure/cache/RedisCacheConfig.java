package com.fconline.infrastructure.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 무료 Redis(Upstash 등)를 캐시로 쓴다 — 아래 RedisCacheManagerBuilderCustomizer는
 * spring.cache.type=redis일 때만 실제로 적용된다(그 외엔 Spring Boot가 CacheType.SIMPLE로
 * 자동 폴백하는 ConcurrentMapCacheManager를 쓰고, 이 커스터마이저 자체가 호출되지 않는다).
 * 로컬/CI/테스트는 CACHE_TYPE/REDIS_URL을 안 주므로(application.yml 기본값) Redis가 전혀
 * 필요 없다 — 이 클래스가 로드돼도 컨텍스트가 Redis에 실제로 연결을 시도하지 않는다.
 *
 * 캐시에 넣는 값이 record/List&lt;record&gt;/Page 같은 도메인 DTO라, JDK 직렬화(Serializable
 * 요구, 이 DTO들은 구현 안 함) 대신 컨트롤러가 HTTP 응답에 쓰는 것과 같은 앱의 ObjectMapper
 * (Page 모듈 등 spring-data-commons가 이미 등록해둔 상태)를 복사해 재사용한다. 다만 Redis에서
 * 읽어올 땐 대상 타입을 모른 채(Object로) 역직렬화해야 하므로, 복사본에만 폴리모픽 타입 정보
 * (@class)를 추가로 켠다 — 원본 ObjectMapper(HTTP 응답 직렬화용)는 그대로 둔다.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

    private final CacheErrorHandler cacheErrorHandler;

    public RedisCacheConfig(CacheErrorHandler cacheErrorHandler) {
        this.cacheErrorHandler = cacheErrorHandler;
    }

    /** Redis(Upstash 등)가 잠깐 끊겨도 캐시 조회/저장 실패가 API 응답까지 깨뜨리지 않게 한다 — 원본 조회로 폴백. */
    @Override
    public CacheErrorHandler errorHandler() {
        return cacheErrorHandler;
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(ObjectMapper objectMapper) {
        ObjectMapper redisObjectMapper = objectMapper.copy();
        redisObjectMapper.activateDefaultTyping(
                redisObjectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        var valueSerializer = RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper));
        var keySerializer = RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer());

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(keySerializer)
                .serializeValuesWith(valueSerializer)
                .entryTtl(Duration.ofMinutes(5));

        return builder -> builder
                .cacheDefaults(defaults)
                .withCacheConfiguration(CacheNames.RECORDS, defaults.entryTtl(Duration.ofMinutes(5)))
                .withCacheConfiguration(CacheNames.INSIGHT_ANSWERS, defaults.entryTtl(Duration.ofMinutes(10)));
    }
}
