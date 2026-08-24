package com.fconline.app.record.dto;

import java.util.List;

/**
 * 매치 상세 모달용 슛 이벤트 묶음. myShots는 이 유저가 쏜 슛(득점 상세 소스), concededShots는
 * 상대가 이 유저를 향해 쏜 슛(실점 상세 소스) — 상대도 추적 대상이어야 채워지고, 아니면 빈 목록.
 */
public record MatchShotsResponse(List<MatchShotResponse> myShots, List<MatchShotResponse> concededShots) {
}
