package com.fconline.app.insight.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.insight.dto.InsightSnapshotContent;
import com.fconline.app.opponent.dto.OpponentMatchResponse;
import com.fconline.app.opponent.dto.OpponentSummaryResponse;
import com.fconline.app.opponent.facade.OpponentFacade;
import com.fconline.app.record.dto.AssistChainResponse;
import com.fconline.app.record.dto.GoalTimeBucketResponse;
import com.fconline.app.record.dto.GoalTypeStatResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.RecentMatchResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.app.record.facade.RecordFacade;
import com.fconline.app.user.dto.TrackedUserResponse;
import com.fconline.app.user.facade.UserFacade;
import com.fconline.domain.match.service.MatchDomainService;
import com.fconline.domain.match.vo.MatchGoalEvent;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.season.Season;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * InsightFacade.ask()에 쓰이는 데이터 스냅샷을 실제로 조립한다. RecordFacade/OpponentFacade의
 * 기존 조회 결과를 그대로 재사용해서 텍스트로 요약한다 — 집계 로직을 여기서 새로 만들지 않는다.
 *
 * 질문 하나에 답하기 위해 관련 API를 전부 호출해 "스냅샷"을 만든다: 종합 전적, 선수단 전체
 * 기여도, 어시스트 체인, 최근 경기, 상대별 전적에 더해 상대 한 명 한 명과의 경기별 상세 기록까지
 * 포함한다. 이 조립은 비용이 있어(analysis: 인사이트 API DB 부하) 질문마다 매번 하지 않고
 * 하루 한 번 배치({@code insight-snapshot} 프로파일, InsightSnapshotCliRunner)가 미리 실행해
 * DB(insight_snapshots)에 저장해두며, InsightFacade는 그 캐시를 읽는다. 배치가 아직 못 돌았거나
 * 캐시가 없는 예외 상황에서만 InsightFacade가 이 클래스를 직접 호출해 즉석에서 조립한다.
 */
@Component
public class InsightSnapshotBuilder {

    /** 상대 무관 "최근 경기" 스냅샷 개수. */
    private static final int RECENT_MATCH_LIMIT = 10;
    /** 상대별 경기 상세 기록 개수(상대 1명당). */
    private static final int OPPONENT_MATCH_LIMIT = 15;

    private static final DateTimeFormatter MATCH_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final RecordFacade recordFacade;
    private final OpponentFacade opponentFacade;
    private final TrackedUserAliasResolver aliasResolver;
    private final SeasonRangeResolver seasonRangeResolver;
    private final MatchDomainService matchDomainService;
    private final UserFacade userFacade;

    public InsightSnapshotBuilder(RecordFacade recordFacade, OpponentFacade opponentFacade,
                                   TrackedUserAliasResolver aliasResolver, SeasonRangeResolver seasonRangeResolver,
                                   MatchDomainService matchDomainService, UserFacade userFacade) {
        this.recordFacade = recordFacade;
        this.opponentFacade = opponentFacade;
        this.aliasResolver = aliasResolver;
        this.seasonRangeResolver = seasonRangeResolver;
        this.userFacade = userFacade;
        this.matchDomainService = matchDomainService;
    }

    @Transactional(readOnly = true)
    public InsightSnapshotContent build(String ouid, MatchType matchType, Long seasonId) {
        OverallRecordResponse overall = recordFacade.getOverallRecord(ouid, matchType, seasonId);
        List<OpponentSummaryResponse> opponents = opponentFacade.listOpponents(ouid, matchType, seasonId);
        List<TopPlayerResponse> allPlayers = recordFacade.getAllPlayers(ouid, matchType, seasonId);
        List<AssistChainResponse> assistChains = recordFacade.getAssistChains(ouid, matchType, seasonId);
        List<RecentMatchResponse> recentMatches = recordFacade
                .getRecentMatches(ouid, matchType, seasonId, PageRequest.of(0, RECENT_MATCH_LIMIT))
                .getContent();

        // 전체 유저 랭킹은 "현재 선택된 유저"와 무관하게 등록된 유저 전원의 자기 자신 종합
        // 전적(각자 전체 상대 합산)을 나란히 비교한 것 — 없으면 AI가 "전체 유저 기준으로
        // 순위 매겨줘" 질문에도 현재 유저의 상대별 전적(그 유저 한 명 기준 상성)만 근거로 답해서
        // 다른 유저끼리의 직접 비교가 아닌 엉뚱한 랭킹을 내놓는 문제가 있었다.
        String allUsersRankingText = buildAllUsersRankingText(matchType, seasonId);

        String summaryText = allUsersRankingText + "\n" + buildSummaryText(overall, opponents, allPlayers, assistChains, recentMatches);

        Season season = seasonRangeResolver.resolve(seasonId);
        Map<String, String> opponentDetailByNickname = new LinkedHashMap<>();
        for (OpponentSummaryResponse o : opponents) {
            List<OpponentMatchResponse> matches = opponentFacade
                    .listOpponentMatches(ouid, o.opponentOuid(), matchType, seasonId,
                            PageRequest.of(0, OPPONENT_MATCH_LIMIT))
                    .getContent();
            List<MatchGoalEvent> goalEvents = matchDomainService.goalEventsVsOpponent(
                    ouid, matchType, season.startInstant(), season.endInstantExclusiveOrNull(), o.opponentOuid());
            opponentDetailByNickname.put(o.opponentNickname(), buildOpponentDetailText(o, matches, goalEvents));
        }

        return new InsightSnapshotContent(summaryText, opponentDetailByNickname);
    }

