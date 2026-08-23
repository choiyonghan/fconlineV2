package com.fconline.app.insight.facade;

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
import com.fconline.domain.match.vo.MatchType;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public InsightSnapshotBuilder(RecordFacade recordFacade, OpponentFacade opponentFacade) {
        this.recordFacade = recordFacade;
        this.opponentFacade = opponentFacade;
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

        String summaryText = buildSummaryText(overall, opponents, allPlayers, assistChains, recentMatches);

        Map<String, String> opponentDetailByNickname = new LinkedHashMap<>();
        for (OpponentSummaryResponse o : opponents) {
            List<OpponentMatchResponse> matches = opponentFacade
                    .listOpponentMatches(ouid, o.opponentOuid(), matchType, seasonId,
                            PageRequest.of(0, OPPONENT_MATCH_LIMIT))
                    .getContent();
            opponentDetailByNickname.put(o.opponentNickname(), buildOpponentDetailText(o, matches));
        }

        return new InsightSnapshotContent(summaryText, opponentDetailByNickname);
    }

    private String buildSummaryText(OverallRecordResponse overall, List<OpponentSummaryResponse> opponents,
                                     List<TopPlayerResponse> allPlayers, List<AssistChainResponse> assistChains,
                                     List<RecentMatchResponse> recentMatches) {
        StringBuilder sb = new StringBuilder();

        sb.append("[선수: ").append(overall.nickname()).append("]\n");
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
                .append("경기, 다실점(3실점 이상) ").append(overall.multiConcededGames()).append("경기\n\n");

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
                .forEach(o -> sb.append("- ").append(o.opponentNickname()).append(": ")
                        .append(o.tally().win()).append("승 ")
                        .append(o.tally().draw()).append("무 ")
                        .append(o.tally().lose()).append("패, 욱식점수 ").append(o.dugsikScore())
                        .append(", 현재 ").append(streakSummary(o)).append("\n"));
        if (opponents.isEmpty()) {
            sb.append("- (해당 매치타입은 상대별 전적을 집계하지 않음)\n");
        }

        return sb.toString();
    }

    private String buildOpponentDetailText(OpponentSummaryResponse o, List<OpponentMatchResponse> matches) {
        StringBuilder sb = new StringBuilder();
        sb.append("[상대: ").append(o.opponentNickname()).append("] 경기별 상세 기록(최신 ")
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

    private static String formatRating(Double rating) {
        return rating == null ? "-" : String.format("%.2f", rating);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
