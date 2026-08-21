// 백엔드 application/**/dto의 record 타입과 1:1 대응한다.
// TODO: 규모가 커지면 springdoc-openapi 스펙에서 openapi-typescript로 자동 생성하는 것을 권장.

export type MatchType = 'CUSTOM' | 'OFFICIAL';

export interface TrackedUser {
	ouid: string;
	nickname: string;
	displayOrder: number;
}

export interface Season {
	id: number;
	name: string;
	startDate: string;
	endDate: string | null;
	current: boolean;
}

export interface MatchTally {
	win: number;
	draw: number;
	lose: number;
	goalsFor: number;
	goalsAgainst: number;
}

export interface TopPlayer {
	spId: string;
	playerName: string;
	goals: number;
	assists: number;
	saves: number;
	tackles: number;
	intercepts: number;
	blocks: number;
	contributionScore: number;
}

export interface GoalTypeStat {
	shootType: string;
	count: number;
}

export interface GoalTimeBucket {
	periodLabel: string;
	count: number;
}

export interface OverallRecord {
	ouid: string;
	nickname: string;
	tally: MatchTally;
	averageRating: number;
	possessionAverage: number;
	foulTotal: number;
	yellowCards: number;
	redCards: number;
	topPlayers: TopPlayer[];
	goalTypeDistribution: GoalTypeStat[];
	goalTimeDistribution: GoalTimeBucket[];
}

export interface StreakBadge {
	curWin: number;
	curLose: number;
	curWinless: number;
	curUnbeaten: number;
	maxWin: number;
	maxLose: number;
	maxWinless: number;
	maxUnbeaten: number;
}

export interface OpponentSummary {
	opponentOuid: string;
	opponentNickname: string;
	tally: MatchTally;
	streak: StreakBadge;
	dugsikScore: number;
}

export interface OpponentMatch {
	matchId: string;
	matchDate: string;
	result: '승' | '무' | '패';
	goalsFor: number;
	goalsAgainst: number;
	averageRating: number | null;
	possession: number | null;
	shootTotal: number | null;
	effectiveShoot: number | null;
	passTry: number | null;
	passSuccess: number | null;
	tackleTry: number | null;
	tackleSuccess: number | null;
	foul: number | null;
	yellowCards: number | null;
	redCards: number | null;
}

export interface Page<T> {
	content: T[];
	totalElements: number;
	totalPages: number;
	number: number;
	size: number;
}

export interface VisitorSummary {
	today: number;
	total: number;
}

export interface ApiError {
	code: string;
	message: string;
	timestamp: string;
}
