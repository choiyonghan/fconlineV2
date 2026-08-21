package com.fconline.app.sync.dto;

import com.fconline.domain.match.vo.MatchType;

public record SyncSummary(MatchType matchType, int fetchedMatchIds, int insertedMatches, int failedUsers) {
}
