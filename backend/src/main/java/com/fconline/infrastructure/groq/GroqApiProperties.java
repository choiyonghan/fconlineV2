package com.fconline.infrastructure.groq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Groq(무료 티어) 연동 설정. Gemini에서 갈아탄 이유는 이 프로젝트의 Gemini API 키가 "신규
 * 사용자"로 분류돼 gemini-3.6-flash 하나만 쓸 수 있고 그마저 무료 한도가 하루 20회로 너무
 * 타이트했기 때문 — Groq는 신용카드 없이 하루 14,400회(모델별로 다름)까지 무료라 훨씬 넉넉하다.
 */
@ConfigurationProperties(prefix = "groq.api")
public record GroqApiProperties(String baseUrl, String key, String model) {
}
