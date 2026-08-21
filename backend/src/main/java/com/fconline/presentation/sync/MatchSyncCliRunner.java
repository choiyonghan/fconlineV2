package com.fconline.presentation.sync;

import com.fconline.application.sync.MatchSyncFacade;
import com.fconline.application.sync.SyncProperties;
import com.fconline.application.sync.SyncSummary;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.infrastructure.meta.SpidMetaSyncAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * GitHub Actions cron 진입점. HTTP Controller와 대등한 "진입 어댑터"로 presentation 계층에 둔다.
 * 실행: java -jar app.jar --spring.profiles.active=sync --sync.match-type=CUSTOM
 *
 * v1은 429 소진처럼 사실상 실패한 실행도 워크플로우가 exit 0으로 종료해 CI에 드러나지 않았다
 * (analysis 6.9) — 이 러너는 실패한 유저가 하나라도 있으면 non-zero로 종료한다.
 */
@Profile("sync")
@Component
public class MatchSyncCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MatchSyncCliRunner.class);

    private final MatchSyncFacade matchSyncFacade;
    private final SpidMetaSyncAdapter spidMetaSyncAdapter;
    private final ApplicationContext context;
    private final SyncProperties syncProperties;

    public MatchSyncCliRunner(MatchSyncFacade matchSyncFacade, SpidMetaSyncAdapter spidMetaSyncAdapter,
                               ApplicationContext context, SyncProperties syncProperties) {
        this.matchSyncFacade = matchSyncFacade;
        this.spidMetaSyncAdapter = spidMetaSyncAdapter;
        this.context = context;
        this.syncProperties = syncProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        MatchType matchType = MatchType.valueOf(syncProperties.matchType());

        try {
            spidMetaSyncAdapter.syncAll();
        } catch (RuntimeException e) {
            // 선수 메타 갱신 실패는 치명적이지 않다 — 로그만 남기고 매치 동기화는 계속한다.
            log.warn("spid.json 동기화 실패, 매치 동기화는 계속 진행합니다.", e);
        }

        log.info("동기화 시작: matchType={}", matchType);
        SyncSummary summary = matchSyncFacade.sync(matchType);
        log.info("동기화 종료: {}", summary);

        final int exitCode = summary.failedUsers() > 0 ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
