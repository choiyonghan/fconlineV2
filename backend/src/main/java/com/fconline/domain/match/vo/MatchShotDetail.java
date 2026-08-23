package com.fconline.domain.match.vo;

/**
 * 매치 상세 모달용 슛 이벤트 1건 — 누가(spId) 어디서(x,y) 어떤 유형/결과로 쐈는지, 어시스트가
 * 있었다면 누가(assistSpId) 해줬는지까지 포함한다. shoot_events 원시 컬럼을 그대로 옮긴 값이라
 * 선수 이름 매핑(spId/assistSpId -> 이름)은 응용 계층(RecordFacade)에서 한다.
 */
public record MatchShotDetail(
        String spId,
        Double x,
        Double y,
        ShootType shootType,
        ShootResult result,
        Integer goalTimeMinutes,
        Integer period,
        Boolean assist,
        String assistSpId
) {
}
