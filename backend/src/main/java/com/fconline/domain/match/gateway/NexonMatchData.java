package com.fconline.domain.match.gateway;

import com.fconline.domain.match.vo.MatchType;
import java.time.Instant;
import java.util.List;

/** 경기 하나에 대한 Nexon 원본 데이터의 도메인 친화적 표현. */
public record NexonMatchData(String matchId, Instant matchDate, MatchType matchType,
                              List<NexonParticipantData> participants) {
}
