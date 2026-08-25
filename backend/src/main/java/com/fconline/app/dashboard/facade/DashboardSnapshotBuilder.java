package com.fconline.app.dashboard.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fconline.app.dashboard.dto.DashboardCombo;
import com.fconline.app.dashboard.dto.DashboardRankingEntry;
import com.fconline.app.dashboard.dto.DashboardScopeSummary;
import com.fconline.app.dashboard.dto.DashboardSnapshotFile;
import com.fconline.app.dashboard.dto.DashboardTopPlayer;
import com.fconline.app.dashboard.dto.DashboardUserSnapshot;
import com.fconline.app.dashboard.support.ApproxXgTable;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.ShotHeatmapResponse;
import com.fconline.app.record.dto.ShotPointResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.app.record.facade.RecordFacade;
import com.fconline.app.season.dto.SeasonResponse;
import com.fconline.app.season.facade.SeasonFacade;
import com.fconline.app.user.dto.TrackedUserResponse;
import com.fconline.app.user.facade.UserFacade;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.infrastructure.gemini.GeminiApiClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 대시보드(site-root/index.html) 스냅샷 조립. 매일 아침 배치({@code dashboard-snapshot}
 * 프로파일, DashboardSnapshotCliRunner)가 호출해 data/dashboard-snapshot.json을 만든다 —
 * 백엔드가 잠들어 있어도(Render 무료 티어 콜드 스타트) 대시보드는 이 정적 파일만 읽어 즉시
 * 뜨게 하려는 목적. 기존 RecordFacade/SeasonFacade/UserFacade만 재사용하고 새 DB 테이블/
 * 마이그레이션은 없다.
 */
