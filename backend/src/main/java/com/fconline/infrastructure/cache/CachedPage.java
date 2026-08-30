package com.fconline.infrastructure.cache;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * {@code Page<T>}는 인터페이스라(런타임엔 PageImpl) 그대로 Redis에 캐싱하면 깨진다 —
 * spring-data-commons의 Page Jackson 모듈은 HTTP 응답 직렬화("쓰기")만 지원하고 역직렬화
 * ("읽기")는 지원하지 않는다(운영에서 실제로 겪은 에러: "Cannot construct instance of
 * `org.springframework.data.domain.Page` (no Creators...)"). 이 평범한 record에 content와
 * 총 개수만 담아 대신 캐싱하고, {@link #toPage(Pageable)}로 필요할 때 새 PageImpl을 만든다.
 */
public record CachedPage<T>(List<T> content, long totalElements) {

    public static <T> CachedPage<T> from(Page<T> page) {
        return new CachedPage<>(page.getContent(), page.getTotalElements());
    }

    public Page<T> toPage(Pageable pageable) {
        return new PageImpl<>(content, pageable, totalElements);
    }
}
