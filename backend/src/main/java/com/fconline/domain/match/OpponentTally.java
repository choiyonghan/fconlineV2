package com.fconline.domain.match;

public record OpponentTally(String opponentOuid, String opponentNickname,
                             int wins, int draws, int losses) {
}
