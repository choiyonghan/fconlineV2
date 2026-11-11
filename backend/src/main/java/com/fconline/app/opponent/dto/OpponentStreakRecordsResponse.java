package com.fconline.app.opponent.dto;

import com.fconline.domain.streak.StreakRecords;
import java.time.Instant;

/** 상대별 역대(시즌 무관 전 기간) 최고 기록 — 상대별 전적 펼침 패널의 "역대 최고 기록" 카드. */
public record OpponentStreakRecordsResponse(int totalMatches, Instant firstMatchDate, Instant lastMatchDate,
                                             Run win, Run unbeaten, Run winless, Run lose) {

    public record Run(int count, Instant from, Instant to, boolean ongoing) {
        static Run from(StreakRecords.Run run) {
            return new Run(run.count(), run.from(), run.to(), run.ongoing());
        }
    }

    public static OpponentStreakRecordsResponse from(StreakRecords records) {
        return new OpponentStreakRecordsResponse(records.totalMatches(), records.firstMatchDate(),
                records.lastMatchDate(), Run.from(records.win()), Run.from(records.unbeaten()),
                Run.from(records.winless()), Run.from(records.lose()));
    }
}