    /**
     * 등록된 유저 전원의 "자기 자신 종합 전적"(각자 전체 상대 합산 — 특정 상대 기준 상성이
     * 아니라 진짜 전체 실력 지표)을 승률 높은 순으로 나열한다. "누가 제일 잘해?" 같은 전체
     * 유저 비교 질문은 이 섹션을 근거로 답해야 정확하다(현재 선택된 유저의 상대별 전적만으로는
     * 다른 유저끼리의 직접 비교가 안 됨).
     */
    private String buildAllUsersRankingText(MatchType matchType, Long seasonId) {
        List<TrackedUserResponse> allUsers = userFacade.listTrackedUsers();
        record UserRanking(String nickname, OverallRecordResponse record, double winRate) {
        }
        List<UserRanking> rankings = allUsers.stream()
                .map(u -> {
                    OverallRecordResponse r = recordFacade.getOverallRecord(u.ouid(), matchType, seasonId);
                    int total = r.tally().win() + r.tally().draw() + r.tally().lose();
                    double winRate = total == 0 ? 0.0 : r.tally().win() * 100.0 / total;
                    return new UserRanking(u.nickname(), r, winRate);
                })
                .sorted(Comparator.comparingDouble(UserRanking::winRate).reversed())
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("전체 유저 종합 전적 순위(등록된 유저 전원, 각자 전체 상대 합산 기준 — 승률 높은 순, ")
                .append(matchType).append("):\n");
        int rank = 1;
        for (UserRanking ur : rankings) {
            int total = ur.record().tally().win() + ur.record().tally().draw() + ur.record().tally().lose();
            long diff = ur.record().tally().goalsFor() - ur.record().tally().goalsAgainst();
            sb.append(rank++).append(". ").append(withAlias(ur.nickname()))
                    .append(": ").append(total).append("전 ")
                    .append(ur.record().tally().win()).append("승 ")
                    .append(ur.record().tally().draw()).append("무 ")
                    .append(ur.record().tally().lose()).append("패 (승률 ")
                    .append(String.format("%.1f", ur.winRate())).append("%), ")
                    .append("득실 ").append(diff >= 0 ? "+" : "").append(diff)
                    .append(", 평균 평점 ").append(String.format("%.2f", ur.record().averageRating())).append("\n");
        }
        if (rankings.isEmpty()) {
            sb.append("- (등록된 유저 없음)\n");
        }
        return sb.toString();
    }

