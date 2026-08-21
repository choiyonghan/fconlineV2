<script lang="ts">
	import type { MatchType, OpponentSummary } from '$lib/api/types';
	import StreakBadges from './StreakBadges.svelte';
	import OpponentDetail from './OpponentDetail.svelte';

	let {
		ouid,
		opponent,
		matchType,
		seasonId
	}: {
		ouid: string;
		opponent: OpponentSummary;
		matchType: MatchType;
		seasonId: number | null;
	} = $props();

	let expanded = $state(false);
</script>

<article class="card opponent-card">
	<button type="button" class="opponent-card-header" onclick={() => (expanded = !expanded)}>
		<div>
			<div class="op-name">{opponent.opponentNickname}</div>
			<div class="muted">
				{opponent.tally.win}승 {opponent.tally.draw}무 {opponent.tally.lose}패 · 욱식점수 {opponent.dugsikScore}
			</div>
		</div>
		<span class="chevron" class:open={expanded}>▾</span>
	</button>

	<StreakBadges streak={opponent.streak} />

	{#if expanded}
		<OpponentDetail {ouid} opponentOuid={opponent.opponentOuid} {matchType} {seasonId} />
	{/if}
</article>

<style>
	.opponent-card-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		width: 100%;
		background: none;
		border: none;
		color: inherit;
		font: inherit;
		cursor: pointer;
		padding: 0;
		text-align: left;
	}

	.op-name {
		font-weight: 600;
	}

	.chevron {
		transition: transform 0.15s ease;
		color: var(--color-text-muted);
	}

	.chevron.open {
		transform: rotate(180deg);
	}
</style>
