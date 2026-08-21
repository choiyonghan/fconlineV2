<script lang="ts">
	import type { OverallRecord } from '$lib/api/types';

	let { record }: { record: OverallRecord } = $props();

	const winRate = $derived(
		record.tally.win + record.tally.draw + record.tally.lose === 0
			? 0
			: Math.round(
					(record.tally.win / (record.tally.win + record.tally.draw + record.tally.lose)) * 100
				)
	);
</script>

<section class="card">
	<h2>👑 {record.nickname} 종합 전적</h2>

	<div class="stat-grid">
		<div>
			<div class="stat-value">
				{record.tally.win}승 {record.tally.draw}무 {record.tally.lose}패
			</div>
			<div class="stat-label">승률 {winRate}%</div>
		</div>
		<div>
			<div class="stat-value">
				{record.tally.goalsFor}:{record.tally.goalsAgainst}
			</div>
			<div class="stat-label">득실</div>
		</div>
		<div>
			<div class="stat-value">{record.averageRating.toFixed(2)}</div>
			<div class="stat-label">평균 평점</div>
		</div>
		<div>
			<div class="stat-value">{record.possessionAverage.toFixed(1)}%</div>
			<div class="stat-label">평균 점유율</div>
		</div>
		<div>
			<div class="stat-value">{record.foulTotal}</div>
			<div class="stat-label">파울</div>
		</div>
		<div>
			<div class="stat-value">🟨{record.yellowCards} 🟥{record.redCards}</div>
			<div class="stat-label">카드</div>
		</div>
	</div>

	{#if record.topPlayers.length > 0}
		<h3>TOP 선수</h3>
		<ol class="top-players">
			{#each record.topPlayers as player (player.spId)}
				<li>
					<strong>{player.playerName}</strong>
					<span class="muted"
						>골 {player.goals} · 도움 {player.assists} · 기여도 {player.contributionScore.toFixed(
							1
						)}</span
					>
				</li>
			{/each}
		</ol>
	{/if}

	{#if record.goalTypeDistribution.length > 0}
		<h3>득점 유형</h3>
		<div class="button-row">
			{#each record.goalTypeDistribution as stat (stat.shootType)}
				<span class="pill-button">{stat.shootType} {stat.count}</span>
			{/each}
		</div>
	{/if}

	{#if record.goalTimeDistribution.length > 0}
		<h3>득점 시간대</h3>
		<div class="button-row">
			{#each record.goalTimeDistribution as bucket (bucket.periodLabel)}
				<span class="pill-button">{bucket.periodLabel}분 {bucket.count}</span>
			{/each}
		</div>
	{/if}
</section>

<style>
	.top-players {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding-left: 20px;
		margin: 8px 0 0;
	}

	h3 {
		font-size: 0.95rem;
		margin: 16px 0 8px;
		color: var(--color-text-muted);
	}
</style>
