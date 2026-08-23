package com.fconline.infrastructure.insight;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "insight.snapshot")
public record GithubInsightSnapshotProperties(String rawBaseUrl) {
}
