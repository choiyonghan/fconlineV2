import { apiGet, apiPost } from './client';
import type {
	MatchType,
	OpponentMatch,
	OpponentSummary,
	OverallRecord,
	Page,
	Season,
	TrackedUser,
	VisitorSummary
} from './types';

export function getTrackedUsers(): Promise<TrackedUser[]> {
	return apiGet('/api/v1/users');
}

export function getSeasons(): Promise<Season[]> {
	return apiGet('/api/v1/seasons');
}

export function getOverallRecord(
	ouid: string,
	matchType: MatchType,
	seasonId?: number
): Promise<OverallRecord> {
	return apiGet('/api/v1/records/overall', { ouid, matchType, seasonId });
}

export function getOpponents(
	ouid: string,
	matchType: MatchType,
	seasonId?: number
): Promise<OpponentSummary[]> {
	return apiGet('/api/v1/opponents', { ouid, matchType, seasonId });
}

export function getOpponentMatches(
	ouid: string,
	opponentOuid: string,
	matchType: MatchType,
	seasonId: number | undefined,
	page = 0,
	size = 20
): Promise<Page<OpponentMatch>> {
	return apiGet(`/api/v1/opponents/${encodeURIComponent(opponentOuid)}/matches`, {
		ouid,
		matchType,
		seasonId,
		page,
		size
	});
}

export function getVisitorSummary(): Promise<VisitorSummary> {
	return apiGet('/api/v1/visitors/summary');
}

export function recordVisit(): Promise<VisitorSummary> {
	return apiPost('/api/v1/visitors/visits');
}
