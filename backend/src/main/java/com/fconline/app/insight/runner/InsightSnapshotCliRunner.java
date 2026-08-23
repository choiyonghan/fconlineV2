package com.fconline.app.insight.runner;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.insight.dto.InsightSnapshotContent;
import com.fconline.app.insight.facade.InsightSnapshotBuilder;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.season.Season;
import com.fconline.domain.user.TrackedUser;
import com.fconline.domain.user.repository.TrackedUserRepository;
import com.fconline.infrastructure.insight.GithubInsightSnapshotFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * GitHub Actions cron 진입점(insight-snapshot.yml). MatchSyncCliRunner와 대등한 배치 러너로,
 * 매일 아침 그날의 매치 동기화 직후 실행되어 추적 대상 유저 × 매치타입마다 인사이트 스냅샷을
 * data/insight-snapshots/*.json 파일로 써낸다. 워크플로우가 이 파일들을 커밋·푸시하고,
 * 백엔드는 GithubInsightSnapshotClient로 raw.githubusercontent.com에서 그대로 읽는다 —
 * DB에 별도 테이블을 두지 않고 리포지토리 자체를 캐시 저장소로 쓴다.
 * 실행: java -jar app.jar --spring.profiles.active=insight-snapshot
 */
@Profile("insight-snapshot")
@Component
public class InsightSnapshotCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InsightSnapshotCliRunner.class);

    private static final Path OUTPUT_DIR = Path.of("data", "insight-snapshots");

    private final TrackedUserRepository trackedUserRepository;
    private final SeasonRangeResolver seasonRangeResolver;
    private final InsightSnapshotBuilder insightSnapshotBuilder;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    public InsightSnapshotCliRunner(TrackedUserRepository trackedUserRepository,
                                     SeasonRangeResolver seasonRangeResolver,
                                     InsightSnapshotBuilder insightSnapshotBuilder,
                                     ObjectMapper objectMapper,
                                     ApplicationContext context) {
        this.trackedUserRepository = trackedUserRepository;
        this.seasonRangeResolver = seasonRangeResolver;
        this.insightSnapshotBuilder = insightSnapshotBuilder;
        this.objectMapper = objectMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        List<TrackedUser> targets = trackedUserRepository.findByTrackedTrueOrderByDisplayOrderAsc();
        log.info("인사이트 스냅샷 대상: {}명 x {}개 매치타입 → {}", targets.size(), MatchType.values().length, OUTPUT_DIR);

        int success = 0;
        int failed = 0;

        for (MatchType matchType : MatchType.values()) {
            for (TrackedUser user : targets) {
                try {
                    writeSnapshot(user.getOuid(), matchType);
                    success++;
                } catch (Exception e) {
                    failed++;
                    log.error("인사이트 스냅샷 실패: ouid={}, matchType={}", user.getOuid(), matchType, e);
                }
            }
        }

        log.info("인사이트 스냅샷 종료: 성공 {}건, 실패 {}건", success, failed);
        final int exitCode = failed > 0 ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private void writeSnapshot(String ouid, MatchType matchType) throws IOException {
        Season season = seasonRangeResolver.resolve(null);
        InsightSnapshotContent content = insightSnapshotBuilder.build(ouid, matchType, season.getId());

        GithubInsightSnapshotFile file = new GithubInsightSnapshotFile(
                ouid, matchType.name(), season.getId(), Instant.now(),
                content.summaryText(), content.opponentDetailByNickname());

        Path path = OUTPUT_DIR.resolve(ouid + "_" + matchType.code() + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), file);
        log.info("  ouid={} matchType={} → {}", ouid, matchType, path);
    }
}
