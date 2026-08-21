<script lang="ts">
	import { getOpponentMatches } from '$lib/api/endpoints';
	import type { MatchType, OpponentMatch, Page } from '$lib/api/types';

	let {
		ouid,
		opponentOuid,
		matchType,
		seasonId
	}: {
		ouid: string;
		opponentOuid: string;
		matchType: MatchType;
		seasonId: number | null;
	} = $props();

	let page = $state<Page<OpponentMatch> | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	$effect(() => {
		loading = true;
		error = null;
		getOpponentMatches(ouid, opponentOuid, matchType, seasonId ?? undefined)
			.then((result) => {
				page = result;
			})
			.catch(() => {
				error = '경기 목록을 불러오지 못했습니다.';
			})
			.finally(() => {
				loading = false;
			});
	});

	function resultClass(result: OpponentMatch['result']) {
		if (result === '승') return 'result-win';
		if (result === '패') return 'result-lose';
		return 'result-draw';
	}
</script>

<div class="opponent-detail">
	{#if loading}
		<p class="muted">불러오는 중...</p>
	{:else if error}
		<p class="muted">{error}</p>
	{:else if page && page.content.length === 0}
		<p class="muted">이 시즌에 치른 경기가 없습니다.</p>
	{:else if page}
		<ul>
			{#each page.content as match (match.matchId)}
				<li>
					<span class="result-badge {resultClass(match.result)}">{match.result}</span>
					<span>{new Date(match.matchDate).toLocaleDateString('ko-KR')}</span>
					<span>{match.goalsFor}:{match.goalsAgainst}</span>
					{#if match.averageRating}<span class="muted">평점 {match.averageRating.toFixed(1)}</span
						>{/if}
				</li>
			{/each}
		</ul>
	{/if}
</div>

<style>
	.opponent-detail ul {
		list-style: none;
		margin: 8px 0 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	.opponent-detail li {
		display: flex;
		align-items: center;
		gap: 10px;
		font-size: 0.9rem;
	}
</style>
