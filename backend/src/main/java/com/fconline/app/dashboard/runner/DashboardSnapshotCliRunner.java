package com.fconline.app.dashboard.runner;

import com.fconline.app.dashboard.dto.DashboardSnapshotFile;
import com.fconline.app.dashboard.facade.DashboardSnapshotBuilder;
import com.fconline.domain.match.vo.MatchType;
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
 * CUSTOM/OFFICIAL 두 스코프 각각 data/dashboard-snapshot.json · data/dashboard-snapshot-official.json
 * 파일로 써낸다(요청, 2026-09-04 확대 — 원래는 CUSTOM 파일 하나뿐이었다). 워크플로우가 두 파일을
 * 커밋·푸시하고, 프론트(site-root/report.html)는 백엔드를 거치지 않고 raw.githubusercontent.com에서
 * 현재 매치타입 토글에 맞는 파일을 직접 읽는다 — DB에 별도 테이블을 두지 않고 리포지토리 자체를
 * 캐시 저장소로 쓴다.
 * 실행: java -jar app.jar --spring.profiles.active=dashboard-snapshot
 */
@Profile("dashboard-snapshot")
@Component
public class DashboardSnapshotCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DashboardSnapshotCliRunner.class);

    private static final Path CUSTOM_OUTPUT_PATH = Path.of("data", "dashboard-snapshot.json");
    private static final Path OFFICIAL_OUTPUT_PATH = Path.of("data", "dashboard-snapshot-official.json");

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

    /** 두 스코프를 각각 독립적으로 시도한다 — 한쪽이 실패해도 다른 쪽 파일은 정상 갱신되게. */
    private int runAndCollectExitCode() {
        boolean customOk = buildAndWrite(MatchType.CUSTOM, CUSTOM_OUTPUT_PATH);
        boolean officialOk = buildAndWrite(MatchType.OFFICIAL, OFFICIAL_OUTPUT_PATH);
        return (customOk && officialOk) ? 0 : 1;
    }

    private boolean buildAndWrite(MatchType matchType, Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            DashboardSnapshotFile snapshot = dashboardSnapshotBuilder.build(matchType);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), snapshot);
            log.info("대시보드 스냅샷 완료: matchType={}, 유저 {}명 → {} (aiRankingFailed={})",
                    matchType, snapshot.users().size(), outputPath, snapshot.aiRankingFailed());
            return true;
        } catch (IOException | RuntimeException e) {
            log.error("대시보드 스냅샷 생성 실패: matchType={}", matchType, e);
            return false;
        }
    }
}
