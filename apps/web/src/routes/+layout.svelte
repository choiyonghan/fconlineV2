<script lang="ts">
	import '../app.css';
	import VisitorBadge from '$lib/components/VisitorBadge.svelte';

	let { children } = $props();
</script>

<div class="app-shell">
	<header class="app-header">
		<h1>FC Online 전적 트래커</h1>
		<a href="../" class="report-link">매치 리포트 보기 →</a>
		<VisitorBadge />
	</header>

	<main>
		{@render children()}
	</main>
</div>
