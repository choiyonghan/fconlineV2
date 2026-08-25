package com.fconline.app.dashboard.runner;

import com.fconline.app.dashboard.dto.DashboardSnapshotFile;
import com.fconline.app.dashboard.facade.DashboardSnapshotBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * GitHub Actions cron 진입점(dashboard-snapshot.yml). InsightSnapshotCliRunner와 대등한 배치
 * 러너로, 매일 아침 그날의 매치 동기화 직후 실행되어 9명 트래커 유저의 요약 + AI 랭킹을
 * data/dashboard-snapshot.json 파일로 써낸다. 워크플로우가 이 파일을 커밋·푸시하고, 프론트
 * (site-root/index.html)는 백엔드를 거치지 않고 raw.githubusercontent.com에서 이 파일을 직접
 * 읽는다 — DB에 별도 테이블을 두지 않고 리포지토리 자체를 캐시 저장소로 쓴다.
 * 실행: java -jar app.jar --spring.profiles.active=dashboard-snapshot
 */
@Profile("dashboard-snapshot")
@Component
public class DashboardSnapshotCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DashboardSnapshotCliRunner.class);

    private static final Path OUTPUT_PATH = Path.of("data", "dashboard-snapshot.json");

    private final DashboardSnapshotBuilder dashboardSnapshotBuilder;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    public DashboardSnapshotCliRunner(DashboardSnapshotBuilder dashboardSnapshotBuilder, ObjectMapper objectMapper,
                                       ApplicationContext context) {
        this.dashboardSnapshotBuilder = dashboardSnapshotBuilder;
        this.objectMapper = objectMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        final int exitCode = runAndCollectExitCode();
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int runAndCollectExitCode() {
        try {
            Files.createDirectories(OUTPUT_PATH.getParent());
            DashboardSnapshotFile snapshot = dashboardSnapshotBuilder.build();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_PATH.toFile(), snapshot);
            log.info("대시보드 스냅샷 완료: 유저 {}명 → {} (aiRankingFailed={})",
                    snapshot.users().size(), OUTPUT_PATH, snapshot.aiRankingFailed());
            return 0;
        } catch (IOException | RuntimeException e) {
            log.error("대시보드 스냅샷 생성 실패", e);
            return 1;
        }
    }
}
