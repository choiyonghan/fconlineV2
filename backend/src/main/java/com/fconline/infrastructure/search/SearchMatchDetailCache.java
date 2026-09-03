package com.fconline.infrastructure.search;

import com.fconline.domain.match.gateway.NexonMatchData;
import com.fconline.domain.match.gateway.NexonMatchGateway;
import com.fconline.infrastructure.cache.CacheNames;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 유저 검색(app.search) 전용 — matchId 1건의 Nexon match-detail 원본을 캐싱한다.
 *
 * 추적 대상이 아닌 임의 유저는 DB에 아무것도 없어서(sync 배치가 손대지 않음), 검색할 때마다
 * 매치 상세를 Nexon에서 실시간으로 받아야 한다. 매치 결과는 한 번 끝나면 다시 안 바뀌는
 * 데이터라 이 캐시만 다른 조회성 캐시(RedisCacheConfig.TTL=30분)보다 훨씬 길게 잡는다
 * (RedisCacheConfig에 별도 Duration으로 등록).
 *
 * <b>반드시 별도 빈으로 분리해야 한다</b> — SearchFacade 안에 이 메서드를 두고 facade가 자기
 * 자신을 호출(self-invocation)하면 Spring AOP 프록시를 우회해 {@code @Cacheable}이 조용히
 * 무시된다(CacheNames 클래스 주석에도 있는 잘 알려진 함정). SearchFacade는 이 빈을 주입받아
 * 호출하는 방식으로만 캐싱이 실제로 걸린다 — search() 집계 루프뿐 아니라 매치 상세 모달용
 * getMatchShots/getMatchSquad도 전부 이 메서드 하나로 통일해서, 검색 직후 캐싱해둔 매치를
 * 그대로 재사용한다(Nexon 재호출 없음).
 */
@Component
public class SearchMatchDetailCache {

    private final NexonMatchGateway nexonMatchGateway;

    public SearchMatchDetailCache(NexonMatchGateway nexonMatchGateway) {
        this.nexonMatchGateway = nexonMatchGateway;
    }

    @Cacheable(CacheNames.SEARCH_MATCH_DETAIL)
    public NexonMatchData getOrFetch(String matchId) {
        return nexonMatchGateway.fetchMatchDetail(matchId);
    }
}
