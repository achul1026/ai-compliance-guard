# ADR-001: Gradle 모듈 구조 (D1)

- 상태: **확정 (Accepted)**
- 결정자: architect (팀 리뷰 반영)
- 결정일: 2026-05-26
- Phase: 0
- 관련 결정: D6 (모듈 의존성에 영향)

---

## Context

ai-compliance-guard는 Gradle 멀티모듈 Spring Boot 프로젝트로 시작한다. 초기 모듈 분할 전략에 대해 두 가지 선택지가 있다.

### 옵션 A — 단일 모듈로 시작, 필요 시 분할
- 장점: 초기 학습 비용 낮음, 초기 속도 빠름
- 단점: Phase 1~2에서 RAG/Agent 컴포넌트가 커지면 의존성이 얽히기 쉽고 재분할 비용이 큼

### 옵션 B — 선제적 멀티모듈 분할 (5개 모듈)
- 장점: 도메인 경계가 코드 레벨에서 강제됨. RAG/Agent를 독립적으로 진화 가능. 테스트·캐시 분리.
- 단점: 초기 모듈 간 의존성 정책 정립 필요. wrapper/빌드 캐시 등 인프라 비용.

### 우리 프로젝트의 특성
1. **Hybrid RAG + Multi-Agent**가 핵심이며 각각이 독립 진화 영역 (검색·임베딩 vs 에이전트 오케스트레이션)
2. ROADMAP 자체가 `:api / :agent / :rag / :infra / :common` 5분할을 전제
3. Phase 2의 D6(LangChain4j vs LangGraph) 결정이 `:agent` 모듈에만 영향이 격리되어야 함
4. Phase 3에서 결제/인증을 `:api` 안에 두더라도 RAG는 독립 유지가 가능해야 함

---

## Decision

**옵션 B를 선택한다.** 초기부터 다음 5개 모듈로 분할한다.

| 모듈 | 타입 | 역할 | 주요 의존성 (in-project) |
|------|------|------|--------------------------|
| `:api` | Spring Boot 실행 모듈 | REST 엔드포인트, 컨트롤러, 인증, 설정 진입점 | `:agent`, `:rag`, `:infra`, `:common` |
| `:agent` | 라이브러리 | Multi-Agent 파이프라인 (Auditor/Critique/Remediator), 오케스트레이션 | `:rag`, `:common` |
| `:rag` | 라이브러리 | Hybrid 검색(BM25 + Vector), Re-ranking, 임베딩 호출 | `:infra`, `:common` |
| `:infra` | 라이브러리 | DB 접근(JPA/JDBC), Flyway 마이그레이션 리소스, 외부 API 클라이언트 | `:common` |
| `:common` | 라이브러리 | 공용 DTO, 도메인 모델, 유틸, 예외, 상수 | (없음) |

### 의존성 규칙
- **단방향 의존성만 허용**: `api → agent → rag → infra → common`
- `:common`은 외부 모듈에 의존하지 않는다.
- `:infra`는 `:rag`, `:agent`, `:api`를 참조하지 않는다.
- 역방향 호출이 필요하면 `:common`에 인터페이스를 두고 의존성 역전.
- 순환 의존성 발견 시 빌드 실패하도록 향후 ArchUnit 등으로 강제 가능 (Phase 1 이후).

### 빌드 전략
- **Kotlin DSL** (`build.gradle.kts`): 타입 안정성, IDE 자동완성, 향후 컨벤션 플러그인 도입 용이
- **루트 build.gradle.kts에서 subprojects 공통 설정** (Java 17, Spring 의존성 관리, repositories)
- **`:api`만 Spring Boot 실행 가능**, 나머지는 `bootJar` 비활성화 + `jar` 활성화
- **Java 17** (Spring Boot 3.x LTS 기준)
- **Spring Boot 3.3.x + Spring Dependency Management 플러그인**

---

## Consequences

### 긍정
- 도메인 경계가 컴파일 시점에 강제됨 (`:rag`가 `:api`를 부르는 실수 차단)
- D6(LangChain4j vs LangGraph) 변경 시 영향이 `:agent`에 격리됨
- 모듈별 테스트 캐시 → 점진 빌드 빨라짐
- Phase 3에서 결제·인증 추가 시 `:api`만 영향

### 부정 / 대응
- (-) 초기 build.gradle.kts 5개 + settings.gradle.kts 작성 비용 → 한 번 작성하면 끝
- (-) IDE 인덱싱 다소 무거움 → Phase 0 규모에서는 무시 가능
- (-) `:common`이 god-module화 위험 → 정기적으로 책임 검토 (Phase 1 종료 시 점검)
- (-) 모듈 간 순환 의존성 실수 가능 → 단방향 규칙 명시, Phase 1 이후 ArchUnit 도입

### 향후 결정 영향
- **D6 (Phase 2)**: LangChain4j/LangGraph 둘 다 `:agent` 모듈에서 흡수 → 본 결정의 영향 없음
- **Phase 3 결제·인증 추가 시**: `:api`에 컨트롤러/서비스를 추가하거나, 별도 `:billing` 모듈로 분할 가능 (옵션 열어둠)

---

## 팀 리뷰 요약

| 팀원 | 피드백 | 반영 |
|------|--------|------|
| backend-specialist | `:api`만 Spring Boot 실행 모듈로 한정, 나머지는 라이브러리 jar로 빌드 | 반영 |
| database-infra-engineer | Flyway 마이그레이션 리소스는 `:infra` 모듈의 classpath에 두기 (`:infra/src/main/resources/db/migration`) | 반영 |
| ai-rag-specialist | LangChain4j 의존성은 `:agent`에만 추가 가능하도록 모듈 경계 유지 | 반영 |
| qa-validator | `./gradlew projects` 출력으로 5개 모듈이 모두 등록됐는지 검증 가능 | 반영 |

---

## 다음 액션

1. ARCH-001: `settings.gradle.kts` + 루트 `build.gradle.kts` + 모듈별 `build.gradle.kts` 작성
2. Gradle wrapper 생성 (8.7 기준)
3. `./gradlew projects`로 모듈 등록 검증
