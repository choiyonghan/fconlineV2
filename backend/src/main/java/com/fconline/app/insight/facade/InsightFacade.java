package com.fconline.app.insight.facade;

import com.fconline.app.common.SeasonRangeResolver;
import com.fconline.app.insight.dto.AskRequest;
import com.fconline.app.insight.dto.AskResponse;
import com.fconline.app.insight.dto.InsightSnapshotContent;
import com.fconline.app.user.dto.TrackedUserResponse;
import com.fconline.app.user.facade.UserFacade;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.season.Season;
import com.fconline.infrastructure.cache.CacheNames;
import com.fconline.infrastructure.groq.GroqApiClient;
import com.fconline.infrastructure.insight.GithubInsightSnapshotClient;
import com.fconline.infrastructure.personality.PersonalityReportClient;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자연어 질문 + 현재 조회 컨텍스트(유저/매치타입/시즌)를 받아 AI가 분석한 답변을 반환한다.
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
 *
 * 전적 데이터와는 별개로, Claude Code가 카톡 대화를 직접 읽고 손으로 써둔 성격 리포트가 있는
 * 유저(전원은 아님)는 그 내용도 PersonalityReportClient로 붙여서, AI가 그 사람 특유의
 * 말투/성향을 반영한 더 캐릭터에 맞는 답변을 하게 한다(요청). FC Online 추적 대상이 아닌(ouid
 * 없는) 친구도 리포트가 있으면 질문에 이름이 언급될 때 ExtraPersonalityPeople로 같은 방식으로
 * 붙인다 — FC 전적과 무관한 잡담 질문이어도 마찬가지(요청).
 */
@Component
public class InsightFacade {

    private static final Logger log = LoggerFactory.getLogger(InsightFacade.class);

    private static final String SYSTEM_INSTRUCTION = """
            당신은 FC Online(축구 게임) 전적 데이터를 분석해주는, 눈치 없이 웃긴 축구 해설가
            컨셉의 도우미입니다. 친구들끼리 재미로 보는 전적 리포트이니 딱딱한 보고서체는
            피하고, 드립·비유·과장된 리액션을 섞어 유쾌하게 답변하세요. 성적이 안 좋으면
            돌려 말하지 말고 재치있게 팩폭하세요(예: 다실점이 많으면 "수비는 잠시 꺼두셨나요"
            식으로) — 웃기되 놀리는 데서 그치지 말고 전적 관련 답변에는 항상 아래 데이터의
            구체적인 수치(전적, 평점, 점유율, 슈팅/패스/드리블/공중볼 정확도, 상대 전적 등)를
            근거로 드세요. "보고서"나 "플레이스타일" 같은 분석을 요청하면 제공된 데이터를
            폭넓게 활용해(선수단 기여도, 팀 전체 공격 지표, 득점 유형/시간대, 상대별 전적 등)
            여러 각도로 상세히 답변하세요.
            질문이 "현재 선택된 유저" 관점 데이터뿐 아니라 다른 유저 자신의 데이터도 함께
            주어지면, 두 유저를 그 각자의 데이터로 직접 비교하세요 — 다른 유저를 통해
            간접적으로 추론하지 말고, 직접 맞붙은 기록이 있으면 그걸 최우선 근거로 쓰세요.
            질문이 "등록된 유저 전체"/"모든 유저" 기준 순위나 비교를 원하면(현재 선택된 유저
            한 명 기준이 아니라), 반드시 맨 앞의 "전체 유저 종합 전적 순위" 섹션(각자 전체 상대
            합산 기준)을 근거로 쓰세요 — 현재 선택된 유저의 상대별 전적(그 유저 한 명과 붙었을
            때의 상성)만 보고 다른 유저끼리를 간접 비교하면 안 됩니다.
            성격 리포트가 함께 주어지면, 그 사람 특유의 말투·자주 쓰는 표현·성향을 답변에
            자연스럽게 녹여서 더 그 사람다운 답변을 만드세요 — 이 정보를 어디서 얻었는지는
            언급하지 말고(예: "대화를 분석해보니", "기록을 뒤져본 결과" 같은 표현 금지),
            그냥 원래 알고 있었다는 듯 자연스럽게 쓰세요. 사생활이나 민감한 개인사를 캐묻거나
            먼저 들추지 말고, 질문 맥락(전적/성향 관련)에 실제로 도움이 될 때만 참고하세요.
            질문이 FC Online 전적과 무관한 일반 상식/잡담이면(데이터에 없다고 거절하지 말고)
            평소 알고 있는 지식으로 자유롭게 답변하되, 위와 같은 캐주얼하고 위트있는 말투는
            그대로 유지하세요. 다만 전적 관련 질문에 한해서는 절대 근거 없이 수치를 지어내지
            말고, 모르면 모른다고 솔직히 답하세요.
            존댓말은 유지하되 말투는 캐주얼하고 재미있게 하세요.
            서식은 가벼운 마크다운만 쓰세요: 강조할 핵심 수치/문구는 **굵게**, 항목을 나열할
            땐 "- " 목록을 쓰고, HTML 태그는 절대 쓰지 마세요(마크다운만 렌더링됩니다).
            """;

