package com.fconline.domain.match.vo;

/**
 * "전체 선수 스탯"의 선수별 xG 합산용 — spId가 붙은 슛 좌표 1건. xG는 위치만 보므로 득점 여부는
 * 담지 않는다(ShotPoint와 달리 shootType/result/matchId도 필요 없어 뺐다).
 */
public record PlayerShotPoint(String spId, Double x, Double y) {
}
