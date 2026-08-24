package com.fconline.app.record.dto;

/**
 * 매치 상세 모달용 슛 이벤트 1건. isGoal=true면 result가 "GOAL"이라는 뜻(프론트가 굳이 문자열
 * 비교를 안 해도 되게 미리 계산해서 내려준다). assistX/assistY는 어시스트가 있을 때만 값이 있고,
 * "슛 클릭 시 어시스트 지점과 선으로 연결"하는 UI용이다. hitPost=골대를 맞았는지,
 * inPenalty=페널티박스 안에서 쐈는지(중거리슛 구분용).
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
        String assistPlayerName,
        Double assistX,
        Double assistY,
        Boolean hitPost,
        Boolean inPenalty
) {
}
