package com.fconline.infrastructure.config;

import com.fconline.infrastructure.gemini.GeminiApiProperties;
import com.fconline.infrastructure.insight.GithubInsightSnapshotProperties;
import com.fconline.infrastructure.nexon.NexonApiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient nexonRestClient(NexonApiProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    /**
     * spid.json(정적 메타)은 /fconline/v1이 아니라 도메인 루트 아래 /static 경로에 있다
     * (analysis 3.1: https://open.api.nexon.com/static/fconline/meta/spid.json).
     */
    @Bean
    public RestClient nexonStaticRestClient(NexonApiProperties properties) {
        String root = properties.baseUrl().replaceFirst("/fconline/v1/?$", "");
        return RestClient.builder()
                .baseUrl(root)
                .build();
    }

    @Bean
    public RestClient geminiRestClient(GeminiApiProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** insight-snapshot.yml이 커밋해둔 data/insight-snapshots/*.json을 읽는 전용 클라이언트. */
    @Bean
    public RestClient insightSnapshotRestClient(GithubInsightSnapshotProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.rawBaseUrl())
                .build();
    }
}
