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

    // 전부 조회성 데이터라 TTL 30분으로 통일(RedisCacheConfig.TTL, 요청).

    // --- RecordFacade ---
    public static final String OVERALL_RECORD = "overall-record";
    public static final String ALL_PLAYERS = "all-players";
    public static final String SHOT_HEATMAP = "shot-heatmap";
    public static final String CONCEDED_SHOT_HEATMAP = "conceded-shot-heatmap";
    public static final String ASSISTED_SHOT_HEATMAP = "assisted-shot-heatmap";
    public static final String MATCH_SHOTS = "match-shots";
    public static final String MATCH_SQUAD = "match-squad";
    public static final String MATCH_STATS = "match-stats";
    public static final String ASSIST_CHAINS = "assist-chains";
    public static final String PLAYER_GRADES = "player-grades";
    public static final String RECENT_MATCHES = "recent-matches";

    /** InsightFacade.ask()의 (ouid,matchType,seasonId,question) 동일 질문 응답. */
    public static final String INSIGHT_ANSWERS = "insight-answers";
    /** PersonalityReportClient.fetch()의 실명별 카톡 성격 리포트(.md) 원문. */
    public static final String PERSONALITY_REPORTS = "personality-reports";

    // --- OpponentFacade ---
    public static final String OPPONENTS = "opponents";

    // --- UserFacade/SeasonFacade ---
    public static final String TRACKED_USERS = "tracked-users";
    public static final String SEASONS = "seasons";
    public static final String TEAM_PERIODS = "team-periods";

    // --- SearchFacade(추적 대상이 아닌 임의 유저 검색, DB 미사용 — 전부 Nexon 실시간 조회) ---
    // RecordFacade와 마찬가지로 화면(API)마다 캐시 이름을 분리한다 — 이 클래스 위쪽 주석의
    // 키 충돌 경고와 동일한 이유. getOverall/getPlayers/getShotHeatmap 등은 전부 같은 파라미터
    // 모양(nickname,matchType,limit)이라 특히 더 그렇다.
    public static final String SEARCH_OVERALL = "search-overall";
    public static final String SEARCH_PLAYERS = "search-players";
    public static final String SEARCH_SHOT_HEATMAP = "search-shot-heatmap";
    public static final String SEARCH_CONCEDED_SHOT_HEATMAP = "search-conceded-shot-heatmap";
    public static final String SEARCH_ASSISTED_SHOT_HEATMAP = "search-assisted-shot-heatmap";
    public static final String SEARCH_ASSIST_CHAINS = "search-assist-chains";
    public static final String SEARCH_RECENT_MATCHES = "search-recent-matches";

    /** SearchMatchDetailCache.findOuid — 닉네임은 유료 변경이 아니면 거의 안 바뀌는 데이터라
     * 다른 조회성 캐시(TTL 30분)보다 길게 잡는다(24시간, RedisCacheConfig에서 별도 지정). */
    public static final String SEARCH_OUID = "search-ouid";
    /** SearchMatchDetailCache.findRecentMatchIds — 새 매치가 방금 끝났을 수도 있어 다른 캐시와
     * 같은 TTL(30분)로 둔다. */
    public static final String SEARCH_RECENT_MATCH_IDS = "search-recent-match-ids";
    /**
     * matchId 1건의 Nexon match-detail 원본(NexonMatchData) — 매치 결과는 한 번 끝나면 다시
     * 안 바뀌므로 다른 조회성 캐시(TTL 30분)보다 훨씬 길게 잡는다(RedisCacheConfig에서 별도
     * Duration 지정). 위 SEARCH_* 조회 API들이 전부 이 캐시 하나만 거쳐서 Nexon match-detail을
     * 읽으므로, 한 번 조회된 매치는 어느 API를 통해서든 재사용된다 — SearchMatchDetailCache 참고.
     */
    public static final String SEARCH_MATCH_DETAIL = "search-match-detail";

    private CacheNames() {
    }
}
