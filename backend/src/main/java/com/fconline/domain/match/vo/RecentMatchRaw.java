package com.fconline.domain.match.vo;

import java.time.Instant;

/**
 * "최근 경기"/"상대별 경기 목록" 리스트 화면 전용 경량 프로젝션.
 * MatchDetail 엔티티 전체(특히 shoot_detail/player_squad/raw_participant jsonb 원본 3종)를
 * 끌어오지 않고, 화면에 실제로 필요한 스칼라 컬럼만 선택해 목록 조회 I/O를 크게 줄인다.
 */
public record RecentMatchRaw(
        String matchId,
        Instant matchDate,
        String opponentNickname,
        String opponentOuid,
        MatchResult result,
        Integer goalsFor,
        Integer goalsAgainst,
        Double averageRating,
        Integer possession,
        Integer shootTotal,
        Integer effectiveShoot,
        Integer passTry,
        Integer passSuccess,
        Integer tackleTry,
        Integer tackleSuccess,
        Integer foul,
        Integer yellowCards,
        Integer redCards
) {
}
