package com.fconline.app.insight.facade;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FC Online 추적 대상이 아닌(ouid 없는) 친구도 성격 리포트가 있으면 AI 질문에 참고시키기 위한
 * 목록 — "실명:Supabase Storage 키" 콤마 구분(TRACKED_USER_REAL_NAMES와 같은 패턴, 예:
 * "이용민:leeyongmin,처리:cheori"). ouid가 없어서 PersonalityReportClient가 쓰는 ouid.md 키
 * 관례를 못 따르므로, 로마자 슬러그를 직접 키로 쓴다(Supabase Storage가 한글 키를 거절함 —
 * PersonalityReportClient 주석 참고).
 */
@ConfigurationProperties(prefix = "personality")
public record ExtraPersonalityPeopleProperties(String extraPeople) {
}
