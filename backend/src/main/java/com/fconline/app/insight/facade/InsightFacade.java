package com.fconline.app.insight.facade;

import com.fconline.app.insight.dto.AskRequest;
import com.fconline.app.insight.dto.AskResponse;
import com.fconline.app.opponent.dto.OpponentSummaryResponse;
import com.fconline.app.opponent.facade.OpponentFacade;
import com.fconline.app.record.dto.GoalTimeBucketResponse;
import com.fconline.app.record.dto.GoalTypeStatResponse;
import com.fconline.app.record.dto.OverallRecordResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import com.fconline.app.record.facade.RecordFacade;
import com.fconline.infrastructure.gemini.GeminiApiClient;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자연어 질문 → 통계 데이터 기반 분석 답변(화면 밖 부가 기능).
 * 이미 있는 RecordFacade/OpponentFacade의 조회 결과를 그대로 재사용해서 텍스트로 요약한 뒤,
 * 그 요약 + 사용자 질문을 Gemini에 보낸다 — 집계 로직을 여기서 새로 만들지 않는다.
 */
@Component
public class InsightFacade {

    private static final String SYSTEM_INSTRUCTION = """
            당신은 FC Online(축구 게임) 전적 데이터를 분석해주는 도우미입니다.
            아래에 제공되는 데이터만 근거로 답변하고, 제공되지 않은 정보는 추측하지 마세요.
            데이터에 없는 걸 물어보면 모른다고 솔직히 답하세요.
            친근한 반말 대신 정중한 한국어 존댓말로, 3~5문장 정도로 간결하게 답변하세요.
            """;

    private final RecordFacade recordFacade;
    private final OpponentFacade opponentFacade;
    private final GeminiApiClient geminiApiClient;

    public InsightFacade(RecordFacade recordFacade, OpponentFacade opponentFacade, GeminiApiClient geminiApiClient) {
        this.recordFacade = recordFacade;
        this.opponentFacade = opponentFacade;
        this.geminiApiClient = geminiApiClient;
    }

    @Transactional(readOnly = true)
    public AskResponse ask(AskRequest request) {
        OverallRecordResponse overall = recordFacade.getOverallRecord(request.ouid(), request.matchType(), request.seasonId());
        List<OpponentSummaryResponse> opponents = opponentFacade.listOpponents(request.ouid(), request.matchType(), request.seasonId());

        String dataSummary = buildDataSummary(overall, opponents);
        String userPrompt = dataSummary + "\n\n[질문]\n" + request.question();

        String answer = geminiApiClient.ask(SYSTEM_INSTRUCTION, userPrompt);
        return new AskResponse(answer);
    }

    private String buildDataSummary(OverallRecordResponse overall, List<OpponentSummaryResponse> opponents) {
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
                .append(", 레드카드 ").append(overall.redCards()).append("\n\n");

        sb.append("기여도 상위 선수:\n");
        for (TopPlayerResponse p : overall.topPlayers()) {
            sb.append("- ").append(p.playerName()).append(": 골 ").append(p.goals())
                    .append(", 어시스트 ").append(p.assists())
                    .append(", 태클 ").append(p.tackles())
                    .append(", 인터셉트 ").append(p.intercepts())
                    .append(", 블록 ").append(p.blocks()).append("\n");
        }
        if (overall.topPlayers().isEmpty()) {
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
}
