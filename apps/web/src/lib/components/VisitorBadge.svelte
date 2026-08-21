<script lang="ts">
	import { onMount } from 'svelte';
	import { getVisitorSummary, recordVisit } from '$lib/api/endpoints';
	import type { VisitorSummary } from '$lib/api/types';

	let summary = $state<VisitorSummary | null>(null);

	onMount(async () => {
		try {
			summary = await recordVisit();
		} catch {
			// 방문자 카운트 실패는 화면 표시에 영향을 주지 않는다.
			try {
				summary = await getVisitorSummary();
			} catch {
				summary = null;
			}
		}
	});
</script>

{#if summary}
	<div class="muted">오늘 {summary.today} · 누적 {summary.total}</div>
{/if}
