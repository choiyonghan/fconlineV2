package com.fconline.infrastructure.cache;

/**
 * {@code @Cacheable}에 쓰는 캐시 이름 상수 — RedisCacheConfig의 TTL/직렬화 설정과 문자열이
 * 어긋나지 않게 한 곳에 모아둔다.
 *
 * RecordFacade의 조회 메서드마다 캐시 이름을 따로 둔다(전부 "records" 하나로 공유하지 않음) —
 * Spring 캐시 키는 메서드 자체가 아니라 파라미터만으로 만들어지는데(SimpleKeyGenerator), 파라미터
 * 모양이 같은 메서드가 하나의 캐시 이름을 같이 쓰면 서로 다른 메서드의 결과가 같은 키로 충돌할
 * 수 있다(실제로 getAllPlayers와 getConcededShotHeatmap이 둘 다 (ouid,matchType,seasonId,
 * opponentOuid,teamPeriodId) 5개 파라미터라 값이 같으면 키가 완전히 같아짐 — 프론트가 같은 화면
 * 로드에서 같은 필터로 두 API를 같이 부르므로 실제로 벌어질 수 있는 상황이었다). 캐시 이름을
 * 메서드별로 분리하면(Redis 키가 "캐시이름::파라미터"로 네임스페이스됨) 이 충돌이 원천적으로
 * 사라지고, 부수적으로 각 캐시를 정확한 반환 타입으로 직렬화할 수 있어(RedisCacheConfig) 여러
 * 타입을 한 캐시에 욱여넣기 위한 범용 폴리모픽 타입 정보(@class) 오버헤드도 없어진다.
 */
public final class CacheNames {

    // 전부 조회성 데이터라 TTL 3시간으로 통일(RedisCacheConfig.TTL, 요청).

    // --- RecordFacade ---
    public static final String OVERALL_RECORD = "overall-record";
    public static final String ALL_PLAYERS = "all-players";
    public static final String SHOT_HEATMAP = "shot-heatmap";
    public static final String CONCEDED_SHOT_HEATMAP = "conceded-shot-heatmap";
    public static final String MATCH_SHOTS = "match-shots";
    public static final String MATCH_SQUAD = "match-squad";
    public static final String MATCH_STATS = "match-stats";
    public static final String ASSIST_CHAINS = "assist-chains";
    public static final String PLAYER_GRADES = "player-grades";
    public static final String RECENT_MATCHES = "recent-matches";

    /** InsightFacade.ask()의 (ouid,matchType,seasonId,question) 동일 질문 응답. */
    public static final String INSIGHT_ANSWERS = "insight-answers";

    // --- OpponentFacade ---
    public static final String OPPONENTS = "opponents";

    // --- UserFacade/SeasonFacade ---
    public static final String TRACKED_USERS = "tracked-users";
    public static final String SEASONS = "seasons";
    public static final String TEAM_PERIODS = "team-periods";

    private CacheNames() {
    }
}
