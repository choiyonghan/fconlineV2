package com.fconline.domain.match.vo;

public record OpponentTally(String opponentOuid, String opponentNickname,
                             int wins, int draws, int losses) {
}