    private String buildSummaryText(OverallRecordResponse overall, List<OpponentSummaryResponse> opponents,
                                     List<TopPlayerResponse> allPlayers, List<AssistChainResponse> assistChains,
                                     List<RecentMatchResponse> recentMatches) {
        StringBuilder sb = new StringBuilder();

        sb.append("[선수: ").append(withAlias(overall.nickname())).append("]\n");
        sb.append("전적: ").append(overall.tally().win()).append("승 ")
                .append(overall.tally().draw()).append("무 ")
                .append(overall.tally().lose()).append("패 (득점 ").append(overall.tally().goalsFor())
                .append(", 실점 ").append(overall.tally().goalsAgainst()).append(")\n");
        sb.append("평균 평점: ").append(String.format("%.2f", overall.averageRating()))
                .append(", 평균 점유율: ").append(String.format("%.0f", overall.possessionAverage())).append("%\n");
        sb.append("파울 ").append(overall.foulTotal())
                .append(", 옐로카드 ").append(overall.yellowCards())
                .append(", 레드카드 ").append(overall.redCards()).append("\n");
        sb.append("클린시트 ").append(overall.cleanSheets())
                .append("경기, 다실점(3실점 이상) ").append(overall.multiConcededGames()).append("경기, ")
                .append("고점유(55%↑) ").append(overall.highPossessionGames()).append("경기, ")
                .append("저점유(45%↓) ").append(overall.lowPossessionGames()).append("경기\n\n");

        sb.append("팀 전체 공격 지표(선수단 합산 — 플레이 성향 판단용):\n");
        sb.append(teamStyleLine(allPlayers)).append("\n\n");

        sb.append("선수단 전체 기여도(").append(allPlayers.size()).append("명, 기여도 높은 순):\n");
        allPlayers.stream()
                .sorted(Comparator.comparingDouble(TopPlayerResponse::contributionScore).reversed())
                .forEach(p -> sb.append("- ").append(p.playerName())
                        .append(": 출전 ").append(p.appearances())
                        .append(", 골 ").append(p.goals())
                        .append(", 어시스트 ").append(p.assists())
                        .append(", 세이브 ").append(p.saves())
                        .append(", 태클 ").append(p.tackles())
                        .append(", 인터셉트 ").append(p.intercepts())
                        .append(", 블록 ").append(p.blocks())
                        .append(", 평균평점 ").append(formatRating(p.avgRating())).append("\n"));
        if (allPlayers.isEmpty()) {
            sb.append("- (데이터 없음)\n");
        }
        sb.append("\n");

        sb.append("득점 유형 분포:\n");
        for (GoalTypeStatResponse g : overall.goalTypeDistribution()) {
            if (g.count() > 0) {
                sb.append("- ").append(g.shootType()).append(": ").append(g.count()).append("건\n");
            }
        }
        sb.append("\n");

        sb.append("득점 시간대 분포:\n");
        for (GoalTimeBucketResponse g : overall.goalTimeDistribution()) {
            sb.append("- ").append(g.periodLabel()).append("분: ").append(g.count()).append("건\n");
        }
        sb.append("\n");

        sb.append("어시스트 체인(상위 ").append(assistChains.size()).append("건):\n");
        for (AssistChainResponse c : assistChains) {
            sb.append("- ").append(c.assisterName()).append(" → ").append(c.scorerName())
                    .append(": ").append(c.goals()).append("골\n");
        }
        if (assistChains.isEmpty()) {
            sb.append("- (데이터 없음)\n");
        }
        sb.append("\n");

        sb.append("최근 경기(최신 ").append(recentMatches.size()).append("건):\n");
        for (RecentMatchResponse m : recentMatches) {
            sb.append("- ").append(MATCH_DATE_FORMAT.format(m.matchDate()))
                    .append(" vs ").append(m.opponentNickname())
                    .append(": ").append(m.result())
                    .append(" ").append(m.goalsFor()).append("-").append(m.goalsAgainst())
                    .append(", 평점 ").append(formatRating(m.averageRating()))
                    .append(", 점유율 ").append(nz(m.possession())).append("%\n");
        }
        if (recentMatches.isEmpty()) {
            sb.append("- (데이터 없음)\n");
        }
        sb.append("\n");

        sb.append("상대별 전적 (욱식 점수 높은 순):\n");
        opponents.stream()
                .sorted((a, b) -> Integer.compare(b.dugsikScore(), a.dugsikScore()))
                .forEach(o -> sb.append("- ").append(withAlias(o.opponentNickname())).append(": ")
                        .append(o.tally().win()).append("승 ")
                        .append(o.tally().draw()).append("무 ")
                        .append(o.tally().lose()).append("패, 욱식점수 ").append(o.dugsikScore())
                        .append(", 현재 ").append(streakSummary(o)).append("\n"));
        if (opponents.isEmpty()) {
            sb.append("- (해당 매치타입은 상대별 전적을 집계하지 않음)\n");
        }

        return sb.toString();
    }

