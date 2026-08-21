<script lang="ts">
	import type { Season } from '$lib/api/types';

	let {
		seasons,
		selectedSeasonId,
		onSelect
	}: {
		seasons: Season[];
		selectedSeasonId: number | null;
		onSelect: (seasonId: number) => void;
	} = $props();
</script>

<div class="button-row" role="group" aria-label="시즌 선택">
	{#each seasons as season (season.id)}
		<button
			type="button"
			class="pill-button"
			class:active={season.id === selectedSeasonId}
			onclick={() => onSelect(season.id)}
		>
			{season.name}
		</button>
	{/each}
</div>
