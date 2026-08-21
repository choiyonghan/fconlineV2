<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { page } from '$app/stores';
	import { selection } from '$lib/stores/selection';
	import { getTrackedUsers, getSeasons, getOverallRecord, getOpponents } from '$lib/api/endpoints';
	import type { MatchType, OpponentSummary, OverallRecord, Season, TrackedUser } from '$lib/api/types';
	import UserPicker from '$lib/components/UserPicker.svelte';
	import SeasonPicker from '$lib/components/SeasonPicker.svelte';
	import MatchTypeToggle from '$lib/components/MatchTypeToggle.svelte';
	import OverallRecordCard from '$lib/components/OverallRecordCard.svelte';
	import OpponentList from '$lib/components/OpponentList.svelte';

	let users = $state<TrackedUser[]>([]);
	let seasons = $state<Season[]>([]);
	let record = $state<OverallRecord | null>(null);
	let opponents = $state<OpponentSummary[]>([]);
	let loadingRecord = $state(false);
	let loadError = $state<string | null>(null);

	onMount(async () => {
		try {
			[users, seasons] = await Promise.all([getTrackedUsers(), getSeasons()]);
		} catch {
			loadError = '유저/시즌 목록을 불러오지 못했습니다. 백엔드가 실행 중인지 확인해 주세요.';
			return;
		}

		// URL 쿼리 → 초기 선택 상태 복원 (없으면 첫 유저 + 현재 시즌 + CUSTOM으로 기본값)
		const params = $page.url.searchParams;
		const initialMatchType = (params.get('matchType') as MatchType) ?? 'CUSTOM';
		const initialSeasonId = params.get('seasonId')
			? Number(params.get('seasonId'))
			: (seasons.find((s) => s.current)?.id ?? seasons[0]?.id ?? null);
		const initialOuid = params.get('ouid') ?? users[0]?.ouid ?? null;

		selection.set({
			ouid: initialOuid,
			matchType: initialMatchType,
			seasonId: initialSeasonId
		});
	});

	// 선택 상태가 바뀔 때마다 데이터를 다시 불러오고 URL을 동기화한다.
	// v1은 시즌/유저 전환 시 페이지 스크립트가 수동으로 재조회 함수를 호출해야 했다.
	$effect(() => {
		const current = $selection;
		if (!current.ouid || !current.seasonId) {
			return;
		}

		const params = new URLSearchParams();
		params.set('ouid', current.ouid);
		params.set('matchType', current.matchType);
		params.set('seasonId', String(current.seasonId));
		goto(`?${params.toString()}`, { replaceState: true, keepFocus: true, noScroll: true });

		loadingRecord = true;
		loadError = null;
		Promise.all([
			getOverallRecord(current.ouid, current.matchType, current.seasonId),
			getOpponents(current.ouid, current.matchType, current.seasonId)
		])
			.then(([overall, opponentSummaries]) => {
				record = overall;
				opponents = opponentSummaries;
			})
			.catch(() => {
				loadError = '전적을 불러오지 못했습니다.';
			})
			.finally(() => {
				loadingRecord = false;
			});
	});

	function selectUser(ouid: string) {
		selection.update((s) => ({ ...s, ouid }));
	}

	function selectMatchType(matchType: MatchType) {
		selection.update((s) => ({ ...s, matchType }));
	}

	function selectSeason(seasonId: number) {
		selection.update((s) => ({ ...s, seasonId }));
	}
</script>

<svelte:head>
	<title>FC Online 전적 트래커</title>
</svelte:head>

<section class="card">
	<UserPicker {users} selectedOuid={$selection.ouid} onSelect={selectUser} />
	<div style="height: 8px"></div>
	<MatchTypeToggle matchType={$selection.matchType} onSelect={selectMatchType} />
	<div style="height: 8px"></div>
	<SeasonPicker {seasons} selectedSeasonId={$selection.seasonId} onSelect={selectSeason} />
</section>

{#if loadError}
	<p class="muted">{loadError}</p>
{/if}

{#if loadingRecord && !record}
	<p class="muted">불러오는 중...</p>
{/if}

{#if record}
	<OverallRecordCard {record} />
{/if}

{#if $selection.matchType === 'CUSTOM' && $selection.ouid}
	<section>
		<h2>상대별 전적</h2>
		<OpponentList
			ouid={$selection.ouid}
			{opponents}
			matchType={$selection.matchType}
			seasonId={$selection.seasonId}
		/>
	</section>
{/if}
