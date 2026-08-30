package com.fconline.infrastructure.cache;

import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.facade.RecentMatchMapper;
import com.fconline.domain.match.repository.MatchDetailRepository;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.RecentMatchRaw;
import com.fconline.domain.user.UserTeamPeriod;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordFacade.getRecentMatches 전용 캐시 보조 빈. 원시 매치 목록 조회 + 팀 기간 조회(둘 다 DB
 * 왕복) + RecentMatchResponse 매핑까지 전부 여기서 하고 최종 결과를 캐싱한다 — 처음엔 원시 매치
 * 목록만 캐싱했는데, 팀 기간 조회가 캐시 밖(RecordFacade)에 남아있어서 캐시가 히트해도 매번 DB를
 * 한 번 더 타는 바람에 체감 속도가 거의 안 줄었다(실측: 캐시 히트해도 700ms~1.2s, 다른 캐시들의
 * 190ms대와 차이). 이제 이 메서드 하나가 캐시 히트하면 DB 왕복이 완전히 0번이 된다.
 *
 * Page&lt;RecentMatchResponse&gt;를 그대로 {@code @Cacheable}에 태우면 CachedPage 클래스
 * 주석에 적은 이유로 역직렬화가 깨지므로, CachedPage로 감싸 캐싱하고 RecordFacade가 필요할 때
 * PageImpl로 복원한다. 별도 빈으로 분리한 이유는 RecordFacade 안에서 이 메서드를 호출하는 방식
 * (self-invocation)이면 Spring의 캐시 프록시를 안 거쳐서 캐시가 아예 안 걸리기 때문이다.
 */
@Component
public class RecentMatchesPageCache {

    private static final Logger log = LoggerFactory.getLogger(RecentMatchesPageCache.class);

    private final MatchDetailRepository matchDetailRepository;
    private final RecentMatchMapper recentMatchMapper;

    public RecentMatchesPageCache(MatchDetailRepository matchDetailRepository, RecentMatchMapper recentMatchMapper) {
        this.matchDetailRepository = matchDetailRepository;
        this.recentMatchMapper = recentMatchMapper;
    }

    /** TTL 5분(RedisCacheConfig) — RecordFacade.getOverallRecord 주석의 "5분 지연 허용" 전제와 동일. */
    @Cacheable(CacheNames.RECENT_MATCHES)
    @Transactional(readOnly = true)
    public CachedPage<RecentMatchResponse> fetch(String ouid, MatchType matchType, Instant from, Instant to,
                                                  Pageable pageable) {
        // TEMP DIAGNOSTIC(요청) — 캐시 히트면 이 로그가 아예 안 찍혀야 정상이다. 반복 호출에도
        // 계속 찍히면 캐시가 실제로는 매번 미스나고 있다는 뜻(원인 파악되면 곧 제거할 예정).
        long t0 = System.nanoTime();
        log.warn("[TEMP] RecentMatchesPageCache.fetch 실제 실행됨(캐시 미스) ouid={} from={} to={} pageable={}",
                ouid, from, to, pageable);
        Page<RecentMatchRaw> page = matchDetailRepository.findRecentByOuid(ouid, matchType, from, to, pageable);
        log.warn("[TEMP] findRecentByOuid 소요 {}ms", (System.nanoTime() - t0) / 1_000_000);

        Set<String> ouidsNeeded = new HashSet<>();
        ouidsNeeded.add(ouid);
        page.forEach(r -> ouidsNeeded.add(r.opponentOuid()));
        Map<String, List<UserTeamPeriod>> periodsByOuid = recentMatchMapper.teamPeriodsByOuid(ouidsNeeded);
        log.warn("[TEMP] teamPeriodsByOuid까지 누적 소요 {}ms", (System.nanoTime() - t0) / 1_000_000);

        Page<RecentMatchResponse> mapped = page.map(raw -> recentMatchMapper.toRecentMatchResponse(ouid, raw, periodsByOuid));
        log.warn("[TEMP] fetch 전체 소요 {}ms", (System.nanoTime() - t0) / 1_000_000);
        return CachedPage.from(mapped);
    }
}
