package com.fconline.app.sync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sync")
public record SyncProperties(String matchType, int matchFetchLimit) {
}
