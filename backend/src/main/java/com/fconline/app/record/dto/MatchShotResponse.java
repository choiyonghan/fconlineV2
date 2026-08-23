package com.fconline.app.record.dto;

/**
 * 매치 상세 모달용 슛 이벤트 1건. isGoal=true면 result가 "GOAL"이라는 뜻(프론트가 굳이 문자열
 * 비교를 안 해도 되게 미리 계산해서 내려준다).
 */
public record MatchShotResponse(
        String spId,
        String playerName,
        Double x,
        Double y,
        String shootType,
        String result,
        boolean isGoal,
        Integer goalTimeMinutes,
        Integer period,
        boolean assist,
        String assistSpId,
        String assistPlayerName
) {
}
