import { writable } from 'svelte/store';
import type { MatchType } from '$lib/api/types';

// v1은 selectedUser/selectedSeason 같은 전역 let 변수를 여러 스크립트 파일이 직접
// 읽고 쓰며 갱신 누락/불일치가 발생하기 쉬운 구조였다 — v2는 이 store 하나로 일원화하고,
// 모든 컴포넌트는 이 store를 구독/파생만 한다.
export interface Selection {
	ouid: string | null;
	matchType: MatchType;
	seasonId: number | null;
}

export const selection = writable<Selection>({
	ouid: null,
	matchType: 'CUSTOM',
	seasonId: null
});
