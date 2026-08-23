package com.fconline.app.insight.facade;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 게임 닉네임 ↔ 실명 별칭 조회. AI 질문(insights/ask)이 "서울쥐" 대신 "김상기"처럼 실명으로
 * 들어와도 같은 사람을 가리키는 걸 알아보게 하려는 용도 — DB에는 실명을 두지 않고
 * (tracked-user.real-names, 즉 TRACKED_USER_REAL_NAMES 환경변수) 설정으로만 주입한다.
 */
@Component
public class TrackedUserAliasResolver {

    private final Map<String, String> realNameByNickname;

    public TrackedUserAliasResolver(TrackedUserAliasProperties properties) {
        this.realNameByNickname = parse(properties.realNames());
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

    /** 닉네임에 매핑된 실명. 설정이 없으면 null. */
    public String realNameOf(String nickname) {
        return realNameByNickname.get(nickname);
    }

    /** 질문 문장에 이 닉네임 자체 또는 그 실명이 등장하는지 확인한다. */
    public boolean mentions(String question, String nickname) {
        if (question.contains(nickname)) {
            return true;
        }
        String realName = realNameByNickname.get(nickname);
        return realName != null && question.contains(realName);
    }
}
