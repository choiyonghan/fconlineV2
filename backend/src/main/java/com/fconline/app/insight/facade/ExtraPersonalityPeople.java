package com.fconline.app.insight.facade;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * FC Online 추적 대상이 아니라 ouid가 없는 친구(예: 이용민, 처리)의 실명 → Supabase Storage
 * 키 매핑. InsightFacade.ask()가 질문 문장에 이 이름이 언급되면(FC 전적과 무관한 질문이어도)
 * 성격 리포트를 붙일 수 있게 한다.
 */
@Component
public class ExtraPersonalityPeople {

    private final Map<String, String> storageKeyByName;

    public ExtraPersonalityPeople(ExtraPersonalityPeopleProperties properties) {
        this.storageKeyByName = parse(properties.extraPeople());
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String pair : raw.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return map;
    }

    /** 실명 → Supabase Storage 키. 전체 목록을 그대로 순회할 때 쓴다. */
    public Map<String, String> all() {
        return storageKeyByName;
    }
}
