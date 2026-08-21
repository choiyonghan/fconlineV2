package com.fconline.domain.match;

/**
 * 승/무/패 + 득실 집계 결과. QueryDSL 프로젝션 결과를 담는 순수 값타입으로,
 * infrastructure(QueryDSL Tuple)에 대한 의존이 domain 밖으로 새지 않게 한다.
 */
public record MatchTally(int wins, int draws, int losses, long goalsFor, long goalsAgainst) {

    public static final MatchTally EMPTY = new MatchTally(0, 0, 0, 0, 0);

    public int totalMatches() {
        return wins + draws + losses;
    }

    public long goalDifference() {
        return goalsFor - goalsAgainst;
    }
}
