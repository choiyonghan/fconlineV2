package com.fconline.domain.match.vo;

/**
 * 매치 1건 안의 스쿼드 엔트리 1명 평점 — 집계 없는 원시값. "이 매치의 MOM이 누구였는지"처럼
 * 매치 단위로 argmax를 구해야 하는 계산(대시보드 선수 랭킹의 MOM 횟수 집계)에 쓴다.
 * rating이 null이거나 0인(출전 등록만 되고 실제로 안 뛴) 엔트리는 조회 단계에서 이미 제외된다.
 */
public record MatchPlayerRating(String matchId, String spId, double rating) {
}
