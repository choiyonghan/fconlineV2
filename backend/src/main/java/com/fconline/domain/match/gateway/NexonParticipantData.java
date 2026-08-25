package com.fconline.domain.match.gateway;

import com.fconline.domain.match.vo.MatchResult;
import com.fconline.domain.match.vo.ShootResult;
import com.fconline.domain.match.vo.ShootType;
import java.util.List;

/**
 * Nexon match-detail 응답 중 참가자 1명 시점으로 이미 가공된 데이터.
 * 원본 JSON → 이 레코드로의 매핑(필드명, ShootType/ShootResult 분류)은
 * infrastructure.nexon의 게이트웨이 구현체 책임이다 — v1의 detailPayload 매핑이 여기 해당한다.
 * 필드명/코드 매핑은 Nexon 공식 match-detail API 문서로 확정했다(NexonApiClient 클래스 주석 참고).
 */
public record NexonParticipantData(
        String ouid,
        String opponentOuid,
        String opponentNickname,
        MatchResult result,
        String controller,
        Double averageRating,
        Integer goalsFor,
        Integer goalsAgainst,
        Integer shootTotal,
        Integer effectiveShoot,
        Integer goalInPenalty,
        Integer goalOutPenalty,
        Integer shootHeading,
        Integer ownGoal,
        Integer possession,
        Integer passTry,
        Integer passSuccess,
        Integer shortPassTry,
        Integer shortPassSuccess,
        Integer longPassTry,
        Integer longPassSuccess,
        Integer bouncingLobPassTry,
        Integer bouncingLobPassSuccess,
        Integer drivenGroundPassTry,
        Integer drivenGroundPassSuccess,
        Integer throughPassTry,
        Integer throughPassSuccess,
        Integer lobbedThroughPassTry,
        Integer lobbedThroughPassSuccess,
        Integer tackleTry,
        Integer tackleSuccess,
        Integer blockTry,
        Integer blockSuccess,
        Integer foul,
        Integer yellowCards,
        Integer redCards,
        Integer offside,
        /** matchDetail.matchEndType 그대로("0=정상 종료"만 쓰기 필터 기준, MatchStats 클래스 주석 참고). */
        Integer matchEndType,
        Integer systemPause,
        List<ShootEventData> shootEvents,
        List<SquadEntryData> squadEntries,
        /** shootDetail[]/player[] 원본 그대로 (jsonb 백업 컬럼용). 없으면 null. */
        String shootDetailRaw,
        String playerSquadRaw,
        /** 참가자 원소(self) 전체 원본 — matchDetail/shoot/pass/defence 등 미매핑 필드까지 포함. */
        String rawParticipant
) {
    public record ShootEventData(ShootType shootType, ShootResult result, Integer goalTimeMinutes, Integer period,
                                  String spId, Integer spGrade, Integer spLevel, Boolean loaned,
                                  Double x, Double y, Boolean assist, String assistSpId,
                                  Double assistX, Double assistY, Boolean hitPost, Boolean inPenalty) {
    }

    public record SquadEntryData(String spId, int spPosition, int goal, int assist,
                                  int save, int tackle, int intercept, int block,
                                  int shootTotal, int effectiveShoot, int passTry, int passSuccess,
                                  int dribbleTry, int dribbleSuccess, int dribbleDistance,
                                  int aerialTry, int aerialSuccess, Double rating) {
    }
}