    private final SeasonRangeResolver seasonRangeResolver;
    private final GithubInsightSnapshotClient githubInsightSnapshotClient;
    private final InsightSnapshotBuilder insightSnapshotBuilder;
    private final TrackedUserAliasResolver aliasResolver;
    private final UserFacade userFacade;
    private final GroqApiClient groqApiClient;
    private final PersonalityReportClient personalityReportClient;
    private final ExtraPersonalityPeople extraPersonalityPeople;

    public InsightFacade(SeasonRangeResolver seasonRangeResolver,
                          GithubInsightSnapshotClient githubInsightSnapshotClient,
                          InsightSnapshotBuilder insightSnapshotBuilder,
                          TrackedUserAliasResolver aliasResolver,
                          UserFacade userFacade,
                          GroqApiClient groqApiClient,
                          PersonalityReportClient personalityReportClient,
                          ExtraPersonalityPeople extraPersonalityPeople) {
        this.seasonRangeResolver = seasonRangeResolver;
        this.githubInsightSnapshotClient = githubInsightSnapshotClient;
        this.insightSnapshotBuilder = insightSnapshotBuilder;
        this.aliasResolver = aliasResolver;
        this.userFacade = userFacade;
        this.groqApiClient = groqApiClient;
        this.personalityReportClient = personalityReportClient;
        this.extraPersonalityPeople = extraPersonalityPeople;
    }

    /**
     * Redis 캐시 대상(TTL 3시간, RedisCacheConfig.TTL) — 질문 원문(AskRequest.question 포함)까지 키로
     * 묶어서, 완전히 같은 질문이 짧은 시간 안에 다시 들어올 때만 히트한다(같은 사람이 새로고침/
     * 중복 클릭하거나, 여러 명이 예시 질문을 그대로 눌러보는 경우). 무료 Groq 티어는 분당/일당
     * 호출 한도가 있어 이런 중복 호출을 줄이는 게 records 캐시보다 오히려 더 의미 있다.
     */
    @Cacheable(CacheNames.INSIGHT_ANSWERS)
    @Transactional(readOnly = true)
    public AskResponse ask(AskRequest request) {
        String ouid = request.ouid();
        MatchType matchType = request.matchType();
        String question = request.question();
        Season season = seasonRangeResolver.resolve(request.seasonId());

        InsightSnapshotContent primary = loadContent(ouid, matchType, season.getId());
        StringBuilder dataSummary = new StringBuilder(appendMentionedOpponent(primary, question));

        List<TrackedUserResponse> allTrackedUsers = userFacade.listTrackedUsers();
        String primaryNickname = allTrackedUsers.stream()
                .filter(u -> u.ouid().equals(ouid))
                .map(TrackedUserResponse::nickname)
                .findFirst()
                .orElse(null);
        appendPersonalityReport(dataSummary, ouid, primaryNickname);

        List<TrackedUserResponse> otherMentioned = allTrackedUsers.stream()
                .filter(u -> !u.ouid().equals(ouid))
                .filter(u -> aliasResolver.mentions(question, u.nickname()))
                .toList();

        for (TrackedUserResponse other : otherMentioned) {
            InsightSnapshotContent otherContent = loadContent(other.ouid(), matchType, season.getId());
            dataSummary.append("\n\n[질문에서 언급된 다른 추적 유저: ").append(labelOf(other.nickname()))
                    .append(" 본인 데이터]\n").append(otherContent.summaryText());
            appendPersonalityReport(dataSummary, other.ouid(), other.nickname());
        }

        // FC Online 추적 대상이 아닌(ouid 없는) 친구도 질문에 이름이 언급되면 성격 리포트를
        // 붙인다 — FC 전적과 무관한 잡담 질문이어도(요청) 마찬가지로 적용.
        extraPersonalityPeople.all().forEach((name, storageKey) -> {
            if (question.contains(name)) {
                personalityReportClient.fetch(storageKey).ifPresent(text ->
                        dataSummary.append("\n\n[").append(name)
                                .append(" 관련 배경 정보 — FC Online 추적 대상 아님, 말투/캐릭터 참고용]\n")
                                .append(text));
            }
        });

        String userPrompt = dataSummary + "\n\n[질문]\n" + question;

        String answer = groqApiClient.ask(SYSTEM_INSTRUCTION, userPrompt);
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

    /**
     * Supabase Storage에 리포트가 실제로 올라와 있는(ouid.md 키) 유저만 성격 리포트를 덧붙인다 —
     * 전원이 다 있는 게 아니라 일부는 조용히 생략된다. 파일 키는 ouid를 쓴다(PersonalityReportClient
     * 주석 참고 — 한글 실명을 키로 쓰면 Supabase Storage가 InvalidKey로 거절해서 겪은 문제).
     */
    private void appendPersonalityReport(StringBuilder dataSummary, String ouid, String nickname) {
        if (nickname == null) {
            return;
        }
        String realName = aliasResolver.realNameOf(nickname);
        String label = realName == null ? nickname : nickname + "(" + realName + ")";
        personalityReportClient.fetch(ouid).ifPresent(text ->
                dataSummary.append("\n\n[").append(label)
                        .append(" 관련 배경 정보 — 말투/캐릭터 참고용]\n").append(text));
    }

    private String labelOf(String nickname) {
        String realName = aliasResolver.realNameOf(nickname);
        return realName == null ? nickname : nickname + "(" + realName + ")";
    }
}
