package com.fconline;

import com.fconline.domain.shared.KstZone;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FconlineApplication {

    public static void main(String[] args) {
        // JVM 기본 타임존을 KST로 고정한다 — GitHub Actions 러너 등 기본 타임존이 UTC인
        // 환경에서 코드 어딘가 새로 추가된 LocalDate.now()/LocalDateTime.now()가 타임존을
        // 빠뜨려도 KstZone(도메인 계층의 명시적 기준)과 어긋나지 않도록 하는 2차 안전장치다.
        TimeZone.setDefault(TimeZone.getTimeZone(KstZone.ID));
        SpringApplication.run(FconlineApplication.class, args);
    }
}
