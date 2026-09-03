package com.fconline.app.search.dto;

import com.fconline.app.record.dto.RecentMatchResponse;

/**
 * 매치 상세 모달 "⚖️ 상대 팀 비교"용 — 검색은 항상 Nexon match-detail 원본을 통째로 캐싱해두므로
 * (SearchMatchDetailCache), DB 기반 RecordFacade.getMatchStats처럼 "상대가 추적 대상이어야만"
 * 상대 스탯이 채워지는 제약이 없다 — 매치가 존재하면 opponent는 항상 채워진다(찾는 ouid가
 * 이 매치에 아예 없는 경우에만 null). team/opponentTeam("사용한 팀")은 user_team_periods가
 * DB 전용 데이터라 검색 대상엔 적용할 방법이 없어 항상 null.
 */
public record SearchMatchStatsResponse(RecentMatchResponse mine, RecentMatchResponse opponent) {
}
