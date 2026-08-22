package com.fconline.infrastructure.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gemini.api")
public record GeminiApiProperties(String baseUrl, String key, String model) {
}
