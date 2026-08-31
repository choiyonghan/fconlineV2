package com.fconline.domain.match.vo;

/**
 * "전체 선수 스탯"의 선수별 xG/xA 합산용 — 특정 선수에게 귀속된 슛 좌표 1건. xG는 위치+슛
 * 유형만 보므로 득점 여부는 담지 않는다(ShotPoint와 달리 result/matchId는 필요 없어 뺐다).
 * shootType은 ExpectedGoalsCalculator.calcXg의 슛 유형별 난이도 배율에 필요해서 담는다
 * (2026-08-31 추가 — 이게 빠져있어서 헤더/바이시클킥 골의 xG가 과대평가되던 버그가 있었다).
 *
 * spId는 문맥에 따라 두 가지 의미로 쓰인다 — xG 집계(findShotPointsByPlayer)에서는 슈터
 * 본인, xA 집계(findAssistedShotPointsByPlayer)에서는 그 슛의 어시스트 제공자(assistSpId).
 * xA는 "그 슛의 xG를 어시스트 제공자에게 귀속"시키는 방식이라 x/y/shootType은 항상 슈터
 * 기준 그대로다.
 */
public record PlayerShotPoint(String spId, Double x, Double y, ShootType shootType) {
}
