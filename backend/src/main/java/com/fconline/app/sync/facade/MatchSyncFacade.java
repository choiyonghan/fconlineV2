package com.fconline.app.sync.facade;

import com.fconline.app.sync.config.SyncProperties;
import com.fconline.app.sync.dto.SyncSummary;
import com.fconline.domain.match.service.MatchDomainService;
import com.fconline.domain.match.service.MatchIngestionService;
import com.fconline.domain.match.repository.MatchRepository;
import com.fconline.domain.match.vo.OpponentTally;
import com.fconline.domain.match.gateway.NexonMatchData;
import com.fconline.domain.match.gateway.NexonMatchGateway;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.season.Season;
import com.fconline.domain.season.repository.SeasonRepository;
import com.fconline.domain.shared.KstZone;
import com.fconline.domain.shared.exception.NexonApiException;
import com.fconline.domain.streak.service.StreakDomainService;
import com.fconline.domain.user.TrackedUser;
import com.fconline.domain.user.repository.TrackedUserRepository;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 동기화 유스케이스 오케스트레이션 (application 계층 = Facade).
 * v1의 fetch_and_store.js/fetch_official.js가 하던 일을 도메인 서비스 조합으로 대체한다.
 * 조회 API와 완전히 같은 도메인 서비스(MatchIngestionService, StreakDomainService)를
 * 호출하므로 로직 중복이 구조적으로 발생할 수 없다(analysis 6.7 해결 지점).
 */
@Component
public class MatchSyncFacade {

    private static final Logger log = LoggerFactory.getLogger(MatchSyncFacade.class);

    private final TrackedUserRepository trackedUserRepository;
    private final SeasonRepository seasonRepository;
    private final NexonMatchGateway nexonMatchGateway;
    private final MatchRepository matchRepository;
    private final MatchIngestionService matchIngestionService;
    private final MatchDomainService matchDomainService;
    private final StreakDomainService streakDomainService;
    private final SyncProperties syncProperties;

    public MatchSyncFacade(TrackedUserRepository trackedUserRepository, SeasonRepository seasonRepository,
                            NexonMatchGateway nexonMatchGateway, MatchRepository matchRepository,
                            MatchIngestionService matchIngestionService, MatchDomainService matchDomainService,
                            StreakDomainService streakDomainService, SyncProperties syncProperties) {
        this.trackedUserRepository = trackedUserRepository;
        this.seasonRepository = seasonRepository;
        this.nexonMatchGateway = nexonMatchGateway;
        this.matchRepository = matchRepository;
        this.matchIngestionService = matchIngestionService;
        this.matchDomainService = matchDomainService;
        this.streakDomainService = streakDomainService;
        this.syncProperties = syncProperties;
    }

    public SyncSummary sync(MatchType matchType) {
        List<TrackedUser> targets = trackedUserRepository.findByTrackedTrueOrderByDisplayOrderAsc();
        log.info("매치 동기화 대상: {}명 (matchType={})", targets.size(), matchType);

        int fetched = 0;
        int inserted = 0;
        int failed = 0;

        for (int i = 0; i < targets.size(); i++) {
            TrackedUser user = targets.get(i);
            try {
                int insertedCount = syncUser(user, matchType);
                inserted += insertedCount;
                fetched++;
                log.info("[{}/{}] ouid={} 완료 (신규 {}건 저장)", i + 1, targets.size(), user.getOuid(), insertedCount);
            } catch (NexonApiException e) {
                // v1은 이 상황에서도 워크플로우가 exit 0으로 성공 처리되어 실패가 드러나지 않았다
                // (analysis 6.9) — v2는 실패를 세어 배치 종료 코드에 반영한다 (MatchSyncCliRunner 참고).
                failed++;
                log.error("[{}/{}] ouid={} 실패: matchType={}", i + 1, targets.size(), user.getOuid(), matchType, e);
            }
        }

        recalculateStreaksForCurrentSeason(targets, matchType);

        return new SyncSummary(matchType, fetched, inserted, failed);
    }

    private int syncUser(TrackedUser user, MatchType matchType) {
        List<String> matchIds = nexonMatchGateway.findRecentMatchIds(
                user.getOuid(), matchType, syncProperties.matchFetchLimit());

        // v1은 매치 ID마다 개별 SELECT를 했다(최대 900회/실행) — IN절 1회 배치 조회로 대체(analysis 6.10).
        Set<String> existingIds = matchRepository.findExistingMatchIds(matchIds);
        int newCount = matchIds.size() - existingIds.size();
        log.info("ouid={} 매치 {}건 조회, 신규 {}건 처리 시작", user.getOuid(), matchIds.size(), newCount);

        int insertedCount = 0;
        for (String matchId : matchIds) {
            if (existingIds.contains(matchId)) {
                continue;
            }
            NexonMatchData data = nexonMatchGateway.fetchMatchDetail(matchId);
            matchIngestionService.ingest(data);
            insertedCount++;
            // 매치 1건당 Nexon API 요청 딜레이(기본 300ms) + 네트워크 왕복이 있어 신규 매치가
            // 많으면 수십 초~분 단위로 걸린다 — 이 사이에 로그가 없으면 멈춘 것처럼 보인다.
            log.info("  matchId={} 저장 완료 ({}/{})", matchId, insertedCount, newCount);
        }
        return insertedCount;
    }

    /** 진행 중인 시즌이 있으면 이번 동기화로 새 경기가 들어온 상대들의 스트릭을 재계산한다. */
    private void recalculateStreaksForCurrentSeason(List<TrackedUser> targets, MatchType matchType) {
        Season currentSeason = seasonRepository.findCurrent(LocalDate.now(KstZone.ID)).orElse(null);
        if (currentSeason == null) {
            log.warn("진행 중인 시즌이 설정되어 있지 않아 스트릭 재계산을 건너뜁니다.");
            return;
        }

        for (TrackedUser user : targets) {
            List<OpponentTally> tallies = matchDomainService.opponentTallies(
                    user.getOuid(), matchType, currentSeason.startInstant(), currentSeason.endInstantExclusiveOrNull());

            Set<String> opponents = new HashSet<>();
            tallies.forEach(tally -> opponents.add(tally.opponentOuid()));

            for (String opponentOuid : opponents) {
                streakDomainService.recalculate(user.getOuid(), opponentOuid, matchType, currentSeason.getId(),
                        currentSeason.startInstant(), currentSeason.endInstantExclusiveOrNull());
            }
        }
    }
}
