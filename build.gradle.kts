plugins {
    // 루트 프로젝트 자체엔 소스가 없지만, Kotlin DSL이 subprojects{} 블록 안에서
    // java{}/toolchain 타입세이프 접근자를 생성하려면 이 플러그인이 필요하다.
    id("java")
}

allprojects {
    group = "com.fconline"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
