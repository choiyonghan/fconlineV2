<script lang="ts">
	import type { MatchType, OpponentSummary } from '$lib/api/types';
	import OpponentCard from './OpponentCard.svelte';

	let {
		ouid,
		opponents,
		matchType,
		seasonId
	}: {
		ouid: string;
		opponents: OpponentSummary[];
		matchType: MatchType;
		seasonId: number | null;
	} = $props();
</script>

{#if opponents.length === 0}
	<p class="muted">이 시즌에 상대 전적이 없습니다.</p>
{:else}
	<div class="opponent-list">
		{#each opponents as opponent (opponent.opponentOuid)}
			<OpponentCard {ouid} {opponent} {matchType} {seasonId} />
		{/each}
	</div>
{/if}

<style>
	.opponent-list {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
</style>
