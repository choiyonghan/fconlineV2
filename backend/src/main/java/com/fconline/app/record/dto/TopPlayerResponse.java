package com.fconline.app.record.dto;

public record TopPlayerResponse(String spId, String playerName, int goals, int assists,
                                 int saves, int tackles, int intercepts, int blocks,
                                 double contributionScore) {
}
