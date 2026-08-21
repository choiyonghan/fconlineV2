package com.fconline.infrastructure.nexon;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexon.api")
public record NexonApiProperties(String baseUrl, List<String> keys, long requestDelayMs) {

    public NexonApiProperties {
        keys = keys == null ? List.of() : keys.stream().filter(k -> k != null && !k.isBlank()).toList();
    }
}
