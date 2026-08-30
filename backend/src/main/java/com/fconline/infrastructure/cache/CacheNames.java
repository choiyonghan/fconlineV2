package com.fconline.infrastructure.cache;

/** {@code @Cacheable}에 쓰는 캐시 이름 상수 — RedisCacheConfig의 TTL 설정과 문자열이 어긋나지 않게 한 곳에 모아둔다. */
public final class CacheNames {

    /** RecordFacade의 조회 API(전적/전체 선수/히트맵/어시스트체인/매치 상세 등) — TTL 5분. */
    public static final String RECORDS = "records";
    /** InsightFacade.ask()의 (ouid,matchType,seasonId,question) 동일 질문 응답 — TTL 10분. */
    public static final String INSIGHT_ANSWERS = "insight-answers";

    private CacheNames() {
    }
}