    private String buildOpponentDetailText(OpponentSummaryResponse o, List<OpponentMatchResponse> matches,
                                            List<MatchGoalEvent> goalEvents) {
        StringBuilder sb = new StringBuilder();
        sb.append("[상대: ").append(withAlias(o.opponentNickname())).append("] 경기별 상세 기록(최신 ")
                .append(matches.size()).append("건):\n");
        for (OpponentMatchResponse m : matches) {
            sb.append("- ").append(MATCH_DATE_FORMAT.format(m.matchDate()))
                    .append(": ").append(m.result())
                    .append(" ").append(m.goalsFor()).append("-").append(m.goalsAgainst())
                    .append(", 평점 ").append(formatRating(m.averageRating()))
                    .append(", 점유율 ").append(nz(m.possession())).append("%")
                    .append(", 슈팅 ").append(nz(m.effectiveShoot())).append("/").append(nz(m.shootTotal()))
                    .append(", 패스성공 ").append(nz(m.passSuccess())).append("/").append(nz(m.passTry()))
                    .append(", 태클성공 ").append(nz(m.tackleSuccess())).append("/").append(nz(m.tackleTry()))
                    .append(", 파울 ").append(nz(m.foul()))
                    .append(", 카드 ").append(nz(m.yellowCards())).append("Y/").append(nz(m.redCards())).append("R\n");
        }
        if (matches.isEmpty()) {
            sb.append("- (데이터 없음)\n");
        }

        // 위 경기별 상세 기록과 같은 표본(matchId)으로만 좁힌다 — 그래야 "선제골 요약"의 승/무/패
        // 합계가 위 목록과 어긋나지 않고, 텍스트 크기도 억제된다.
        Set<String> matchIdsInScope = matches.stream().map(OpponentMatchResponse::matchId).collect(Collectors.toSet());
        Map<String, List<MatchGoalEvent>> eventsByMatch = goalEvents.stream()
                .filter(e -> matchIdsInScope.contains(e.matchId()))
                .collect(Collectors.groupingBy(MatchGoalEvent::matchId));

        String timelineText = buildGoalTimelineText(matches, eventsByMatch);
        if (timelineText != null) {
            sb.append("\n").append(timelineText);
        }

        String firstGoalSummary = buildFirstGoalSummary(o, matches, eventsByMatch);
        if (firstGoalSummary != null) {
            sb.append("\n").append(firstGoalSummary);
        }
        return sb.toString();
    }

