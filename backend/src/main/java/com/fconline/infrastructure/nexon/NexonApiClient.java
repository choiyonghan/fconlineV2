package com.fconline.infrastructure.nexon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fconline.domain.match.gateway.NexonMatchData;
import com.fconline.domain.match.gateway.NexonMatchGateway;
import com.fconline.domain.match.gateway.NexonParticipantData;
import com.fconline.domain.match.gateway.NexonParticipantData.ShootEventData;
import com.fconline.domain.match.gateway.NexonParticipantData.SquadEntryData;
import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.MatchType;
import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.match.vo.ShootType;
import com.fconline.domain.shared.exception.NexonApiException;
import com.fconline.domain.shared.exception.RateLimitException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * NexonMatchGateway 포트의 유일한 구현체. v1의 fetchNexonApi(키 로테이션)가
 * fetch_and_store.js/fetch_official.js/build-data.js 세 곳에 중복 정의되어 있던 것을
 * 이 클래스 하나로 대체한다(analysis 6.7).
 *
 * v1이 운영 중인 실제 Supabase(anon key, 읽기전용)에서 match_details 표본 1건을 직접 조회해
 * shoot_detail/player_squad의 실제 원본 구조를 확인했다 — 아래 매핑은 그 표본을 근거로 한다.
 * (goalTotal 계열 등 나머지 matchDetail 필드명은 여전히 v1 DB 컬럼명으로부터의 추정이라
 * TODO로 남겨둔다.)
 *
 * 확인된 shoot_detail[] 원본 형태: {x, y, spId, type(정수), assist(bool), result(정수),
 *   assistX, assistY, hitPost, spGrade, spLevel, goalTime(정수, 아래 참고), spIdType, inPenalty, assistSpId}
 * — "period"라는 별도 필드는 존재하지 않는다(v1 분석 문서가 언급한 별도 필드가 아니라
 *   goalTime 값 자체에 인코딩되어 있는 것으로 보인다: 표본값이 674, 2184, 2747처럼 2^24 미만인
 *   경우와 16778601, 16779065, 16780194처럼 2^24(16777216) 이상인 경우 두 그룹으로 나뉜다 —
 *   상위 바이트가 period, 하위 24비트가 raw 값이라는 가설로 디코딩하되, raw 값의 실제 단위
 *   (초/틱 등)는 검증되지 않았다.
 * TODO(구현 착수 시 추가 검증 필요): goalTime의 정확한 단위, type/result 정수 코드의 의미.
 *
 * 확인된 player_squad[].status 원본 형태(접두사 없음, v1 DB player_squad 그대로):
 *   {goal, assist, tackle, intercept, block, save?, ...dribble/pass/aerial 등 미사용 필드}
 *   — 표본에는 세이브 관련 필드가 아예 없었다(골키퍼가 없는 경기였을 가능성) — save는 0 기본값 유지.
 */
@Component
public class NexonApiClient implements NexonMatchGateway {

    private static final Logger log = LoggerFactory.getLogger(NexonApiClient.class);
    private static final String HEADER_API_KEY = "x-nxopen-api-key";

    private final RestClient restClient;
    private final NexonApiKeyRotator keyRotator;
    private final NexonApiProperties properties;

    public NexonApiClient(RestClient nexonRestClient, NexonApiKeyRotator keyRotator, NexonApiProperties properties) {
        this.restClient = nexonRestClient;
        this.keyRotator = keyRotator;
        this.properties = properties;
    }

    @Override
    public Optional<String> findOuid(String nickname) {
        try {
            JsonNode body = get("/id", Map.of("nickname", nickname));
            JsonNode ouidNode = body.get("ouid");
            return (ouidNode == null || ouidNode.isNull()) ? Optional.empty() : Optional.of(ouidNode.asText());
        } catch (NexonApiException e) {
            if (e.getStatusCode() == 400 || e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<String> findRecentMatchIds(String ouid, MatchType matchType, int limit) {
        JsonNode body = get("/user/match", Map.of(
                "ouid", ouid,
                "matchtype", String.valueOf(matchType.code()),
                "offset", "0",
                "limit", String.valueOf(limit)
        ));

        List<String> ids = new ArrayList<>();
        if (body.isArray()) {
            body.forEach(node -> ids.add(node.asText()));
        }
        return ids;
    }

    @Override
    public NexonMatchData fetchMatchDetail(String matchId) {
        JsonNode body = get("/match-detail", Map.of("matchid", matchId));
        return parseMatchDetail(matchId, body);
    }

    private NexonMatchData parseMatchDetail(String matchId, JsonNode body) {
        Instant matchDate = parseInstant(body.path("matchDate").asText(null));
        MatchType matchType = MatchType.fromCode(body.path("matchType").asInt());

        List<NexonParticipantData> participants = new ArrayList<>();
        JsonNode matchInfo = body.path("matchInfo");
        if (matchInfo.isArray() && matchInfo.size() == 2) {
            JsonNode first = matchInfo.get(0);
            JsonNode second = matchInfo.get(1);
            participants.add(parseParticipant(first, second));
            participants.add(parseParticipant(second, first));
        } else {
            log.warn("matchInfo가 예상된 2인 배열 형태가 아닙니다 (matchId={})", matchId);
        }

        return new NexonMatchData(matchId, matchDate, matchType, participants);
    }

    private NexonParticipantData parseParticipant(JsonNode self, JsonNode opponent) {
        JsonNode detail = self.path("matchDetail");

        return new NexonParticipantData(
                self.path("ouid").asText(null),
                opponent.path("ouid").asText(null),
                opponent.path("nickname").asText(null),
                parseResult(detail.path("matchResult").asText(null)),
                detail.path("controller").asText(null),
                detail.path("averageRating").isMissingNode() ? null : detail.path("averageRating").asDouble(),
                detail.path("goalTotal").asInt(0),
                detail.path("goalTotalDisplay").isMissingNode() ? opponent.path("matchDetail").path("goalTotal").asInt(0)
                        : detail.path("goalTotalDisplay").asInt(0),
                detail.path("shootTotal").asInt(0),
                detail.path("shootEffective").asInt(0),
                detail.path("goalInPenalty").asInt(0),
                detail.path("goalOutPenalty").asInt(0),
                detail.path("shootHeading").asInt(0),
                detail.path("ownGoal").asInt(0),
                detail.path("possession").asInt(0),
                detail.path("passTry").asInt(0),
                detail.path("passSuccess").asInt(0),
                detail.path("shortPassTry").asInt(0),
                detail.path("throughPassTry").asInt(0),
                detail.path("throughPassSuccess").asInt(0),
                detail.path("tackleTry").asInt(0),
                detail.path("tackleSuccess").asInt(0),
                detail.path("foul").asInt(0),
                detail.path("yellowCards").asInt(0),
                detail.path("redCards").asInt(0),
                detail.path("offside").isMissingNode() ? null : detail.path("offside").asInt(),
                parseShootEvents(self.path("shoot")),
                parseSquadEntries(self.path("player"))
        );
    }

    /** goalTime 상위 바이트(2^24 자리)를 period로, 하위 24비트를 raw 시간값으로 분리하는 경계값. */
    private static final int GOAL_TIME_PERIOD_UNIT = 1 << 24;

    private List<ShootEventData> parseShootEvents(JsonNode shootNode) {
        List<ShootEventData> events = new ArrayList<>();
        if (shootNode.isArray()) {
            for (JsonNode event : shootNode) {
                Integer rawGoalTime = event.path("goalTime").isMissingNode() ? null : event.path("goalTime").asInt();
                Integer period = rawGoalTime == null ? null : (rawGoalTime / GOAL_TIME_PERIOD_UNIT) + 1;
                // rawGoalTime의 실제 단위(초/틱)가 검증되지 않아 60으로 나눈 값을 "분" 근사치로 사용한다.
                Integer goalTimeMinutes = rawGoalTime == null ? null : (rawGoalTime % GOAL_TIME_PERIOD_UNIT) / 60;

                events.add(new ShootEventData(
                        parseShootType(event.path("type").isMissingNode() ? null : event.path("type").asInt()),
                        parseShootResult(event.path("result").isMissingNode() ? null : event.path("result").asInt()),
                        goalTimeMinutes,
                        period
                ));
            }
        }
        return events;
    }

    private List<SquadEntryData> parseSquadEntries(JsonNode playerNode) {
        List<SquadEntryData> entries = new ArrayList<>();
        if (playerNode.isArray()) {
            for (JsonNode player : playerNode) {
                JsonNode status = player.path("status");
                entries.add(new SquadEntryData(
                        player.path("spId").asText(null),
                        player.path("spPosition").asInt(0),
                        status.path("goal").asInt(0),
                        status.path("assist").asInt(0),
                        status.path("save").asInt(0),
                        status.path("tackle").asInt(0),
                        status.path("intercept").asInt(0),
                        status.path("block").asInt(0)
                ));
            }
        }
        return entries;
    }

    private MatchResult parseResult(String raw) {
        if (raw == null) {
            return MatchResult.DRAW;
        }
        return switch (raw.toLowerCase()) {
            case "승", "win" -> MatchResult.WIN;
            case "패", "lose" -> MatchResult.LOSE;
            default -> MatchResult.DRAW;
        };
    }

    /**
     * shoot_detail[].type은 정수 코드다(실 표본: 1, 2, 3). 정확한 코드-라벨 매핑은 Nexon 공식 문서나
     * 더 많은 표본 없이는 확정할 수 없어 TODO로 남긴다 — 우선 값 자체는 보존하되 라벨은 UNKNOWN 처리.
     */
    private ShootType parseShootType(Integer code) {
        if (code == null) {
            return ShootType.UNKNOWN;
        }
        // TODO(구현 착수 시 검증 필요): 코드 1/2/3 등이 실제로 어떤 슛 유형인지 Nexon 문서로 확정할 것.
        return ShootType.UNKNOWN;
    }

    /**
     * shoot_detail[].result도 정수 코드다(실 표본: 1, 3). result=3이 가장 빈번하게 나타나 GOAL일
     * 가능성이 높지만(표본 6건 중 5건이 3), 확정할 근거는 아니라 TODO로 남긴다.
     */
    private ShootResult parseShootResult(Integer code) {
        if (code == null) {
            return ShootResult.UNKNOWN;
        }
        // TODO(구현 착수 시 검증 필요): 코드 1/3 등이 실제로 GOAL/SAVED/BLOCKED 중 무엇인지 확정할 것.
        return ShootResult.UNKNOWN;
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            return Instant.parse(raw);
        }
    }

    /** 429 발생 시 다음 키로 자동 전환해 재시도한다. 모든 키 소진 시 RateLimitException을 그대로 전파. */
    private JsonNode get(String path, Map<String, String> queryParams) {
        int attempts = 0;
        int maxAttempts = Math.max(1, properties.keys().size());

        while (true) {
            attempts++;
            try {
                sleepBetweenRequests();
                return restClient.get()
                        .uri(uriBuilder -> {
                            var builder = uriBuilder.path(path);
                            queryParams.forEach(builder::queryParam);
                            return builder.build();
                        })
                        .header(HEADER_API_KEY, keyRotator.currentKey())
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 429) {
                    log.warn("Nexon API 429 수신, 다음 키로 전환 시도 (attempt={}/{})", attempts, maxAttempts);
                    keyRotator.markCurrentKeyExhausted();
                    if (attempts >= maxAttempts) {
                        throw new RateLimitException("모든 키 로테이션 후에도 429가 지속됩니다: " + path);
                    }
                    continue;
                }
                throw new NexonApiException("Nexon API 호출 실패: " + path, e.getStatusCode().value(), e);
            }
        }
    }

    private void sleepBetweenRequests() {
        if (properties.requestDelayMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(properties.requestDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
