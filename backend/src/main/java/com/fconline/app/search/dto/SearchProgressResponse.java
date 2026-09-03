package com.fconline.app.search.dto;

/**
 * 검색 화면 로딩바용 — total=0이면 아직 매치 목록도 못 받아온 초기 상태(또는 5분 넘게 방치돼
 * 정리된 상태)라는 뜻이다. SearchProgressTracker 클래스 주석 참고.
 */
public record SearchProgressResponse(int fetched, int total) {
}
