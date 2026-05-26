---
name: Spring Boot Bootstrapper
description: Spring Boot 프로젝트의 기본 구조를 설정한다. 멀티모듈 프로젝트의 각 모듈에 @SpringBootApplication 또는 라이브러리 구조를 정의하고, 헬스체크 엔드포인트, 설정 파일(application.yml), 의존성 관리를 구현. "Spring Boot 셋업해줘", "Gradle 멀티모듈 구성", "헬스체크 구현", "설정 관리 체계" 요청에서 반드시 사용할 것.
type: general-purpose
model: opus
---

## 작업 흐름

### 1단계: Gradle 멀티모듈 구조 이해
- Architect가 정의한 모듈 구조 검토 (:api, :agent, :rag, :infra, :common)
- 각 모듈의 역할 파악 (웹 모듈 vs 라이브러리 모듈)

### 2단계: `:api` 모듈 부트스트랩
```
:api/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/achul/compliance/
│   │   │       ├── Application.java (@SpringBootApplication)
│   │   │       ├── health/
│   │   │       │   └── HealthController.java (GET /health)
│   │   │       └── config/
│   │   │           └── AppConfig.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       └── application-prod.yml
│   └── test/
│       └── java/
│           └── ApplicationTests.java
└── build.gradle.kts
```

### 3단계: Spring Boot 설정 파일
**application.yml** (공통):
```yaml
spring:
  application:
    name: ai-compliance-guard
  jpa:
    show-sql: false
    hibernate:
      ddl-auto: validate
  datasource:
    hikari:
      maximum-pool-size: 10

server:
  port: 8080
  servlet:
    context-path: /api/v1

logging:
  level:
    root: INFO
    com.achul.compliance: DEBUG
```

**application-local.yml** (로컬 개발):
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/compliance_db
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD:postgres}

  jpa:
    show-sql: true

llm:
  api-key: ${LLM_API_KEY}
  provider: ${LLM_PROVIDER:openai}
```

### 4단계: 헬스체크 엔드포인트
```java
@RestController
@RequestMapping("/health")
public class HealthController {
  
  @GetMapping
  public ResponseEntity<HealthResponse> health() {
    return ResponseEntity.ok(
      HealthResponse.builder()
        .status("UP")
        .timestamp(LocalDateTime.now())
        .version("1.0.0")
        .build()
    );
  }
}
```

### 5단계: build.gradle.kts 설정
주요 의존성:
- Spring Boot Web, Data JPA, Actuator
- PostgreSQL Driver
- Lombok, Jackson
- LangChain4j (또는 LangGraph) — D6 결정 후

### 6단계: .gitignore 정의
```
# IDE
.idea/
*.iml

# Build
build/
.gradle/

# Secrets
.env
.env.local
application-local.yml
secrets/

# OS
.DS_Store
```

## 검증 체크리스트

- [ ] `./gradlew :api:clean build` 성공
- [ ] JAR 파일 생성됨 (`build/libs/`)
- [ ] `./gradlew :api:bootRun` 기동
- [ ] `curl http://localhost:8080/api/v1/health` 200 응답
- [ ] 응답 바디:
  ```json
  {
    "status": "UP",
    "timestamp": "2026-05-26T...",
    "version": "1.0.0"
  }
  ```
- [ ] 로그에 SQL 연결 오류 없음
- [ ] 환경변수 오버라이드 동작 확인

## 라이브러리 모듈 구조 (:common, :rag, :agent 등)

각 라이브러리 모듈은:
- `@SpringBootApplication` 없음
- build.gradle.kts에 `id("io.spring.dependency-management")`만 적용
- DTO, 유틸리티, 공용 설정 포함
- 의존성은 명시적으로 관리

## 필수 체크리스트

- [ ] Gradle wrapper 설정 (gradlew, gradlew.bat)
- [ ] Kotlin DSL (build.gradle.kts) 또는 Groovy DSL (build.gradle) 선택 및 일관성
- [ ] 의존성 버전 정의 (parent BOM 또는 pluginManagement 활용)
- [ ] 프로파일별 설정 작동
- [ ] IDE 임포트 성공 (IntelliJ IDEA)
