package com.fconline.infrastructure.search;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 검색 화면(site-root/search.html)의 로딩바용 — "매치 100건 중 몇 건째 받아왔는지"를 인메모리로
 * 추적한다. limit이 최대 100까지라 매치 1건당 Nexon 호출 300ms 딜레이를 감안하면 검색 한 번에
 * 최대 30~40초가 걸릴 수 있어서(요청), 프론트가 이 상태를 폴링해 진행률을 보여준다.
 *
 * <p>DB나 Redis가 아니라 순수 인메모리다 — 로딩바는 "지금 이 서버 인스턴스가 실제로 몇 건째
 * Nexon을 호출했는지"를 보여주면 충분하고, 재시작하면 사라져도 무방한 일회성 UI 상태라 굳이
 * 영속화할 이유가 없다(Render 무료 티어는 어차피 인스턴스 1개).
 *
 * <p>키는 "닉네임|매치타입"(SearchFacade.progressKey) — 검색 폼 제출 시점엔 프론트가 아직
 * ouid를 모르기 때문에(7개 API 응답이 오기 전) ouid가 아니라 사용자가 입력한 닉네임으로 키를
 * 맞춘다. fetchedIds를 Set으로 둔 이유: SearchFacade의 조회 API 7종이 전부 같은 매치 목록을
 * 각자 순회하며 markFetched를 부르므로(CSR, SearchFacade 클래스 주석 참고), 같은 matchId를
 * 여러 스레드가 중복으로 기록해도 Set이 자동으로 한 건으로 흡수한다 — 정확히 몇 번째 스레드가
 * "진짜로" Nexon을 호출했는지 구분할 필요가 없다.
 */
@Component
public class SearchProgressTracker {

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final Map<String, Progress> byKey = new ConcurrentHashMap<>();

    /** 매치 목록(findRecentMatchIds) 조회가 끝나 전체 건수를 알게 된 시점에 호출한다. 같은 검색을
     * 향한 7개 API가 거의 동시에 각자 호출하므로, 이미 같은 total로 진행 중이면 새로 만들지 않고
     * 기존 진행 상황(이미 fetch된 매치들)을 그대로 이어쓴다. */
    public void start(String key, int total) {
        sweepStale();
        byKey.compute(key, (k, existing) -> (existing != null && existing.total == total) ? existing : new Progress(total));
    }

    /** matchId 1건의 상세를 가져온(캐시 히트 포함) 직후 호출한다. */
    public void markFetched(String key, String matchId) {
        Progress p = byKey.get(key);
        if (p != null) {
            p.fetchedIds.add(matchId);
        }
    }

    /** [fetched, total] — 아직 시작 전(닉네임을 처음 조회 중이거나, 이미 오래전에 끝나 정리된 경우)이면 empty. */
    public Optional<int[]> snapshot(String key) {
        Progress p = byKey.get(key);
        return p == null ? Optional.empty() : Optional.of(new int[]{p.fetchedIds.size(), p.total});
    }

    /** 매 start() 호출 시 5분 넘게 방치된 항목을 정리한다 — 폴링이 중간에 끊긴(사용자가 페이지를
     * 벗어난) 검색이 계속 메모리에 남지 않게 하는 최소한의 청소. */
    private void sweepStale() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        byKey.entrySet().removeIf(e -> e.getValue().startedAt.isBefore(cutoff));
    }

    private static final class Progress {
        final int total;
        final Instant startedAt = Instant.now();
        final Set<String> fetchedIds = ConcurrentHashMap.newKeySet();

        Progress(int total) {
            this.total = total;
        }
    }
}
