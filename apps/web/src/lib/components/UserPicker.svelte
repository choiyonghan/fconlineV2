<script lang="ts">
	import type { TrackedUser } from '$lib/api/types';

	let {
		users,
		selectedOuid,
		onSelect
	}: {
		users: TrackedUser[];
		selectedOuid: string | null;
		onSelect: (ouid: string) => void;
	} = $props();
</script>

<div class="button-row" role="group" aria-label="유저 선택">
	{#each users as user (user.ouid)}
		<button
			type="button"
			class="pill-button"
			class:active={user.ouid === selectedOuid}
			onclick={() => onSelect(user.ouid)}
		>
			{user.nickname}
		</button>
	{/each}
</div>
