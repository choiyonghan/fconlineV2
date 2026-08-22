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
 * shoot_detail/player_squad의 실제 원본 구조를 1차로 확인했고, 이후 Nexon 공식 match-detail
 * API 문서로 필드명/코드표를 전부 확정했다 (matchInfo[].{matchDetail,shoot,pass,defence,shootDetail,player}
 * 5개 형제 객체로 통계가 나뉘어 있다는 것과 shootDetail[].type/result 코드 전체 목록 포함).
 *
 * goalTime 인코딩: 상위 비트가 period(2^24 단위, floor(raw/2^24)+1 = 1~5), 하위 24비트가
 * 해당 period 시작 시점부터의 경과 초(raw % 2^24)다 — 이 클래스는 이를 그대로
 * (period, 경과분) 두 컬럼으로 나눠 저장한다(공식 문서는 여기에 45/90/105/120분을 더해
 * "매치 전체 기준 경과초"로 합치는 방식을 안내하지만, period 컬럼이 이미 별도로 있어
 * 정규화 관점에서 더하지 않는 쪽을 택했다).
 *
 * player_squad[].status 원본 형태(접두사 없음, v1 DB player_squad 그대로):
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
        // Nexon match-detail 응답은 참가자당 matchDetail/shoot/pass/defence 4개의 형제 객체로
        // 통계를 나눠 담는다 — 하나의 "detail" 객체에 다 들어있는 게 아니다. 각 필드가 실제로
        // 어느 객체에 속하는지는 공식 API 문서로 확정했다(예전엔 전부 matchDetail에서 읽어
        // goalTotal/passTry/tackleTry 등이 항상 0으로 저장되고 있었다).
        JsonNode matchDetail = self.path("matchDetail");
        JsonNode shoot = self.path("shoot");
        JsonNode pass = self.path("pass");
        JsonNode defence = self.path("defence");

        return new NexonParticipantData(
                self.path("ouid").asText(null),
                opponent.path("ouid").asText(null),
                opponent.path("nickname").asText(null),
                parseResult(matchDetail.path("matchResult").asText(null)),
                matchDetail.path("controller").asText(null),
                matchDetail.path("averageRating").isMissingNode() ? null : matchDetail.path("averageRating").asDouble(),
                shoot.path("goalTotal").asInt(0),
                shoot.path("goalTotalDisplay").isMissingNode() ? shoot.path("goalTotal").asInt(0)
                        : shoot.path("goalTotalDisplay").asInt(0),
                shoot.path("shootTotal").asInt(0),
                shoot.path("effectiveShootTotal").asInt(0),
                shoot.path("goalInPenalty").asInt(0),
                shoot.path("goalOutPenalty").asInt(0),
                shoot.path("shootHeading").asInt(0),
                shoot.path("ownGoal").asInt(0),
                matchDetail.path("possession").asInt(0),
                pass.path("passTry").asInt(0),
                pass.path("passSuccess").asInt(0),
                pass.path("shortPassTry").asInt(0),
                pass.path("throughPassTry").asInt(0),
                pass.path("throughPassSuccess").asInt(0),
                defence.path("tackleTry").asInt(0),
                defence.path("tackleSuccess").asInt(0),
                matchDetail.path("foul").asInt(0),
                matchDetail.path("yellowCards").asInt(0),
                matchDetail.path("redCards").asInt(0),
                matchDetail.path("offsideCount").isMissingNode() ? null : matchDetail.path("offsideCount").asInt(),
                parseShootEvents(self.path("shootDetail")),
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
                Integer goalTimeMinutes = rawGoalTime == null ? null : (rawGoalTime % GOAL_TIME_PERIOD_UNIT) / 60;

                // assistSpId는 어시스트가 없으면 -1 센티널이 온다 — null로 정규화한다.
                String assistSpId = event.path("assistSpId").isMissingNode() ? null : event.path("assistSpId").asText(null);
                if ("-1".equals(assistSpId)) {
                    assistSpId = null;
                }

                events.add(new ShootEventData(
                        parseShootType(event.path("type").isMissingNode() ? null : event.path("type").asInt()),
                        parseShootResult(event.path("result").isMissingNode() ? null : event.path("result").asInt()),
                        goalTimeMinutes,
                        period,
                        event.path("spId").isMissingNode() ? null : event.path("spId").asText(null),
                        event.path("spGrade").isMissingNode() ? null : event.path("spGrade").asInt(),
                        event.path("spLevel").isMissingNode() ? null : event.path("spLevel").asInt(),
                        event.path("spIdType").isMissingNode() ? null : event.path("spIdType").asBoolean(),
                        event.path("x").isMissingNode() ? null : event.path("x").asDouble(),
                        event.path("y").isMissingNode() ? null : event.path("y").asDouble(),
                        event.path("assist").isMissingNode() ? null : event.path("assist").asBoolean(),
                        assistSpId,
                        event.path("assistX").isMissingNode() ? null : event.path("assistX").asDouble(),
                        event.path("assistY").isMissingNode() ? null : event.path("assistY").asDouble(),
                        event.path("hitPost").isMissingNode() ? null : event.path("hitPost").asBoolean(),
                        event.path("inPenalty").isMissingNode() ? null : event.path("inPenalty").asBoolean()
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

    /** shootDetail[].type 코드 → ShootType. Nexon 공식 문서로 확정된 매핑(1~12). */
    private ShootType parseShootType(Integer code) {
        if (code == null) {
            return ShootType.UNKNOWN;
        }
        return switch (code) {
            case 1 -> ShootType.NORMAL;
            case 2 -> ShootType.FINESSE;
            case 3 -> ShootType.HEADING;
            case 4 -> ShootType.LOBBING;
            case 5 -> ShootType.FLARE;
            case 6 -> ShootType.LOW;
            case 7 -> ShootType.VOLLEY;
            case 8 -> ShootType.FREE_KICK;
            case 9 -> ShootType.PENALTY_KICK;
            case 10 -> ShootType.KNUCKLE;
            case 11 -> ShootType.BICYCLE_KICK;
            case 12 -> ShootType.POWER;
            default -> ShootType.UNKNOWN;
        };
    }

    /** shootDetail[].result 코드 → ShootResult. Nexon 공식 문서로 확정된 매핑(1 ontarget, 2 offtarget, 3 goal). */
    private ShootResult parseShootResult(Integer code) {
        if (code == null) {
            return ShootResult.UNKNOWN;
        }
        return switch (code) {
            case 1 -> ShootResult.ON_TARGET;
            case 2 -> ShootResult.OFF_TARGET;
            case 3 -> ShootResult.GOAL;
            default -> ShootResult.UNKNOWN;
        };
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
