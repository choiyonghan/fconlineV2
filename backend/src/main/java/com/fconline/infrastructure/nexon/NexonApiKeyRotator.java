package com.fconline.infrastructure.nexon;

import com.fconline.domain.shared.exception.RateLimitException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 설정된 개수(현재 최대 6개)만큼의 Nexon API 키를 라운드로빈으로 순환한다.
 * v1은 update.yml에 키를 1개만 주입해 3키 로테이션 로직이 사실상 무력화되어 있었다
 * (analysis 5절) — v2는 배치 워크플로우에 키를 모두 주입하는 것을 전제로 한다.
 * 키 개수는 nexon.api.keys(application.yml)에 나열된 만큼 그대로 반영되므로,
 * 발급받은 키를 늘리면 application.yml/sync.yml에 플레이스홀더만 추가하면 된다.
 *
 * 스레드 세이프하지 않다 — 동기화 배치는 단일 스레드로 순차 실행되므로 문제되지 않는다.
 */
@Component
public class NexonApiKeyRotator {

    private final List<String> keys;
    private final boolean[] exhausted;
    private int cursor = 0;

    public NexonApiKeyRotator(NexonApiProperties properties) {
        this.keys = new ArrayList<>(properties.keys());
        this.exhausted = new boolean[keys.size()];
    }

    public String currentKey() {
        if (keys.isEmpty()) {
            throw new IllegalStateException("설정된 Nexon API 키가 없습니다 (nexon.api.keys).");
        }
        return keys.get(cursor);
    }

    /**
     * 현재 키가 429를 받았을 때 호출한다. 다음 사용 가능한 키로 커서를 옮기고,
     * 모든 키가 소진됐으면 RateLimitException을 던져 호출자(동기화 파사드)가
     * 해당 유저 처리를 명시적으로 중단하게 한다.
     * v1은 이 상황에서도 워크플로우가 exit 0으로 성공 처리되어 실패가 CI에 드러나지 않았다
     * (analysis 6.9) — v2는 예외를 상위로 전파해 배치가 non-zero로 종료되게 한다.
     */
    public void markCurrentKeyExhausted() {
        if (!keys.isEmpty()) {
            exhausted[cursor] = true;
        }

        for (int i = 0; i < keys.size(); i++) {
            int candidate = (cursor + 1 + i) % keys.size();
            if (!exhausted[candidate]) {
                cursor = candidate;
                return;
            }
        }
        throw new RateLimitException("Nexon API 키 " + keys.size() + "개가 모두 429로 소진되었습니다.");
    }
}
