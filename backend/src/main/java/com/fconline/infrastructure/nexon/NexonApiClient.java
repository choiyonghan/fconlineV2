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
 * TODO(구현 착수 시 검증 필요): 아래 JsonNode 필드 경로는 v1 코드가 최종적으로 만든
 * DB 컬럼 이름을 근거로 추정한 것이며, Nexon match-detail 원본 응답의 실제 키 이름과
 * 다를 수 있다. 실제 응답을 1회 확인해 parseParticipant/parseShootEvent/parseSquadEntry를 보정할 것.
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

    private List<ShootEventData> parseShootEvents(JsonNode shootNode) {
        List<ShootEventData> events = new ArrayList<>();
        if (shootNode.isArray()) {
            for (JsonNode event : shootNode) {
                events.add(new ShootEventData(
                        parseShootType(event.path("type").asText(null)),
                        parseShootResult(event.path("result").asText(null)),
                        event.path("goalTime").isMissingNode() ? null : event.path("goalTime").asInt(),
                        event.path("period").isMissingNode() ? null : event.path("period").asInt()
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
                        status.path("spGoal").asInt(0),
                        status.path("spAssist").asInt(0),
                        status.path("spSave").asInt(0),
                        status.path("spTackle").asInt(0),
                        status.path("spIntercept").asInt(0),
                        status.path("spBlock").asInt(0)
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

    private ShootType parseShootType(String raw) {
        if (raw == null) {
            return ShootType.UNKNOWN;
        }
        return switch (raw) {
            case "로빙 슛", "LOBBING" -> ShootType.LOBBING;
            case "파워 샷", "POWER" -> ShootType.POWER;
            case "헤딩 슛", "HEADING" -> ShootType.HEADING;
            case "발리 슛", "VOLLEY" -> ShootType.VOLLEY;
            case "페널티킥", "PK" -> ShootType.PENALTY_KICK;
            case "프리킥", "FREEKICK" -> ShootType.FREE_KICK;
            case "일반 슛", "NORMAL" -> ShootType.NORMAL;
            default -> ShootType.UNKNOWN;
        };
    }

    private ShootResult parseShootResult(String raw) {
        if (raw == null) {
            return ShootResult.UNKNOWN;
        }
        return switch (raw.toUpperCase()) {
            case "GOAL" -> ShootResult.GOAL;
            case "SAVED", "SAVE" -> ShootResult.SAVED;
            case "BLOCKED", "BLOCK" -> ShootResult.BLOCKED;
            case "POST" -> ShootResult.POST;
            case "OFF_TARGET", "MISS" -> ShootResult.OFF_TARGET;
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
