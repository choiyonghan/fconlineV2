package com.fconline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 스프링 컨텍스트가 정상적으로 뜨는지(빈 배선, JPA 엔티티 매핑 구조)만 확인하는 스모크 테스트.
 * Docker가 없는 환경에서도 돌아가도록 H2를 사용한다 — Flyway/Postgres 전용 문법 검증은 제외.
 */
@SpringBootTest
@ActiveProfiles("test")
class FconlineApplicationTests {

    @Test
    void contextLoads() {
    }
}
