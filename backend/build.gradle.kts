plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    // --- Spring Boot starters ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- DB / migration ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- QueryDSL (OpenFeign 포크, jakarta 네이티브 지원 — 원본 com.querydsl은 아카이브됨) ---
    implementation(libs.querydsl.jpa)
    annotationProcessor(libs.querydsl.apt)
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

    // --- OpenAPI ---
    implementation(libs.springdoc.openapi.webmvc.ui)

    // --- Lombok (엔티티 보일러플레이트 전용, DTO는 record 사용) ---
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.github.tomakehurst:wiremock-jre8:3.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Docker 없이도 빠르게 돌릴 수 있는 컨텍스트 로드/마이그레이션 스모크 테스트용
    // (PostgreSQL 호환 모드). 실제 CI/로컬 통합 테스트는 Testcontainers(위)를 우선 사용할 것.
    testRuntimeOnly("com.h2database:h2")
}

tasks.named<Jar>("jar") {
    enabled = false
}

// QueryDSL Q타입 생성 경로를 build/generated 아래로 격리 (버전관리 대상 아님)
val querydslDir = layout.buildDirectory.dir("generated/querydsl")
sourceSets {
    main {
        java.srcDir(querydslDir)
    }
}
// querydsl-apt(OpenFeign 포크) jar는 META-INF/services에 프로세서를 등록하지 않으므로
// JPA용 프로세서를 명시적으로 지정해야 Q타입이 생성된다. main 컴파일에만 적용 —
// 테스트 컴파일 클래스패스에는 querydsl-apt가 없으므로 거기까지 강제하면 프로세서를 못 찾아 실패한다.
tasks.named<JavaCompile>("compileJava") {
    options.generatedSourceOutputDirectory.set(querydslDir)
    options.compilerArgs.add("-processor")
    options.compilerArgs.add("com.querydsl.apt.jpa.JPAAnnotationProcessor,lombok.launch.AnnotationProcessorHider\$AnnotationProcessor")
}
