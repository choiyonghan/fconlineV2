<script lang="ts">
	import type { MatchType } from '$lib/api/types';

	let {
		matchType,
		onSelect
	}: {
		matchType: MatchType;
		onSelect: (matchType: MatchType) => void;
	} = $props();
</script>

<div class="button-row" role="group" aria-label="매치 타입 선택">
	<button
		type="button"
		class="pill-button"
		class:active={matchType === 'CUSTOM'}
		onclick={() => onSelect('CUSTOM')}
	>
		커스텀 매치
	</button>
	<button
		type="button"
		class="pill-button"
		class:active={matchType === 'OFFICIAL'}
		onclick={() => onSelect('OFFICIAL')}
	>
		공식 경기
	</button>
</div>
