package com.fconline.infrastructure.search;

import com.fconline.domain.match.gateway.NexonMatchData;
import com.fconline.domain.match.gateway.NexonMatchGateway;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.infrastructure.cache.CacheNames;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 유저 검색(app.search) 전용 — Nexon 호출 3종(닉네임→ouid, 최근 matchId 목록, matchId 1건 상세)을
 * 전부 여기서 캐싱해 감싼다.
 *
 * 추적 대상이 아닌 임의 유저는 DB에 아무것도 없어서(sync 배치가 손댄 적이 없음), 검색할 때마다
 * 이 셋을 Nexon에서 실시간으로 받아야 한다. SearchFacade의 조회 API(getOverall/getPlayers/
 * getShotHeatmap/...)가 전부 같은 (nickname, matchType, limit)을 받아 독립적으로 계산하는데
 * (요청 — SSR로 한 번에 묶지 않고 화면마다 API를 따로 호출하는 CSR 방식), 그 API들이 전부 이
 * 캐시를 거치므로 실제 Nexon 호출은 "그 nickname/matchId를 처음 보는 API"만 비용을 낸다.
 *
 * <b>반드시 별도 빈으로 분리해야 한다</b> — SearchFacade 안에 이 메서드들을 두고 facade가 자기
 * 자신을 호출(self-invocation)하면 Spring AOP 프록시를 우회해 {@code @Cacheable}이 조용히
 * 무시된다(CacheNames 클래스 주석에도 있는 잘 알려진 함정).
 *
 * <p><b>{@code sync = true}가 반드시 필요하다</b> — SearchFacade의 조회 API 7종(getOverall/
 * getPlayers/...)이 화면 하나당 API 하나씩(CSR, SearchFacade 클래스 주석 참고) 프론트에서
 * {@code Promise.all}로 "동시에" 호출된다. 처음 보는 닉네임이라 캐시가 비어있는 최초 검색에서
 * sync 없이는 이 7개 요청이 서로의 캐시 기록을 기다리지 않고 각자 findOuid/findRecentMatchIds/
 * getOrFetch를 따로 미스 처리해 Nexon을 중복 호출한다 — 최악의 경우 최대 7배까지 뻥튀기됨.
 * sync=true를 주면 같은 키에 대해 먼저 들어온 호출만 실제로 계산(Nexon 호출)하고 나머지는
 * 그 결과를 기다렸다 재사용한다(Spring의 캐시 스탬피드 방지 표준 옵션 — RedisCache/
 * ConcurrentMapCache 둘 다 지원).
 */
@Component
public class SearchMatchDetailCache {

    private final NexonMatchGateway nexonMatchGateway;

    public SearchMatchDetailCache(NexonMatchGateway nexonMatchGateway) {
        this.nexonMatchGateway = nexonMatchGateway;
    }

    /** 닉네임은 유료 변경이 아니면 거의 안 바뀌는 데이터라 TTL을 길게 잡는다(RedisCacheConfig). */
    @Cacheable(cacheNames = CacheNames.SEARCH_OUID, sync = true)
    public Optional<String> findOuid(String nickname) {
        return nexonMatchGateway.findOuid(nickname);
    }

    /** 새 매치가 방금 끝났을 수도 있어 다른 조회성 캐시와 같은 TTL(30분)로 둔다. */
    @Cacheable(cacheNames = CacheNames.SEARCH_RECENT_MATCH_IDS, sync = true)
    public List<String> findRecentMatchIds(String ouid, MatchType matchType, int limit) {
        return nexonMatchGateway.findRecentMatchIds(ouid, matchType, limit);
    }

    /** 매치 결과는 한 번 끝나면 다시 안 바뀌는 데이터라 다른 조회성 캐시보다 훨씬 길게(24시간) 잡는다. */
    @Cacheable(cacheNames = CacheNames.SEARCH_MATCH_DETAIL, sync = true)
    public NexonMatchData getOrFetch(String matchId) {
        return nexonMatchGateway.fetchMatchDetail(matchId);
    }
}
