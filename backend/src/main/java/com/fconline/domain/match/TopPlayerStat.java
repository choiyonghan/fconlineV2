package com.fconline.domain.match;

public record TopPlayerStat(String spId, int goals, int assists, int saves,
                             int tackles, int intercepts, int blocks, double contributionScore) {
}
