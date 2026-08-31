package com.fconline.infrastructure.config;

import com.fconline.infrastructure.groq.GroqApiProperties;
import com.fconline.infrastructure.insight.GithubInsightSnapshotProperties;
import com.fconline.infrastructure.nexon.NexonApiProperties;
import com.fconline.infrastructure.personality.PersonalityReportProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 모든 외부 RestClient에 타임아웃을 명시한다 — 안 걸면 JDK HttpClient 기본값(사실상 무제한)이라
 * 상대 서버가 응답을 안 주면 요청 스레드가 영원히 물린다(운영에서 실제로 겪음: /insights/ask가
 * AI API 응답을 못 받고 120초 넘게 안 끊긴 채 남아있었음). 연결(connect) 자체는 이미 존재하는
 * 서비스들이라 5초면 충분하고, 응답 대기(read)는 서비스 성격에 따라 다르게 준다 — 일반 REST
 * 조회는 30초, AI 답변 생성은 시간이 걸릴 수 있어 45초.
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AI_READ_TIMEOUT = Duration.ofSeconds(45);

    @Bean
    public RestClient nexonRestClient(NexonApiProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(DEFAULT_READ_TIMEOUT))
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
                .requestFactory(requestFactory(DEFAULT_READ_TIMEOUT))
                .build();
    }

    /** OpenAI 호환 Chat Completions API(Groq) — GroqApiProperties.key로 Bearer 인증. */
    @Bean
    public RestClient groqRestClient(GroqApiProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Authorization", "Bearer " + properties.key())
                .requestFactory(requestFactory(AI_READ_TIMEOUT))
                .build();
    }

    /** insight-snapshot.yml이 커밋해둔 data/insight-snapshots/*.json을 읽는 전용 클라이언트. */
    @Bean
    public RestClient insightSnapshotRestClient(GithubInsightSnapshotProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.rawBaseUrl())
                .requestFactory(requestFactory(DEFAULT_READ_TIMEOUT))
                .build();
    }

    /**
     * Supabase Storage(private bucket)에서 성격 리포트(.md)를 읽는 전용 클라이언트 —
     * service role key로 인증해 RLS를 우회한다(백엔드 전용, 브라우저엔 절대 노출 안 됨).
     */
    @Bean
    public RestClient personalityReportRestClient(PersonalityReportProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.url() + "/storage/v1/object/" + properties.bucket())
                .defaultHeader("Authorization", "Bearer " + properties.serviceRoleKey())
                .defaultHeader("apikey", properties.serviceRoleKey())
                .requestFactory(requestFactory(DEFAULT_READ_TIMEOUT))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