    /**
     * 매치별 골 타임라인 원문(누가 몇 분에 넣었는지) — "선제골 요약"처럼 미리 집계해둔 지표뿐
     * 아니라, 아직 전용 집계 로직이 없는 질문(예: "후반에 골을 더 많이 넣는 편이야?", "역전골이
     * 몇 번 있었어?")도 AI가 원시 타임라인을 직접 보고 스스로 분석할 수 있게 한다. 두 참가자
     * 다 추적 대상이어야 그 매치의 타임라인을 완전히 복원할 수 있어, 그런 매치만 나온다.
     */
    private String buildGoalTimelineText(List<OpponentMatchResponse> matches,
                                          Map<String, List<MatchGoalEvent>> eventsByMatch) {
        if (eventsByMatch.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("골 타임라인(경기별, \"나\"=이 유저 관점 골 · \"상대\"=그 상대 관점 골, 분은 경기 시작 기준 누적):\n");
        matches.stream()
                .filter(m -> eventsByMatch.containsKey(m.matchId()))
                .forEach(m -> {
                    String line = eventsByMatch.get(m.matchId()).stream()
                            .sorted(Comparator.comparingInt(e -> matchDomainService.absoluteMinute(e.minute(), e.period())))
                            .map(e -> (e.mine() ? "나 " : "상대 ") + matchDomainService.absoluteMinute(e.minute(), e.period()) + "'")
                            .collect(Collectors.joining(", "));
                    sb.append("- ").append(MATCH_DATE_FORMAT.format(m.matchDate())).append(": ").append(line).append("\n");
                });
        return sb.toString();
    }

    /**
     * "누가 선제골을 더 많이 넣고, 그때 결과는 어땠는지" — 매치별로 가장 이른 골의 주체를 골라
     * 위 골 타임라인과 같은 표본으로 집계한다(무득점 경기는 고를 골이 없어 자연히 빠짐).
     */
    private String buildFirstGoalSummary(OpponentSummaryResponse o, List<OpponentMatchResponse> matches,
                                          Map<String, List<MatchGoalEvent>> eventsByMatch) {
        if (eventsByMatch.isEmpty()) {
            return null;
        }
        Map<String, String> resultByMatchId = matches.stream()
                .collect(Collectors.toMap(OpponentMatchResponse::matchId, OpponentMatchResponse::result, (a, b) -> a));

        int myFirst = 0, oppFirst = 0;
        int myFirstWin = 0, myFirstDraw = 0, myFirstLose = 0;
        int oppFirstWin = 0, oppFirstDraw = 0, oppFirstLose = 0;

        for (Map.Entry<String, List<MatchGoalEvent>> entry : eventsByMatch.entrySet()) {
            MatchGoalEvent first = entry.getValue().stream()
                    .min(Comparator.comparingInt(e -> matchDomainService.absoluteMinute(e.minute(), e.period())))
                    .orElse(null);
            if (first == null) {
                continue;
            }
            String result = resultByMatchId.get(entry.getKey());
            if (result == null) {
                continue;
            }
            if (first.mine()) {
                myFirst++;
                if ("승".equals(result)) myFirstWin++;
                else if ("무".equals(result)) myFirstDraw++;
                else if ("패".equals(result)) myFirstLose++;
            } else {
                oppFirst++;
                if ("승".equals(result)) oppFirstWin++;
                else if ("무".equals(result)) oppFirstDraw++;
                else if ("패".equals(result)) oppFirstLose++;
            }
        }

        int total = myFirst + oppFirst;
        if (total == 0) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(withAlias(o.opponentNickname())).append("와의 선제골 분석(위 골 타임라인과 같은 표본 ")
                .append(total).append("건):\n");
        sb.append("- 내가 선제골: ").append(myFirst).append("회 (그때 결과 ")
                .append(myFirstWin).append("승 ").append(myFirstDraw).append("무 ").append(myFirstLose).append("패)\n");
        sb.append("- 상대가 선제골: ").append(oppFirst).append("회 (그때 결과 ")
                .append(oppFirstWin).append("승 ").append(oppFirstDraw).append("무 ").append(oppFirstLose).append("패)\n");
        return sb.toString();
    }

    private String streakSummary(OpponentSummaryResponse o) {
        var s = o.streak();
        if (s.curWin() > 0) {
            return s.curWin() + "연승";
        }
        if (s.curLose() > 0) {
            return s.curLose() + "연패";
        }
        if (s.curUnbeaten() > 1) {
            return s.curUnbeaten() + "경기 무패";
        }
        if (s.curWinless() > 1) {
            return s.curWinless() + "경기 무승";
        }
        return "특이사항 없음";
    }

    /**
     * 선수단 전체 슈팅/패스/드리블/공중볼 시도-성공을 합산해 정확도(%)를 낸다 — 개별 선수
     * 스탯만으로는 안 보이는 팀 전체 플레이 성향(예: 드리블 위주 vs 패스 위주)을 위한 지표.
     */
    private String teamStyleLine(List<TopPlayerResponse> allPlayers) {
        long shootTotal = 0, effectiveShoot = 0, passTry = 0, passSuccess = 0;
        long dribbleTry = 0, dribbleSuccess = 0, aerialTry = 0, aerialSuccess = 0;
        for (TopPlayerResponse p : allPlayers) {
            shootTotal += p.shootTotal();
            effectiveShoot += p.effectiveShoot();
            passTry += p.passTry();
            passSuccess += p.passSuccess();
            dribbleTry += p.dribbleTry();
            dribbleSuccess += p.dribbleSuccess();
            aerialTry += p.aerialTry();
            aerialSuccess += p.aerialSuccess();
        }
        return "- 슈팅 정확도: " + percent(effectiveShoot, shootTotal) + " (" + effectiveShoot + "/" + shootTotal + ")\n"
                + "- 패스 성공률: " + percent(passSuccess, passTry) + " (" + passSuccess + "/" + passTry + ")\n"
                + "- 드리블 성공률: " + percent(dribbleSuccess, dribbleTry) + " (" + dribbleSuccess + "/" + dribbleTry + ")\n"
                + "- 공중볼 경합 승률: " + percent(aerialSuccess, aerialTry) + " (" + aerialSuccess + "/" + aerialTry + ")";
    }

    private static String percent(long success, long total) {
        return total == 0 ? "-" : String.format("%.0f%%", success * 100.0 / total);
    }

    /** 닉네임에 매핑된 실명이 있으면 "닉네임(실명)"으로, 없으면 닉네임 그대로. */
    private String withAlias(String nickname) {
        String realName = aliasResolver.realNameOf(nickname);
        return realName == null ? nickname : nickname + "(" + realName + ")";
    }

    private static String formatRating(Double rating) {
        return rating == null ? "-" : String.format("%.2f", rating);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
