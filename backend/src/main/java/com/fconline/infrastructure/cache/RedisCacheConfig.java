package com.fconline.infrastructure.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fconline.app.insight.dto.AskResponse;
import com.fconline.app.opponent.dto.OpponentSummaryResponse;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.MatchShotsResponse;
import com.fconline.app.record.dto.MatchSquadEntryResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.PlayerGradeResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.app.season.dto.SeasonResponse;
import com.fconline.app.user.dto.TrackedUserResponse;
import com.fconline.app.user.dto.UserTeamPeriodResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 무료 Redis(Upstash 등)를 캐시로 쓴다 — 아래 RedisCacheManagerBuilderCustomizer는
 * spring.cache.type=redis일 때만 실제로 적용된다(그 외엔 Spring Boot가 CacheType.SIMPLE로
 * 자동 폴백하는 ConcurrentMapCacheManager를 쓰고, 이 커스터마이저 자체가 호출되지 않는다).
 * 로컬/CI/테스트는 CACHE_TYPE/REDIS_URL을 안 주므로(application.yml 기본값) Redis가 전혀
 * 필요 없다 — 이 클래스가 로드돼도 컨텍스트가 Redis에 실제로 연결을 시도하지 않는다.
 *
 * CacheNames의 각 캐시는 정확히 하나의 반환 타입만 담으므로(메서드별로 캐시 이름을 분리한 이유는
 * CacheNames 주석 참고), 캐시별로 {@link Jackson2JsonRedisSerializer}에 정확한 타입을 박아
 * 직렬화한다 — 컨트롤러가 HTTP 응답에 쓰는 것과 같은 앱의 ObjectMapper를 그대로 재사용해서
 * (Page 모듈 등 이미 등록된 상태) 동작이 일관된다. 타입이 정확히 정해져 있으므로 폴리모픽 타입
 * 정보(@class)가 필요 없어 페이로드가 더 작고 역직렬화도 더 빠르다 — 애초에 무료 Redis(Render와
 * 리전을 맞춰도 크로스 클라우드 왕복이 있어 지연에 민감)를 쓰는 이유가 속도인데, 안 써도 되는
 * 타입 메타데이터를 굳이 붙일 이유가 없다. CacheNames에 등록을 깜빡한 새 캐시 이름이 생기면
 * cacheDefaults(범용 폴리모픽 직렬화)로 안전하게 폴백한다.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

    /**
     * 여기서 캐싱하는 모든 값은 조회성 데이터(직접 쓰는 API가 없다 — 매치 동기화/AI 답변 생성이
     * 각자의 배치·요청 경로로 별도로 갱신할 뿐)라 전부 같은 TTL로 통일한다(요청). 시즌
     * current 필드처럼 "오늘" 기준 계산값도 있지만, 이 정도 지연은 이 앱의 성격상 허용
     * 가능하다고 판단. 원래 3시간이었는데, 2026-09-02에 team-periods 캐시가 배포 직후의 옛
     * 값을 오래 물고 있는 걸 실제로 겪은 뒤 30분으로 줄임(요청) — 그래도 캐시 자체를 없애는
     * 것보단, 배포 직후 값이 바뀌는 데이터를 다루는 빈도가 낮으니 짧은 TTL로 절충.
     */
    private static final Duration TTL = Duration.ofMinutes(30);

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
        var tf = objectMapper.getTypeFactory();

        Map<String, RedisCacheConfiguration> perCache = Map.ofEntries(
                Map.entry(CacheNames.OVERALL_RECORD, typedConfig(objectMapper, OverallRecordResponse.class, TTL)),
                Map.entry(CacheNames.ALL_PLAYERS,
                        typedConfig(objectMapper, tf.constructCollectionType(List.class, TopPlayerResponse.class), TTL)),
                Map.entry(CacheNames.SHOT_HEATMAP, typedConfig(objectMapper, ShotHeatmapResponse.class, TTL)),
                Map.entry(CacheNames.CONCEDED_SHOT_HEATMAP, typedConfig(objectMapper, ShotHeatmapResponse.class, TTL)),
                Map.entry(CacheNames.ASSISTED_SHOT_HEATMAP, typedConfig(objectMapper, ShotHeatmapResponse.class, TTL)),
                Map.entry(CacheNames.MATCH_SHOTS, typedConfig(objectMapper, MatchShotsResponse.class, TTL)),
                Map.entry(CacheNames.MATCH_SQUAD,
                        typedConfig(objectMapper, tf.constructCollectionType(List.class, MatchSquadEntryResponse.class), TTL)),
                Map.entry(CacheNames.MATCH_STATS, typedConfig(objectMapper, RecentMatchResponse.class, TTL)),
                Map.entry(CacheNames.ASSIST_CHAINS,
                        typedConfig(objectMapper, tf.constructCollectionType(List.class, AssistChainResponse.class), TTL)),
                Map.entry(CacheNames.PLAYER_GRADES,
                        typedConfig(objectMapper, tf.constructCollectionType(List.class, PlayerGradeResponse.class), TTL)),
                // RecentMatchesPageCache가 Page 대신 CachedPage(평범한 record)를 캐싱한다 — Page는
                // 인터페이스라 spring-data-commons의 Page Jackson 모듈이 직렬화("쓰기")만 지원하고
                // 역직렬화("읽기")는 지원하지 않아서(운영에서 실제로 겪은 에러: "Cannot construct
                // instance of Page") 그대로 캐싱하면 매번 깨진다. CachedPage 클래스 주석 참고.
                Map.entry(CacheNames.RECENT_MATCHES,
                        typedConfig(objectMapper, tf.constructParametricType(CachedPage.class, RecentMatchResponse.class), TTL)),
                Map.entry(CacheNames.INSIGHT_ANSWERS, typedConfig(objectMapper, AskResponse.class, TTL)),
                Map.entry(CacheNames.OPPONENTS,
                        typedConfig(objectMapper, tf.constructCollectionType(List.class, OpponentSummaryResponse.class), TTL)),
                Map.entry(CacheNames.TRACKED_USERS,
                        typedConfig(objectMapper, tf.constructCollectionType(List.class, TrackedUserResponse.class), TTL)),
                Map.entry(CacheNames.SEASONS,
                        typedConfig(objectMapper, tf.constructCollectionType(List.class, SeasonResponse.class), TTL)),
                Map.entry(CacheNames.TEAM_PERIODS,
                        typedConfig(objectMapper, tf.constructCollectionType(List.class, UserTeamPeriodResponse.class), TTL)),
                // 성격 리포트는 그냥 마크다운 원문(String)이라 Jackson JSON 포장 없이 바이트 그대로
                // 저장한다 — 리포트 안에 따옴표·줄바꿈이 많아 JSON 이스케이프를 거칠 이유가 없다.
                Map.entry(CacheNames.PERSONALITY_REPORTS,
                        baseConfig(TTL).serializeValuesWith(SerializationPair.fromSerializer(new StringRedisSerializer())))
        );

        RedisCacheConfiguration fallback = polymorphicFallbackConfig(objectMapper);

        return builder -> builder
                .cacheDefaults(fallback)
                .withInitialCacheConfigurations(perCache);
    }

    private RedisCacheConfiguration typedConfig(ObjectMapper objectMapper, Class<?> type, Duration ttl) {
        return typedConfig(objectMapper, objectMapper.getTypeFactory().constructType(type), ttl);
    }

    private RedisCacheConfiguration typedConfig(ObjectMapper objectMapper, JavaType javaType, Duration ttl) {
        RedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, javaType);
        return baseConfig(ttl).serializeValuesWith(SerializationPair.fromSerializer(serializer));
    }

    /**
     * CacheNames에 등록을 깜빡한 캐시 이름이 생겼을 때만 쓰이는 안전망. record는 컴파일러가 항상
     * final로 만드는데, DefaultTyping.NON_FINAL은 "final이면 타입이 명확하다"고 보고 루트 값에도
     * @class를 안 붙여서(운영에서 실제로 겪은 버그 — missing type id property '@class') 대신
     * EVERYTHING을 쓴다.
     */
    private RedisCacheConfiguration polymorphicFallbackConfig(ObjectMapper objectMapper) {
        ObjectMapper polymorphicMapper = objectMapper.copy();
        polymorphicMapper.activateDefaultTyping(
                polymorphicMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        var serializer = new GenericJackson2JsonRedisSerializer(polymorphicMapper);
        return baseConfig(TTL).serializeValuesWith(SerializationPair.fromSerializer(serializer));
    }

    private RedisCacheConfiguration baseConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .entryTtl(ttl);
    }
}
