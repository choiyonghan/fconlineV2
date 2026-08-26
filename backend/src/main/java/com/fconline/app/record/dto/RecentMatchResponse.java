package com.fconline.app.record.dto;

import java.time.Instant;

/** OpponentMatchResponse와 필드는 같지만 상대 무관 "내 최신 경기" 목록이라 opponentNickname을 포함한다. */
public record RecentMatchResponse(
        String matchId,
        Instant matchDate,
        String opponentNickname,
        String opponentOuid,
        String result,
        int goalsFor,
        int goalsAgainst,
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
        Integer redCards,
        /** 이 매치 날짜 시점에 이 유저가 쓰던 팀(user_team_periods, 사용자가 직접 조사해 채움).
         *  해당 기간 데이터가 없으면 null — 프론트는 이 경우 팀명을 생략한다. */
        String team,
        /** 상대의 같은 시점 팀 — 상대도 추적 대상이고 그 기간 데이터가 있어야 채워진다. */
        String opponentTeam
) {
}