@Component
public class DashboardSnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(DashboardSnapshotBuilder.class);

    private static final int TOP_N = 3;
    /** getAssistChains: 양방향(A→B+B→A) 합산 TOP1을 정확히 뽑으려고 넓게 요청한다(RecordFacade 주석 참고). */
    private static final int ASSIST_CHAIN_LIMIT = 200;

    private final UserFacade userFacade;
    private final SeasonFacade seasonFacade;
    private final RecordFacade recordFacade;
    private final GeminiApiClient geminiApiClient;
    private final ObjectMapper objectMapper;

    public DashboardSnapshotBuilder(UserFacade userFacade, SeasonFacade seasonFacade, RecordFacade recordFacade,
                                     GeminiApiClient geminiApiClient, ObjectMapper objectMapper) {
        this.userFacade = userFacade;
        this.seasonFacade = seasonFacade;
        this.recordFacade = recordFacade;
        this.geminiApiClient = geminiApiClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DashboardSnapshotFile build() {
        List<TrackedUserResponse> users = userFacade.listTrackedUsers();
        SeasonResponse currentSeason = resolveCurrentSeason();

        // SeasonRangeResolver.resolve(null)은 "전체 기간"이 아니라 "오늘 기준 진행 중인 시즌"으로
        // 해석된다(app.common.SeasonRangeResolver 참고) — 이 앱은 어디서나 항상 시즌 경계로
        // 스코프되는 구조라 "모두의 커스텀"도 currentSeason.id()를 명시적으로 넘긴다. 즉 두
        // 스코프는 같은 현재시즌 기간 안에서 matchType만 다르다(CUSTOM vs OFFICIAL).
        Map<String, DashboardScopeSummary> customByOuid = buildScope(users, MatchType.CUSTOM, currentSeason.id());
        Map<String, DashboardScopeSummary> seasonByOuid = buildScope(users, MatchType.OFFICIAL, currentSeason.id());

        Map<String, DashboardUserSnapshot> snapshots = new LinkedHashMap<>();
        for (TrackedUserResponse u : users) {
            snapshots.put(u.ouid(), new DashboardUserSnapshot(
                    u.ouid(), u.nickname(), customByOuid.get(u.ouid()), seasonByOuid.get(u.ouid())));
        }

        RankingResult ranking = buildRanking(users, customByOuid, seasonByOuid);

        return new DashboardSnapshotFile(Instant.now(), currentSeason.name(),
                ranking.failed(), ranking.note(), ranking.entries(), snapshots);
    }

    private SeasonResponse resolveCurrentSeason() {
        List<SeasonResponse> seasons = seasonFacade.listSeasons();
        return seasons.stream().filter(SeasonResponse::current).findFirst()
                .orElseGet(() -> {
                    log.warn("current=true인 시즌이 없어 최신 시즌으로 대체합니다.");
                    return seasons.get(0);
                });
    }

    // ---------------- 스코프(커스텀/시즌) 서머리 ----------------

    private Map<String, DashboardScopeSummary> buildScope(List<TrackedUserResponse> users, MatchType matchType,
                                                            Long seasonId) {
        Map<String, ShotHeatmapResponse> heatmapByOuid = new HashMap<>();
        Map<String, ShotHeatmapResponse> concededByOuid = new HashMap<>();
        ApproxXgTable xgTable = new ApproxXgTable();

        for (TrackedUserResponse u : users) {
            ShotHeatmapResponse heatmap = recordFacade.getShotHeatmap(u.ouid(), matchType, seasonId, false);
            heatmapByOuid.put(u.ouid(), heatmap);
            xgTable.addAll(heatmap.points());
            concededByOuid.put(u.ouid(), recordFacade.getConcededShotHeatmap(u.ouid(), matchType, seasonId));
        }
        xgTable.build();

        Map<String, DashboardScopeSummary> result = new LinkedHashMap<>();
        for (TrackedUserResponse u : users) {
            result.put(u.ouid(), buildScopeSummary(u.ouid(), matchType, seasonId,
                    heatmapByOuid.get(u.ouid()), concededByOuid.get(u.ouid()), xgTable));
        }
        return result;
    }

    /**
     * 지표 공식은 report.js의 renderPlayStyle(플레이 성향 카드)과 동일하게 맞춘다 — 클래스
     * 주석(DashboardScopeSummary) 참고.
     */
    private DashboardScopeSummary buildScopeSummary(String ouid, MatchType matchType, Long seasonId,
                                                      ShotHeatmapResponse heatmap, ShotHeatmapResponse conceded,
                                                      ApproxXgTable xgTable) {
        OverallRecordResponse overall = recordFacade.getOverallRecord(ouid, matchType, seasonId);
        int games = overall.tally().win() + overall.tally().draw() + overall.tally().lose();

        List<ShotPointResponse> points = heatmap.points();
        List<ShotPointResponse> concededPoints = conceded.points();

        double expectedGoalsFor = xgTable.expectedGoals(points);
        long actualGoals = points.stream().filter(ShotPointResponse::goal).count();
        double expectedGoalsAgainst = xgTable.expectedGoals(concededPoints);
        int concededSampleGames = (int) concededPoints.stream().map(ShotPointResponse::matchId).distinct().count();

        int low = (int) overall.lowPossessionGames();
        int high = (int) overall.highPossessionGames();
        int balanced = Math.max(games - low - high, 0);

        List<AssistChainResponse> chains = recordFacade.getAssistChains(ouid, matchType, seasonId, ASSIST_CHAIN_LIMIT);
        List<TopPlayerResponse> players = recordFacade.getAllPlayers(ouid, matchType, seasonId);

        return new DashboardScopeSummary(
                games, overall.tally().win(), overall.tally().draw(), overall.tally().lose(),
                games == 0 ? 0 : overall.tally().goalsFor() / (double) games,
                games == 0 ? 0 : expectedGoalsFor / games,
                actualGoals - expectedGoalsFor,
                games == 0 ? 0 : points.size() / (double) games,
                games == 0 ? 0 : overall.tally().goalsAgainst() / (double) games,
                concededSampleGames == 0 ? null : expectedGoalsAgainst / concededSampleGames,
                concededSampleGames,
                (int) overall.cleanSheets(), pct(overall.cleanSheets(), games),
                (int) overall.multiConcededGames(), pct(overall.multiConcededGames(), games),
                low, pct(low, games),
                balanced, pct(balanced, games),
                high, pct(high, games),
                overall.possessionAverage(),
                overall.averageRating(),
                topCombo(chains),
                topBy(players, TopPlayerResponse::goals),
                topBy(players, TopPlayerResponse::assists),
                topBy(players, p -> p.goals() + p.assists()),
                topBy(players, p -> p.tackles() + p.intercepts()),
                topBy(players, TopPlayerResponse::saves)
        );
    }

    private static double pct(long count, int games) {
        return games == 0 ? 0 : count * 100.0 / games;
    }

    /** "환상의 콤비" — 양방향(A→B+B→A) 합산 골 최다 조합 1위. report.js의 topAssistDuos와 동일한 병합 방식. */
    private DashboardCombo topCombo(List<AssistChainResponse> chains) {
        record Duo(String nameA, String nameB, long goals) {
        }
        Map<String, Duo> byPair = new LinkedHashMap<>();
        for (AssistChainResponse c : chains) {
            String key = c.assisterSpId().compareTo(c.scorerSpId()) <= 0
                    ? c.assisterSpId() + "|" + c.scorerSpId()
                    : c.scorerSpId() + "|" + c.assisterSpId();
            Duo prev = byPair.get(key);
            byPair.put(key, new Duo(c.assisterName(), c.scorerName(), (prev == null ? 0 : prev.goals()) + c.goals()));
        }
        return byPair.values().stream()
                .max(Comparator.comparingLong(Duo::goals))
                .map(d -> new DashboardCombo(d.nameA(), d.nameB(), d.goals()))
                .orElse(null);
    }

    private List<DashboardTopPlayer> topBy(List<TopPlayerResponse> players, java.util.function.ToIntFunction<TopPlayerResponse> extractor) {
        return players.stream()
                .map(p -> new DashboardTopPlayer(p.playerName(), extractor.applyAsInt(p)))
                .filter(t -> t.value() > 0)
                .sorted(Comparator.comparingInt(DashboardTopPlayer::value).reversed())
                .limit(TOP_N)
                .toList();
    }

    // ---------------- AI 랭킹 ----------------

    private record RankingResult(boolean failed, String note, List<DashboardRankingEntry> entries) {
    }

    private static final String RANKING_SYSTEM_INSTRUCTION = """
            너는 FC 온라인(피파온라인) 친구 그룹의 전적 데이터를 분석하는 어시스턴트다. 아래 유저들의
            "모두의 커스텀"(전체 기간 커스텀 매치)과 "현재시즌"(공식전) 통계를 보고 종합 실력 기준
            1위부터 꼴찌까지 순위를 매겨라. 승률과 득실차를 가장 중요하게 보고, 결정력(실제 득점-xG),
            평균 평점, 클린시트 비율도 참고해라. 표본 경기 수가 너무 적은(예: 5경기 미만) 유저는
            신뢰도가 낮다는 점도 감안해라.
            반드시 순수 JSON 배열로만 답하라. 코드블록, 마크다운, 다른 설명 문장을 절대 섞지 마라.
            형식: [{"nickname":"정확히 입력받은 닉네임 그대로","rank":1,"reason":"2~3문장 한국어 사유"}, ...]
            입력받은 유저 수와 정확히 같은 개수의 항목을, rank는 1부터 그 수까지 중복 없이 채워라.
            """;

    private RankingResult buildRanking(List<TrackedUserResponse> users,
                                        Map<String, DashboardScopeSummary> customByOuid,
                                        Map<String, DashboardScopeSummary> seasonByOuid) {
        try {
            String prompt = buildRankingPrompt(users, customByOuid, seasonByOuid);
            String raw = geminiApiClient.askJson(RANKING_SYSTEM_INSTRUCTION, prompt);
            List<DashboardRankingEntry> parsed = parseRanking(raw, users);
            return new RankingResult(false, null, parsed);
        } catch (Exception e) {
            log.error("AI 랭킹 호출/파싱 실패 — 승률 기준 정렬로 대체합니다.", e);
            return new RankingResult(true, "AI 랭킹 호출에 실패해 승률·득실차 기준으로 대체했습니다.",
                    fallbackRanking(users, customByOuid, seasonByOuid));
        }
    }

    private String buildRankingPrompt(List<TrackedUserResponse> users,
                                       Map<String, DashboardScopeSummary> customByOuid,
                                       Map<String, DashboardScopeSummary> seasonByOuid) {
        StringBuilder sb = new StringBuilder();
        sb.append("유저 ").append(users.size()).append("명:\n\n");
        for (TrackedUserResponse u : users) {
            sb.append("- 닉네임: ").append(u.nickname()).append("\n");
            appendScopeLine(sb, "  모두의 커스텀", customByOuid.get(u.ouid()));
            appendScopeLine(sb, "  현재시즌", seasonByOuid.get(u.ouid()));
            sb.append("\n");
        }
        return sb.toString();
    }

    private void appendScopeLine(StringBuilder sb, String label, DashboardScopeSummary s) {
        if (s == null || s.games() == 0) {
            sb.append(label).append(": 표본 없음\n");
            return;
        }
        double winRate = s.games() == 0 ? 0 : s.wins() * 100.0 / s.games();
        sb.append(label).append(": ").append(s.games()).append("전 ")
                .append(s.wins()).append("승 ").append(s.draws()).append("무 ").append(s.losses())
                .append("패 (승률 ").append(String.format("%.1f", winRate)).append("%), ")
                .append("평균득점 ").append(String.format("%.2f", s.avgGoalsFor())).append(", ")
                .append("평균실점 ").append(String.format("%.2f", s.avgGoalsAgainst())).append(", ")
                .append("결정력 ").append(String.format("%+.1f", s.finishing())).append(", ")
                .append("평균평점 ").append(String.format("%.2f", s.avgRating())).append(", ")
                .append("클린시트 ").append(String.format("%.0f", s.cleanSheetPct())).append("%\n");
    }

    private List<DashboardRankingEntry> parseRanking(String raw, List<TrackedUserResponse> users) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode array = root.isArray() ? root : root.path("ranking");
        if (!array.isArray() || array.size() != users.size()) {
            throw new IllegalStateException("Gemini 랭킹 응답 개수가 유저 수와 다릅니다: " + raw);
        }

        Map<String, String> ouidByNickname = users.stream()
                .collect(Collectors.toMap(TrackedUserResponse::nickname, TrackedUserResponse::ouid));
        List<DashboardRankingEntry> entries = new ArrayList<>();
        Set<Integer> seenRanks = new HashSet<>();
        Set<String> seenNicknames = new HashSet<>();
        for (JsonNode node : array) {
            String nickname = node.path("nickname").asText(null);
            int rank = node.path("rank").asInt(-1);
            String reason = node.path("reason").asText("");
            String ouid = ouidByNickname.get(nickname);
            if (ouid == null || rank < 1 || rank > users.size() || !seenRanks.add(rank) || !seenNicknames.add(nickname)) {
                throw new IllegalStateException("Gemini 랭킹 응답이 유효하지 않습니다(닉네임/순위 불일치): " + raw);
            }
            entries.add(new DashboardRankingEntry(ouid, nickname, rank, reason));
        }
        entries.sort(Comparator.comparingInt(DashboardRankingEntry::rank));
        return entries;
    }

    /** Gemini 호출/파싱 실패 시 결정론적 대체 — 현재시즌 승률(표본 없으면 커스텀 승률) → 득실차 순. */
    private List<DashboardRankingEntry> fallbackRanking(List<TrackedUserResponse> users,
                                                          Map<String, DashboardScopeSummary> customByOuid,
                                                          Map<String, DashboardScopeSummary> seasonByOuid) {
        record Scored(TrackedUserResponse user, double winRate, double avgGoalDiff) {
        }
        List<Scored> scored = users.stream().map(u -> {
            DashboardScopeSummary s = seasonByOuid.get(u.ouid());
            if (s == null || s.games() == 0) s = customByOuid.get(u.ouid());
            if (s == null || s.games() == 0) return new Scored(u, 0, 0);
            double winRate = s.wins() * 100.0 / s.games();
            double goalDiff = s.avgGoalsFor() - s.avgGoalsAgainst();
            return new Scored(u, winRate, goalDiff);
        }).sorted(Comparator.comparingDouble(Scored::winRate).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::avgGoalDiff).reversed())).toList();

        List<DashboardRankingEntry> entries = new ArrayList<>();
        int rank = 1;
        for (Scored s : scored) {
            String reason = String.format("승률 %.1f%%, 경기당 득실차 %+.2f 기준으로 정렬했습니다(AI 랭킹 실패 대체).",
                    s.winRate(), s.avgGoalDiff());
            entries.add(new DashboardRankingEntry(s.user().ouid(), s.user().nickname(), rank++, reason));
        }
        return entries;
    }
}
