package com.fconline.infrastructure.cache;

import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.RecentMatchRaw;
import java.time.Instant;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordFacade.getRecentMatches 전용 캐시 보조 빈. Page&lt;RecentMatchRaw&gt;를 그대로
 * {@code @Cacheable}에 태우면 CachedPage 주석에 적은 이유로 역직렬화가 깨지므로, 여기서
 * DB 조회 결과를 CachedPage로 감싸 캐싱하고 RecordFacade가 필요할 때 PageImpl로 복원한다.
 * 별도 빈으로 분리한 이유: RecordFacade 안에서 이 메서드를 호출하는 방식(self-invocation)이면
 * Spring의 캐시 프록시를 안 거쳐서 캐시가 아예 안 걸리기 때문이다.
 */
@Component
public class RecentMatchesPageCache {

    private final MatchDetailRepository matchDetailRepository;

    public RecentMatchesPageCache(MatchDetailRepository matchDetailRepository) {
        this.matchDetailRepository = matchDetailRepository;
    }

    /** TTL 5분(RedisCacheConfig) — RecordFacade.getOverallRecord 주석의 "5분 지연 허용" 전제와 동일. */
    @Cacheable(CacheNames.RECENT_MATCHES)
    @Transactional(readOnly = true)
    public CachedPage<RecentMatchRaw> fetch(String ouid, MatchType matchType, Instant from, Instant to, Pageable pageable) {
        return CachedPage.from(matchDetailRepository.findRecentByOuid(ouid, matchType, from, to, pageable));
    }
}
