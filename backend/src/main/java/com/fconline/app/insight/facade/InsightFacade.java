package com.fconline.app.insight.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.insight.dto.AskRequest;
import com.fconline.app.insight.dto.AskResponse;
import com.fconline.app.insight.dto.InsightSnapshotContent;
import com.fconline.app.user.dto.TrackedUserResponse;
import com.fconline.app.user.facade.UserFacade;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.season.Season;
import com.fconline.infrastructure.gemini.GeminiApiClient;
import com.fconline.infrastructure.insight.GithubInsightSnapshotClient;
import java.util.Comparator;
import java.util.List;
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
 *
 * 질문이 "현재 선택된 유저" 말고 다른 추적 유저(닉네임 또는 실명)를 함께 언급하면, 그 유저
 * 자신의 스냅샷도 따로 불러와 붙여준다 — 그래야 "A랑 B 중 누가 잘해?" 같은, 현재 선택된
 * 유저 관점이 아닌 제3자 간 비교 질문에도 각자의 실제 데이터로 답할 수 있다(그전엔 현재
 * 선택된 유저 기준 상대 전적만 있어서, 관계없는 두 유저를 비교하려면 공통 상대를 거쳐 추론하는
 * 식으로 답이 새곤 했다).
 */
@Component
public class InsightFacade {

    private static final Logger log = LoggerFactory.getLogger(InsightFacade.class);

    private static final String SYSTEM_INSTRUCTION = """
            당신은 FC Online(축구 게임) 전적 데이터를 분석해주는, 눈치 없이 웃긴 축구 해설가
            컨셉의 도우미입니다. 친구들끼리 재미로 보는 전적 리포트이니 딱딱한 보고서체는
            피하고, 드립·비유·과장된 리액션을 섞어 유쾌하게 답변하세요. 성적이 안 좋으면
            돌려 말하지 말고 재치있게 팩폭하세요(예: 다실점이 많으면 "수비는 잠시 꺼두셨나요"
            식으로) — 웃기되 놀리는 데서 그치지 말고 항상 아래 데이터의 구체적인 수치(전적,
            평점, 점유율, 슈팅/패스/드리블/공중볼 정확도, 상대 전적 등)를 근거로 드세요.
            아래에 제공되는 데이터만 근거로 답변하고, 제공되지 않은 정보는 추측하지 마세요.
            데이터에 없는 걸 물어보면 모른다고 솔직히 답하세요. "보고서"나 "플레이스타일"
            같은 분석을 요청하면 제공된 데이터를 폭넓게 활용해(선수단 기여도, 팀 전체
            공격 지표, 득점 유형/시간대, 상대별 전적 등) 여러 각도로 상세히 답변하세요.
            질문이 "현재 선택된 유저" 관점 데이터뿐 아니라 다른 유저 자신의 데이터도 함께
            주어지면, 두 유저를 그 각자의 데이터로 직접 비교하세요 — 다른 유저를 통해
            간접적으로 추론하지 말고, 직접 맞붙은 기록이 있으면 그걸 최우선 근거로 쓰세요.
            존댓말은 유지하되 말투는 캐주얼하고 재미있게 하세요.
            """;

    private final SeasonRangeResolver seasonRangeResolver;
    private final GithubInsightSnapshotClient githubInsightSnapshotClient;
    private final InsightSnapshotBuilder insightSnapshotBuilder;
    private final TrackedUserAliasResolver aliasResolver;
    private final UserFacade userFacade;
    private final GeminiApiClient geminiApiClient;

    public InsightFacade(SeasonRangeResolver seasonRangeResolver,
                          GithubInsightSnapshotClient githubInsightSnapshotClient,
                          InsightSnapshotBuilder insightSnapshotBuilder,
                          TrackedUserAliasResolver aliasResolver,
                          UserFacade userFacade,
                          GeminiApiClient geminiApiClient) {
        this.seasonRangeResolver = seasonRangeResolver;
        this.githubInsightSnapshotClient = githubInsightSnapshotClient;
        this.insightSnapshotBuilder = insightSnapshotBuilder;
        this.aliasResolver = aliasResolver;
        this.userFacade = userFacade;
        this.geminiApiClient = geminiApiClient;
    }

    @Transactional(readOnly = true)
    public AskResponse ask(AskRequest request) {
        String ouid = request.ouid();
        MatchType matchType = request.matchType();
        String question = request.question();
        Season season = seasonRangeResolver.resolve(request.seasonId());

        InsightSnapshotContent primary = loadContent(ouid, matchType, season.getId());
        StringBuilder dataSummary = new StringBuilder(appendMentionedOpponent(primary, question));

        List<TrackedUserResponse> otherMentioned = userFacade.listTrackedUsers().stream()
                .filter(u -> !u.ouid().equals(ouid))
                .filter(u -> aliasResolver.mentions(question, u.nickname()))
                .toList();

        for (TrackedUserResponse other : otherMentioned) {
            InsightSnapshotContent otherContent = loadContent(other.ouid(), matchType, season.getId());
            dataSummary.append("\n\n[질문에서 언급된 다른 추적 유저: ").append(labelOf(other.nickname()))
                    .append(" 본인 데이터]\n").append(otherContent.summaryText());
        }

        String userPrompt = dataSummary + "\n\n[질문]\n" + question;

        String answer = geminiApiClient.ask(SYSTEM_INSTRUCTION, userPrompt);
        return new AskResponse(answer);
    }

    /** 스냅샷 파일을 읽고, 없으면(첫 실행 등) 즉석 조립으로 폴백한다. */
    private InsightSnapshotContent loadContent(String ouid, MatchType matchType, Long seasonId) {
        return githubInsightSnapshotClient.fetch(ouid, matchType)
                .map(file -> new InsightSnapshotContent(file.summaryText(), file.opponentDetailByNickname()))
                .orElseGet(() -> {
                    log.warn("인사이트 스냅샷이 없어 즉석에서 조립합니다: ouid={}, matchType={}, seasonId={}",
                            ouid, matchType, seasonId);
                    return insightSnapshotBuilder.build(ouid, matchType, seasonId);
                });
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

    private String labelOf(String nickname) {
        String realName = aliasResolver.realNameOf(nickname);
        return realName == null ? nickname : nickname + "(" + realName + ")";
    }
}
