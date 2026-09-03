package com.fconline.app.search.dto;

import com.fconline.app.common.dto.MatchTallyResponse;
import com.fconline.app.record.dto.TopPlayerResponse;
import java.util.List;

/**
 * 유저 검색(추적 대상 9명이 아닌 임의 닉네임) 결과. RecordFacade가 DB에서 조립하는
 * OverallRecordResponse와 성격이 같지만(전적/평점/점유율/xG/xA/선수 기여도), 데이터 출처가
 * DB가 아니라 Nexon 실시간 조회라는 점이 다르다 — 그래서 별도 DTO로 둔다(SearchFacade 참고).
 *
 * sampleSize = 실제로 집계에 쓰인 매치 수(요청한 limit보다 적을 수 있음 — CUSTOM은
 * matchEndType≠0인 비정상 종료 경기를 집계에서 제외하기 때문, RecordFacade의 baseWhere와
 * 동일한 규칙). assistsFor는 "실제 어시스트 vs xA값" 타일용 팀 전체 합계(topPlayers 각 항목의
 * assists 합과 같은 값이지만, 클라이언트가 매번 다시 더할 필요 없게 서버가 미리 계산해서 준다).
 * topPlayers는 TopPlayerResponse를 그대로 재사용한다 — xg/xa 포함 필드가 이미 완전히 같아서
 * 새로 만들 이유가 없다.
 */
public record SearchResultResponse(
        String ouid,
        String nickname,
        String matchType,
        int sampleSize,
        MatchTallyResponse tally,
        long assistsFor,
        Double avgRating,
        Double avgPossession,
        double xgFor,
        double xaFor,
        double finishing,
        List<TopPlayerResponse> topPlayers,
        List<SearchRecentMatchResponse> recentMatches
) {
}
