package com.fconline.domain.match.vo;

/**
 * 선수 카드(spId)의 가장 최근 매치에서 관측된 강화 단계(0~11강, shoot_events.sp_grade).
 * 슛을 한 번도 안 쏜 선수(예: 무실점만 지킨 골키퍼, 슛 없이 수비만 한 필드 플레이어)는
 * shoot_events에 행이 없어 이 목록에서 빠진다 — 화면은 매칭 실패 시 조용히 등급만 생략한다.
 */
public record PlayerGrade(String spId, Integer grade) {
}
