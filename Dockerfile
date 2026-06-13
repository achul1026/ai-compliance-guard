# ── 빌드 스테이지: Gradle 멀티모듈 → :api bootJar ──
FROM gradle:8.7-jdk17 AS build
WORKDIR /workspace

# 의존성 캐시 레이어: 빌드 스크립트 먼저 복사
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common/build.gradle.kts ./common/
COPY infra/build.gradle.kts ./infra/
COPY rag/build.gradle.kts ./rag/
COPY agent/build.gradle.kts ./agent/
COPY api/build.gradle.kts ./api/
RUN gradle :api:dependencies --no-daemon > /dev/null 2>&1 || true

# 소스 복사 후 실행 가능 jar 빌드
COPY . .
RUN gradle :api:bootJar --no-daemon -x test

# ── 실행 스테이지: 경량 JRE ──
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY --from=build /workspace/api/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# 헬스체크: prod context-path /api/v1
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/v1/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
