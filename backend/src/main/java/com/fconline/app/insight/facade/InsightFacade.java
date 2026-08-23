package com.fconline.app.insight.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.insight.dto.AskRequest;
import com.fconline.app.insight.dto.AskResponse;
import com.fconline.app.insight.dto.InsightSnapshotContent;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.season.Season;
import com.fconline.infrastructure.gemini.GeminiApiClient;
import com.fconline.infrastructure.insight.GithubInsightSnapshotClient;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자연어 질문 + 현재 조회 컨텍스트(유저/매치타입/시즌)를 받아 Gemini가 분석한 답변을 반환한다.
 *
 * 질문마다 관련 API를 전부 재호출하면 DB 부하가 쌓이므로, 매일 아침 배치({@code insight-snapshot}
 * 프로파일, InsightSnapshotCliRunner)가 미리 만들어 GitHub 저장소에 커밋해둔 스냅샷 파일을
 * (GithubInsightSnapshotClient로) 읽어서 그대로 쓴다. 별도 DB 테이블 없이 리포지토리 자체를
 * 캐시 저장소로 쓰는 구조 — 커밋된 JSON이 곧 "오늘의 스냅샷"이다. 스냅샷이 아직 없거나
 * (첫 실행, 새 시즌 등) 조회에 실패했을 때만 즉석에서 조립한다(InsightSnapshotBuilder 참고).
 */
@Component
public class InsightFacade {

    private static final Logger log = LoggerFactory.getLogger(InsightFacade.class);

    private static final String SYSTEM_INSTRUCTION = """
            당신은 FC Online(축구 게임) 전적 데이터를 분석해주는 도우미입니다.
            아래에 제공되는 데이터만 근거로 답변하고, 제공되지 않은 정보는 추측하지 마세요.
            데이터에 없는 걸 물어보면 모른다고 솔직히 답하세요.
            "보고서"나 요약을 요청하면 제공된 데이터를 폭넓게 활용해 구체적인 수치와 경기 흐름을
            근거로 상세히 답변하세요. 친근한 반말 대신 정중한 한국어 존댓말을 사용하세요.
            """;

    private final SeasonRangeResolver seasonRangeResolver;
    private final GithubInsightSnapshotClient githubInsightSnapshotClient;
    private final InsightSnapshotBuilder insightSnapshotBuilder;
    private final TrackedUserAliasResolver aliasResolver;
    private final GeminiApiClient geminiApiClient;

    public InsightFacade(SeasonRangeResolver seasonRangeResolver,
                          GithubInsightSnapshotClient githubInsightSnapshotClient,
                          InsightSnapshotBuilder insightSnapshotBuilder,
                          TrackedUserAliasResolver aliasResolver,
                          GeminiApiClient geminiApiClient) {
        this.seasonRangeResolver = seasonRangeResolver;
        this.githubInsightSnapshotClient = githubInsightSnapshotClient;
        this.insightSnapshotBuilder = insightSnapshotBuilder;
        this.aliasResolver = aliasResolver;
        this.geminiApiClient = geminiApiClient;
    }

    @Transactional(readOnly = true)
    public AskResponse ask(AskRequest request) {
        String ouid = request.ouid();
        MatchType matchType = request.matchType();
        Season season = seasonRangeResolver.resolve(request.seasonId());

        InsightSnapshotContent content = githubInsightSnapshotClient.fetch(ouid, matchType)
                .map(file -> new InsightSnapshotContent(file.summaryText(), file.opponentDetailByNickname()))
                .orElseGet(() -> {
                    log.warn("인사이트 스냅샷이 없어 즉석에서 조립합니다: ouid={}, matchType={}, seasonId={}",
                            ouid, matchType, season.getId());
                    return insightSnapshotBuilder.build(ouid, matchType, season.getId());
                });

        String dataSummary = appendMentionedOpponent(content, request.question());
        String userPrompt = dataSummary + "\n\n[질문]\n" + request.question();

        String answer = geminiApiClient.ask(SYSTEM_INSTRUCTION, userPrompt);
        return new AskResponse(answer);
    }

    /**
     * 질문 문장 안에 등장하는 상대(닉네임 또는 그 실명, TrackedUserAliasResolver 참고)를 찾아
     * 그 상대와의 경기별 상세 기록을 덧붙인다. 닉네임이 서로의 부분 문자열인 경우(예: "욱냥" vs
     * "욱냥0I") 짧은 쪽이 먼저 오탐하지 않도록 긴 닉네임부터 검사한다.
     */
    private String appendMentionedOpponent(InsightSnapshotContent content, String question) {
        Optional<Map.Entry<String, String>> mentioned = content.opponentDetailByNickname().entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
                .filter(e -> aliasResolver.mentions(question, e.getKey()))
                .findFirst();

        if (mentioned.isEmpty()) {
            return content.summaryText();
        }
        return content.summaryText() + "\n\n" + mentioned.get().getValue();
    }
}
