package com.fconline.domain.match.vo;

/**
 * cleanSheets(무실점 경기)/multiConcededGames(3실점 이상 경기)/highPossessionGames(점유율
 * 55% 이상)/lowPossessionGames(점유율 45% 이하)는 "플레이 성향" 카드(수비 성향)용 집계다.
 * 표본 크기는 이 record에 담지 않는다 — 총 경기 수는 이미 MatchTally(승+무+패)로 구할 수 있어서다.
 */
public record MatchStatsSummary(double averageRating, double averagePossession,
                                 long foulTotal, long yellowCards, long redCards,
                                 long cleanSheets, long multiConcededGames,
                                 long highPossessionGames, long lowPossessionGames,
                                 /** "더티 플레이" 성향(플레이 성향 카드)용 — 표본 전체 합산. */
                                 long systemPauseTotal,
                                 /** "패스 성향" 카드용 — 표본 전체 합산(전체/숏/롱 패스). */
                                 long passTryTotal, long passSuccessTotal,
                                 long shortPassTryTotal, long shortPassSuccessTotal,
                                 long longPassTryTotal, long longPassSuccessTotal,
                                 /** "수비 성향" 카드용 — 표본 전체 합산(태클/블락). */
                                 long tackleTryTotal, long tackleSuccessTotal,
                                 long blockTryTotal, long blockSuccessTotal) {

    public static final MatchStatsSummary EMPTY =
            new MatchStatsSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
