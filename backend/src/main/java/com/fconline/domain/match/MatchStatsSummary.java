package com.fconline.domain.match;

public record MatchStatsSummary(double averageRating, double averagePossession,
                                 long foulTotal, long yellowCards, long redCards) {

    public static final MatchStatsSummary EMPTY = new MatchStatsSummary(0, 0, 0, 0, 0);
}
