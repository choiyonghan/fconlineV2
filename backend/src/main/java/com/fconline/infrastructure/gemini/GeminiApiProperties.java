package com.fconline.infrastructure.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini(Google AI Studio 무료 티어) 연동 설정. 2026-08-31에 Groq → Gemini → Groq → Gemini 순으로
 * 두 번 오갔다(GeminiApiClient 클래스 주석의 "마이그레이션 이력" 참고) — 최종적으로 Gemini가
 * 승자인 이유: gemini-3.1-flash-lite(또는 gemini-3.5-flash-lite)는 무료 티어에서 RPD 500 ·
 * TPM 250,000으로, 우리 인사이트 프롬프트(성격 리포트 포함 시 약 1만 토큰)를 여유롭게 감당하면서
 * 하루 호출 횟수도 넉넉하다. 반면 애초에 이 마이그레이션의 발단이었던 "Gemini는 신규 사용자한테
 * RPD 20으로 막혀있다"는 진단은 gemini-3.6-flash(및 -pro, 일반 -flash 계열)에만 해당하는
 * 얘기였다 — *-flash-lite 계열은 신규 사용자에게도 RPD 500이 그대로 열려 있다(AI Studio
 * 대시보드 https://aistudio.google.com/rate-limit 로 직접 확인, 모델별로 등급이 다름).
 */
@ConfigurationProperties(prefix = "gemini.api")
public record GeminiApiProperties(String baseUrl, String key, String model) {
}
