package com.fconline.application.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sync")
public record SyncProperties(String matchType, int matchFetchLimit) {
}
