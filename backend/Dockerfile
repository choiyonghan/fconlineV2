# syntax=docker/dockerfile:1

# 빌드 스테이지 — Gradle로 backend:bootJar만 만든다.
# 컨텍스트는 레포 루트(../ 기준)라서 gradlew/settings.gradle.kts 등 루트 빌드 파일에
# 접근할 수 있다 — apps/web은 .dockerignore로 제외해 빌드 컨텍스트를 가볍게 유지한다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY backend ./backend

RUN ./gradlew :backend:bootJar --no-daemon --console=plain

# 실행 스테이지 — JDK가 아니라 JRE로 이미지 크기를 줄인다.
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/backend/build/libs/*.jar app.jar

# Render 무료 티어(RAM 512MB) 안에서 안전하게 뜨도록 힙/메타스페이스를 제한한다.
ENV JAVA_OPTS="-Xmx400m -XX:MaxMetaspaceSize=150m"

# Render가 PORT 환경변수로 리스닝 포트를 지정한다 — application.yml의
# server.port: ${PORT:${SERVER_PORT:8080}}가 이를 그대로 읽는다.
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
