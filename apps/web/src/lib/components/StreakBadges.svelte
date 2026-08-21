<script lang="ts">
	import type { StreakBadge } from '$lib/api/types';

	let { streak }: { streak: StreakBadge } = $props();
</script>

<div class="streak-row muted">
	{#if streak.curWin > 0}<span>🔥 {streak.curWin}연승</span>{/if}
	{#if streak.curLose > 0}<span>😭 {streak.curLose}연패</span>{/if}
	{#if streak.curUnbeaten > 1}<span>🛡️ {streak.curUnbeaten}무패</span>{/if}
	{#if streak.curWinless > 1}<span>💤 {streak.curWinless}무승</span>{/if}
	<span>최다 연승 {streak.maxWin} · 최다 연패 {streak.maxLose}</span>
</div>

<style>
	.streak-row {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
		margin-top: 4px;
	}
</style>
