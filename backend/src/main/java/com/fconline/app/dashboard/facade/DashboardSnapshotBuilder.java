package com.fconline.app.dashboard.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fconline.app.dashboard.dto.DashboardCombo;
import com.fconline.app.dashboard.dto.DashboardPooledPlayer;
import com.fconline.app.dashboard.dto.DashboardRankingEntry;
import com.fconline.app.dashboard.dto.DashboardScopeSummary;
import com.fconline.app.dashboard.dto.DashboardSnapshotFile;
import com.fconline.app.dashboard.dto.DashboardTopPlayer;
import com.fconline.app.dashboard.dto.DashboardUserSnapshot;
import com.fconline.domain.match.vo.ExpectedGoalsCalculator;
import com.fconline.app.insight.facade.TrackedUserAliasResolver;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.MatchPlayerRatingResponse;
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
 * 메인 대시보드(report.html, 유저 칩 "전체") 스냅샷 조립. 매일 아침 배치({@code dashboard-snapshot}
 * 프로파일, DashboardSnapshotCliRunner)가 호출해 data/dashboard-snapshot.json을 만든다 — 백엔드가
 * 잠들어 있어도(Render 무료 티어 콜드 스타트) 대시보드는 이 정적 파일만 읽어 즉시 뜨게 하려는
 * 목적. 기존 RecordFacade/SeasonFacade/UserFacade만 재사용하고 새 DB 테이블/마이그레이션은 없다.
 *
 * 스코프는 "모두의 커스텀"(matchType=CUSTOM, 현재시즌 날짜 범위) 하나뿐이다 — 공식전은 대시보드에
 * 안 남긴다(요청). SeasonRangeResolver.resolve(null)이 "전체 기간"이 아니라 "오늘 기준 진행 중인
 * 시즌"으로 해석되므로(app.common.SeasonRangeResolver), currentSeason.id()를 명시적으로 넘긴다.
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
    private final TrackedUserAliasResolver aliasResolver;
    private final ObjectMapper objectMapper;

    public DashboardSnapshotBuilder(UserFacade userFacade, SeasonFacade seasonFacade, RecordFacade recordFacade,
                                     GeminiApiClient geminiApiClient, TrackedUserAliasResolver aliasResolver,
                                     ObjectMapper objectMapper) {
        this.userFacade = userFacade;
        this.seasonFacade = seasonFacade;
        this.recordFacade = recordFacade;
        this.geminiApiClient = geminiApiClient;
        this.aliasResolver = aliasResolver;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DashboardSnapshotFile build() {
        List<TrackedUserResponse> users = userFacade.listTrackedUsers();
        SeasonResponse currentSeason = resolveCurrentSeason();
        MatchType matchType = MatchType.CUSTOM;
        Long seasonId = currentSeason.id();

        Map<String, DashboardScopeSummary> summaryByOuid = new LinkedHashMap<>();
        Map<String, List<TopPlayerResponse>> playersByOuid = new LinkedHashMap<>();
        for (TrackedUserResponse u : users) {
            ShotHeatmapResponse heatmap = recordFacade.getShotHeatmap(u.ouid(), matchType, seasonId, false);
            ShotHeatmapResponse conceded = recordFacade.getConcededShotHeatmap(u.ouid(), matchType, seasonId);
            List<TopPlayerResponse> players = recordFacade.getAllPlayers(u.ouid(), matchType, seasonId);
            playersByOuid.put(u.ouid(), players);
            summaryByOuid.put(u.ouid(), buildScopeSummary(u.ouid(), matchType, seasonId, heatmap, conceded, players));
        }

        Map<String, DashboardUserSnapshot> snapshots = new LinkedHashMap<>();
        for (TrackedUserResponse u : users) {
            snapshots.put(u.ouid(), new DashboardUserSnapshot(u.ouid(), u.nickname(), summaryByOuid.get(u.ouid())));
        }

        RankingResult ranking = buildRanking(users, summaryByOuid);
        List<DashboardPooledPlayer> allPlayers = buildPooledPlayers(users, matchType, seasonId, playersByOuid);

        return new DashboardSnapshotFile(Instant.now(), currentSeason.name(),
                ranking.failed(), ranking.note(), ranking.introText(), ranking.outroText(),
                ranking.entries(), snapshots, allPlayers);
    }

    /**
     * "전체 선수 스탯"(9명 풀링, 최소 10경기 출전만) + MOM 횟수. MOM은 매치별로 이 유저 관점
     * 스쿼드 평점 원시값(getMatchPlayerRatings)을 9명 전부 모아 matchId로 묶은 뒤 argmax를
     * 구하는 방식이다 — 상대도 추적 대상이면 상대 쪽 행도 같은 matchId로 이 풀에 자연히
     * 섞여 들어와서 양팀 통틀어 비교된다(findConcededShotPoints와 같은 원리, 별도 상대 조인 불필요).
     */
    private List<DashboardPooledPlayer> buildPooledPlayers(List<TrackedUserResponse> users, MatchType matchType,
                                                             Long seasonId, Map<String, List<TopPlayerResponse>> playersByOuid) {
        record RatedEntry(String nickname, String matchId, String spId, double rating) {
        }
        List<RatedEntry> allRatings = new ArrayList<>();
        for (TrackedUserResponse u : users) {
            for (MatchPlayerRatingResponse r : recordFacade.getMatchPlayerRatings(u.ouid(), matchType, seasonId)) {
                allRatings.add(new RatedEntry(u.nickname(), r.matchId(), r.spId(), r.rating()));
            }
        }
        Map<String, RatedEntry> bestByMatch = new LinkedHashMap<>();
        for (RatedEntry e : allRatings) {
            RatedEntry cur = bestByMatch.get(e.matchId());
            if (cur == null || e.rating() > cur.rating()) bestByMatch.put(e.matchId(), e);
        }
        Map<String, Integer> momCountByKey = new HashMap<>();
        for (RatedEntry best : bestByMatch.values()) {
            momCountByKey.merge(best.nickname() + "|" + best.spId(), 1, Integer::sum);
        }

        List<DashboardPooledPlayer> pooled = new ArrayList<>();
        for (TrackedUserResponse u : users) {
            for (TopPlayerResponse p : playersByOuid.getOrDefault(u.ouid(), List.of())) {
                if (p.appearances() < TOP_PLAYER_MIN_APPEARANCES) continue;
                int momCount = momCountByKey.getOrDefault(u.nickname() + "|" + p.spId(), 0);
                pooled.add(new DashboardPooledPlayer(
                        u.nickname(), p.spId(), p.playerName(), p.appearances(),
                        p.goals(), p.assists(), p.saves(), p.tackles(), p.intercepts(), p.blocks(),
                        p.shootTotal(), p.effectiveShoot(), p.passTry(), p.passSuccess(),
                        p.dribbleTry(), p.dribbleSuccess(), p.dribbleDistance(), p.aerialTry(), p.aerialSuccess(),
                        p.avgRating(), p.xg(), p.goals() - p.xg(), momCount, p.goalsAgainst(), p.xa()));
            }
        }
        return pooled;
    }

    private SeasonResponse resolveCurrentSeason() {
        List<SeasonResponse> seasons = seasonFacade.listSeasons();
        return seasons.stream().filter(SeasonResponse::current).findFirst()
                .orElseGet(() -> {
                    log.warn("current=true인 시즌이 없어 최신 시즌으로 대체합니다.");
                    return seasons.get(0);
                });
    }

    // ---------------- 스코프("모두의 커스텀") 서머리 ----------------

    /**
     * 지표 공식은 report.js의 renderPlayStyle(플레이 성향 카드)과 동일하게 맞춘다 — 클래스
     * 주석(DashboardScopeSummary) 참고. xG는 ExpectedGoalsCalculator(거리·각도 로지스틱 회귀
     * 순수 함수)라 더는 표본을 미리 풀링할 필요가 없다. players는 build()가 "전체 선수 스탯"
     * 풀링에도 재사용하려고 미리 fetch해서 넘겨준다(중복 호출 방지).
     */
    private DashboardScopeSummary buildScopeSummary(String ouid, MatchType matchType, Long seasonId,
                                                      ShotHeatmapResponse heatmap, ShotHeatmapResponse conceded,
                                                      List<TopPlayerResponse> players) {
        OverallRecordResponse overall = recordFacade.getOverallRecord(ouid, matchType, seasonId);
        int games = overall.tally().win() + overall.tally().draw() + overall.tally().lose();

        List<ShotPointResponse> points = heatmap.points();
        List<ShotPointResponse> concededPoints = conceded.points();

        double expectedGoalsFor = sumXg(points);
        long actualGoals = points.stream().filter(ShotPointResponse::goal).count();
        double expectedGoalsAgainst = sumXg(concededPoints);
        int concededSampleGames = (int) concededPoints.stream().map(ShotPointResponse::matchId).distinct().count();

        int low = (int) overall.lowPossessionGames();
        int high = (int) overall.highPossessionGames();
        int balanced = Math.max(games - low - high, 0);

        List<AssistChainResponse> chains = recordFacade.getAssistChains(ouid, matchType, seasonId, ASSIST_CHAIN_LIMIT);
        int totalShotsOnTarget = players.stream().mapToInt(TopPlayerResponse::effectiveShoot).sum();
        double totalXaFor = players.stream().mapToDouble(TopPlayerResponse::xa).sum();
        // 선수단 합산 대신 overall(MatchStats 기반, 매치당 팀 합계) 값을 쓴다 — report.js
        // 개인 리포트 페이지의 패스 성향 카드와 같은 소스로 맞춘 것(이전엔 여기만 선수 합산이었다).
        int totalPassTry = (int) overall.passTryTotal();
        int totalPassSuccess = (int) overall.passSuccessTotal();

        return new DashboardScopeSummary(
                games, overall.tally().win(), overall.tally().draw(), overall.tally().lose(),
                games == 0 ? 0 : overall.tally().goalsFor() / (double) games,
                games == 0 ? 0 : expectedGoalsFor / games,
                actualGoals - expectedGoalsFor,
                games == 0 ? 0 : points.size() / (double) games,
                games == 0 ? 0 : overall.tally().goalsAgainst() / (double) games,
                concededSampleGames == 0 ? null : expectedGoalsAgainst / concededSampleGames,
                concededSampleGames,
                (int) overall.tally().goalsFor(), (int) overall.tally().goalsAgainst(),
                points.size(), totalShotsOnTarget, totalPassTry, totalPassSuccess, expectedGoalsFor,
                concededSampleGames == 0 ? null : expectedGoalsAgainst,
                totalXaFor,
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

    private static double sumXg(List<ShotPointResponse> points) {
        double sum = 0;
        for (ShotPointResponse p : points) {
            sum += ExpectedGoalsCalculator.calcXg(p.x(), p.y(), p.shootType());
        }
        return sum;
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

    /** 표본이 너무 적은(예: 1경기 반짝) 선수가 TOP에 끼는 걸 막는다 — 최소 출전 경기 수. */
    private static final int TOP_PLAYER_MIN_APPEARANCES = 10;

    private List<DashboardTopPlayer> topBy(List<TopPlayerResponse> players, java.util.function.ToIntFunction<TopPlayerResponse> extractor) {
        return players.stream()
                .filter(p -> p.appearances() >= TOP_PLAYER_MIN_APPEARANCES)
                .map(p -> new DashboardTopPlayer(p.playerName(), extractor.applyAsInt(p)))
                .filter(t -> t.value() > 0)
                .sorted(Comparator.comparingInt(DashboardTopPlayer::value).reversed())
                .limit(TOP_N)
                .toList();
    }

    /** 닉네임에 매핑된 실명이 있으면 "닉네임(실명)"으로, 없으면 닉네임 그대로(InsightSnapshotBuilder.withAlias와 동일). */
    private String displayNameOf(String nickname) {
        String realName = aliasResolver.realNameOf(nickname);
        return realName == null ? nickname : nickname + "(" + realName + ")";
    }

    // ---------------- AI 랭킹 ----------------

    private record RankingResult(boolean failed, String note, String introText, String outroText,
                                  List<DashboardRankingEntry> entries) {
    }

    private static final String RANKING_SYSTEM_INSTRUCTION = """
            너는 FC 온라인(피파온라인) 친구 그룹의 전적 데이터를 근거로 "종합 순위 리포트"를 발표하는
            과장되고 유쾌한 스포츠 해설자다. 아래 유저들의 "모두의 커스텀"(현재시즌 커스텀 매치) 통계를
            보고 종합 실력 기준 1위부터 꼴찌까지 순위를 매겨라. 승률과 득실차를 가장 중요하게 보고,
            결정력(실제 득점-xG), 기대어시스트(xA, 어시스트한 슛의 기대 득점 합계), 평균 평점,
            클린시트 비율도 참고해라. 표본 경기 수가 너무 적은(예: 5경기 미만) 유저는 신뢰도가
            낮다는 점도 감안해라.

            톤: 과장된 스포츠 중계·해설 말투(이모지 섞어서 재밌게, 하지만 인신공격이나 진짜 조롱은 금지 —
            성적에 대한 유쾌한 놀림 정도만). 반드시 순수 JSON 오브젝트로만 답하라. 코드블록, 마크다운, 다른
            설명 문장을 절대 섞지 마라. 형식:
            {"introText":"리포트 시작 인사말 1~2문단(재밌게)",
             "ranking":[{"nickname":"정확히 입력받은 닉네임 그대로","rank":1,"reason":"전적 요약 + 2~3문장 유쾌한 해설"}, ...],
             "outroText":"전체 총평 1문단(재밌게)"}
            입력받은 유저 수와 정확히 같은 개수의 ranking 항목을, rank는 1부터 그 수까지 중복 없이 채워라.
            """;

    private RankingResult buildRanking(List<TrackedUserResponse> users, Map<String, DashboardScopeSummary> summaryByOuid) {
        try {
            String prompt = buildRankingPrompt(users, summaryByOuid);
            String raw = geminiApiClient.askJson(RANKING_SYSTEM_INSTRUCTION, prompt);
            return parseRanking(raw, users);
        } catch (Exception e) {
            log.error("AI 랭킹 호출/파싱 실패 — 승률 기준 대체 랭킹(고정 유머 해설 포함)으로 대체합니다.", e);
            return fallbackRanking(users, summaryByOuid);
        }
    }

    private String buildRankingPrompt(List<TrackedUserResponse> users, Map<String, DashboardScopeSummary> summaryByOuid) {
        StringBuilder sb = new StringBuilder();
        sb.append("유저 ").append(users.size()).append("명(모두의 커스텀 기준):\n\n");
        for (TrackedUserResponse u : users) {
            sb.append("- 닉네임: ").append(u.nickname()).append("\n");
            appendScopeLine(sb, summaryByOuid.get(u.ouid()));
        }
        return sb.toString();
    }

    private void appendScopeLine(StringBuilder sb, DashboardScopeSummary s) {
        if (s == null || s.games() == 0) {
            sb.append("  표본 없음\n\n");
            return;
        }
        double winRate = s.wins() * 100.0 / s.games();
        sb.append("  ").append(s.games()).append("전 ")
                .append(s.wins()).append("승 ").append(s.draws()).append("무 ").append(s.losses())
                .append("패 (승률 ").append(String.format("%.1f", winRate)).append("%), ")
                .append("평균득점 ").append(String.format("%.2f", s.avgGoalsFor())).append(", ")
                .append("평균실점 ").append(String.format("%.2f", s.avgGoalsAgainst())).append(", ")
                .append("결정력 ").append(String.format("%+.1f", s.finishing())).append(", ")
                .append("기대어시스트(xA) ").append(String.format("%.1f", s.totalXaFor())).append(", ")
                .append("평균평점 ").append(String.format("%.2f", s.avgRating())).append(", ")
                .append("클린시트 ").append(String.format("%.0f", s.cleanSheetPct())).append("%\n\n");
    }

    private RankingResult parseRanking(String raw, List<TrackedUserResponse> users) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode array = root.isArray() ? root : root.path("ranking");
        if (!array.isArray() || array.size() != users.size()) {
            throw new IllegalStateException("AI 랭킹 응답 개수가 유저 수와 다릅니다: " + raw);
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
                throw new IllegalStateException("AI 랭킹 응답이 유효하지 않습니다(닉네임/순위 불일치): " + raw);
            }
            entries.add(new DashboardRankingEntry(ouid, nickname, displayNameOf(nickname), rank, reason));
        }
        entries.sort(Comparator.comparingInt(DashboardRankingEntry::rank));

        String introText = root.has("introText") ? root.path("introText").asText(null) : null;
        String outroText = root.has("outroText") ? root.path("outroText").asText(null) : null;
        return new RankingResult(false, null, introText, outroText, entries);
    }

    // ---------------- AI 호출 실패 시 대체(fallback) ----------------

    /**
     * 사용자가 실제로 만든(AI에게 물어봐서 받은) "해설자 톤" 예시를 그대로 재현하고 싶어했다 —
     * 다만 그 예시의 순위/전적 숫자를 소스에 영구히 박아두면 데이터가 갱신될 때마다 거짓말이
     * 되고, 실명이 들어간 문장도 있어(이 저장소는 public이라 실명은 절대 커밋하지 않는다 —
     * TrackedUserAliasResolver 클래스 주석 참고) 그대로 하드코딩할 수 없었다. 그래서:
     * - 순위/전적 숫자는 항상 그때그때 실제 계산값을 쓴다(승률 desc → 평균 득실차 desc).
     * - "해설:" 농담 한 줄만 닉네임별로 고정해서 재사용한다(숫자·실명은 다 빼고 재작성) —
     *   어느 순위에 있든 그 유저의 "캐릭터"로 계속 붙는다.
     * - 인트로/총평도 그 예시의 텍스트지만, 총평은 특정 순위·득실을 콕 집어 말하는 문장이라
     *   그대로 두면 순위가 바뀐 날 거짓말이 되므로 순위-무관 버전으로 다시 썼다.
     */
    private static final Map<String, String> FALLBACK_JOKES = Map.ofEntries(
            Map.entry("D로쏘네리", "승률 미친 포스로 리그 최고존엄 후보에 등극! 패배보다 승리가 압도적으로 많은 고고한 신선의 영역입니다."),
            Map.entry("아기블루스", "혼자 리그를 씹어먹는 중입니다! 득실차가 전체 유저 중 가장 화끈한 폭격기 성능을 보여줬네요."),
            Map.entry("내눈을가져가", "눈을 내주고 승리를 챙긴 신체기부형 강자! 당당히 상위권에 자리를 잡았습니다."),
            Map.entry("내혀를가져가", "무려 최다급 경기 수를 소화하는 미친 체력과 판수! 리그의 실질적인 체력왕이자 화력 담당입니다."),
            Map.entry("ST반니스텔로이", "정확히 반반! 승리와 패배의 완벽한 황금 비율을 자랑하는 리그의 인간 반반치킨입니다."),
            Map.entry("서울쥐", "살짝 아쉬운 턱걸이권이지만, 이길 때는 아주 상대를 탈탈 털어버리는 매운맛 쥐입니다."),
            Map.entry("지린성에사는욱구", "이 정도면 축구가 아니라 걸어다니는 승점 자판기 아니신가요?! 수비는 잠시 꺼두셨는지 득실이 대기권 밖으로 뚫렸습니다. 리그의 성인군자십니다!"),
            Map.entry("욱냥0I", "표본이 아직 적지만, 승리의 맛을 아직 한 번도 보지 못한 아기 고양이 상태입니다."),
            Map.entry("프란체스co토티", "표본이 적은 채로 평점이 아쉬운 편입니다! 경기장에 찍먹만 하러 오셨다가 수수료만 내고 가셨나 봐요!")
    );

    private static final String FALLBACK_INTRO =
            "아아, 마이크 테스트! FC 온라인 리그의 잔혹하고도 눈물겨운 전체 유저 종합 순위 리포트를 발표합니다!\n\n" +
            "이번 순위는 각 유저가 그동안 쌓아올린 피, 땀, 그리고 (누군가의) 눈물로 만들어진 승률·득실차 기준입니다. " +
            "영광의 1위부터 통곡의 꼴찌까지 지금 공개합니다! (AI 랭킹 호출에 실패해 자동 집계 기준으로 대신 보여드려요.)";

    private static final String FALLBACK_OUTRO =
            "[해설자 총평]\n오늘도 승자는 미소를, 패자는 다음 시즌을 기약합니다. 득실차 마이너스는 부끄러운 게 아니라 " +
            "다음 경기를 더 화끈하게 만들 스토리일 뿐입니다 — 리그는 계속됩니다!";

    private RankingResult fallbackRanking(List<TrackedUserResponse> users, Map<String, DashboardScopeSummary> summaryByOuid) {
        record Scored(TrackedUserResponse user, DashboardScopeSummary summary, double winRate, double avgGoalDiff) {
        }
        List<Scored> scored = users.stream().map(u -> {
            DashboardScopeSummary s = summaryByOuid.get(u.ouid());
            if (s == null || s.games() == 0) return new Scored(u, s, 0, 0);
            double winRate = s.wins() * 100.0 / s.games();
            double goalDiff = s.avgGoalsFor() - s.avgGoalsAgainst();
            return new Scored(u, s, winRate, goalDiff);
        }).sorted(Comparator.comparingDouble(Scored::winRate).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::avgGoalDiff).reversed())).toList();

        List<DashboardRankingEntry> entries = new ArrayList<>();
        int rank = 1;
        for (Scored s : scored) {
            entries.add(new DashboardRankingEntry(s.user().ouid(), s.user().nickname(),
                    displayNameOf(s.user().nickname()), rank++, fallbackReason(s.user().nickname(), s.summary())));
        }
        return new RankingResult(true, "AI 랭킹 호출에 실패해 승률·득실차 기준 자동 집계로 대체했습니다.",
                FALLBACK_INTRO, FALLBACK_OUTRO, entries);
    }

    private String fallbackReason(String nickname, DashboardScopeSummary s) {
        String joke = FALLBACK_JOKES.getOrDefault(nickname, "묵묵히 자기 몫을 하고 있습니다.");
        if (s == null || s.games() == 0) {
            return "표본 경기가 아직 없습니다. " + joke;
        }
        double winRate = s.wins() * 100.0 / s.games();
        String statLine = s.games() + "전 " + s.wins() + "승 " + s.draws() + "무 " + s.losses() + "패 (승률 "
                + String.format("%.1f", winRate) + "%) | 득실 "
                + String.format("%+.1f", (s.avgGoalsFor() - s.avgGoalsAgainst()) * s.games())
                + " | 평균 평점 " + String.format("%.2f", s.avgRating());
        return statLine + " — " + joke;
    }
}
