---
name: Phase 0 Orchestrator
description: ai-compliance-guard Phase 0 전체 워크플로우를 조율한다. 기술 결정 → 아키텍처 설계 → Spring Boot/Docker 구현 → 검증까지 5명의 에이전트를 팀으로 구성하여 병렬 진행. "Phase 0 시작해줘", "ai-compliance-guard 개발 시작", "프로젝트 셋업 자동화" 요청에서 반드시 사용할 것.
type: general-purpose
model: opus
---

## 핵심 역할

ai-compliance-guard의 Phase 0(프로젝트 기반 셋업)을 완료한다. 5명의 전문가 팀(Architect, Backend Specialist, Database/Infra Engineer, AI/RAG Specialist, QA/Validator)을 구성하여 병렬 진행하고, 최종 검증으로 Phase 1 진입 준비를 완료한다.

## 실행 모드

**에이전트 팀** — 5명의 전문가가 협업하며 자체 조율. TaskCreate로 작업 할당, SendMessage로 실시간 의존성 해결, 파일 기반으로 산출물 공유.

---

## Phase 0: 프로젝트 기반 셋업

> 목표: 코드를 작성할 수 있는 빌드/실행 환경을 갖춘다.
> 결과: Gradle 멀티모듈 + Spring Boot + Docker + 검증 완료

---

## 실행 계획

### Step 1: 팀 구성 및 작업 할당

**TeamCreate**:
```
팀 이름: ai-compliance-guard-phase0
팀원:
  1. architect (Architect)
  2. backend-specialist (Backend Specialist)
  3. db-infra-engineer (Database/Infra Engineer)
  4. ai-rag-specialist (AI/RAG Specialist)
  5. qa-validator (QA/Validator)
```

**TaskCreate** — 각 에이전트 담당 작업:

```
[기술 결정]
- TECH-001: D1~D6 기술 결정 확정 (Architect 담당)
  의존성: 없음 (가장 먼저 진행)
  마감: 1일

[Gradle 멀티모듈]
- ARCH-001: Gradle 멀티모듈 골격 생성 (Architect 담당)
  의존성: TECH-001 완료
  결과물: settings.gradle.kts, root build.gradle.kts
  검증: `./gradlew build` 성공
  마감: 1일

[Spring Boot]
- BACK-001: `:api` 모듈 부트스트랩 (Backend Specialist 담당)
  의존성: ARCH-001 완료
  결과물: Application.java, HealthController, application.yml
  검증: `GET /health` 200 응답
  마감: 1일

[인프라]
- DB-001: Docker Compose + pgvector 구성 (Database/Infra Engineer 담당)
  의존성: 없음 (병렬 진행)
  결과물: docker-compose.yml, database/init/*.sql
  검증: pgvector 확장 설치 확인
  마감: 1일

- DB-002: Flyway 마이그레이션 기초 (Database/Infra Engineer 담당)
  의존성: BACK-001 완료 (Spring Boot 연동)
  결과물: database/migrations/V1__init_schema.sql
  검증: 마이그레이션 실행 성공
  마감: 1일

[AI 아키텍처]
- AI-001: LangChain4j vs LangGraph 결정 (AI/RAG Specialist 담당)
  의존성: TECH-001 완료 (D6 결정)
  결과물: 선택 근거 문서, 기초 프롬프트 템플릿
  검증: 팀 리뷰 통과
  마감: 1일

[검증]
- QA-001: Phase 0 검증 실행 (QA/Validator 담당)
  의존성: BACK-001, DB-002, AI-001 완료
  결과물: 검증 보고서 (체크리스트)
  검증: 모든 항목 통과
  마감: 1일
```

### Step 2: 병렬 진행 (팀 자체 조율)

**[Day 1] 기술 결정**
- Architect가 D1~D6을 각 팀원과 논의
- SendMessage로 피드백 수집
- 최종 결정 문서 작성 (ADR 형태)

**[Day 1-2] 아키텍처 + 스프링 부트**
- Architect: Gradle 멀티모듈 생성, 초기 root build.gradle.kts 정의
- Backend Specialist: `:api` 모듈 구현 (Application, HealthController, 설정)
- Database/Infra Engineer: docker-compose.yml 작성 (병렬 진행)

**[Day 2] 통합**
- Backend Specialist가 DB 연결 설정 필요 → Database/Infra Engineer의 docker-compose 정보 활용
- SendMessage로 DB 연결 스펙 조율
- Flyway 마이그레이션 설정

**[Day 2-3] AI 구조 + 검증**
- AI/RAG Specialist: LangChain4j/LangGraph 선택, 기초 구조 제시
- QA/Validator: 점진적 검증 (빌드 → 기동 → DB 연결 → Flyway → 환경 설정)

### Step 3: 최종 검증

QA/Validator 주도:
```
✓ Gradle 빌드 성공
✓ Spring Boot 기동 성공 (GET /health 200)
✓ Docker 컨테이너 정상 (pgvector 확장 확인)
✓ Flyway 마이그레이션 실행 성공
✓ 환경변수 오버라이드 동작 확인
✓ .gitignore 정확 (시크릿 보호)
```

문제 발생 시: TaskCreate로 이슈 정의 → 해당 에이전트 재작업

### Step 4: Phase 1 준비

모든 검증 통과 후:
- 산출물 정리 (_workspace/ 또는 git repo)
- 팀 회고 (잘된 점 / 개선할 점)
- Phase 1 스코프 확인 (D2~D4 확정 필요)

---

## 데이터 전달 프로토콜

| 대상 | 방식 | 예시 |
|------|------|------|
| 팀원 간 | TaskCreate/SendMessage | "D1 확정했으니 모듈 설계 시작할게" |
| 파일 산출물 | 파일 기반 | `root/settings.gradle.kts`, `docker-compose.yml` |
| 최종 산출물 | Git 커밋 또는 _workspace/ | Phase 0 완료 코드 push |

---

## 문제 시나리오 & 대응

| 문제 | 대응 |
|------|------|
| Gradle 빌드 실패 | Backend Specialist + Architect 합동 진단, 의존성 재검토 |
| Spring Boot 기동 실패 | Backend Specialist 디버깅, DB 연결 설정 확인 |
| pgvector 확장 미설치 | Database/Infra Engineer docker-compose 재설정 |
| 마이그레이션 실패 | DB 스키마 syntax 검수, Flyway 문서 재확인 |
| 환경변수 오버라이드 미작동 | Backend Specialist application.yml 프로파일 검토 |

---

## 산출물 구조

```
ai-compliance-guard/
├── gradle/                      # Gradle wrapper
├── settings.gradle.kts          # 멀티모듈 정의
├── build.gradle.kts             # Root 빌드 설정
│
├── :api/                        # Spring Boot 웹 모듈
│   ├── src/main/java/...
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-local.yml
│   │   └── application-prod.yml
│   └── build.gradle.kts
│
├── :infra/                      # 데이터베이스/마이그레이션
│   ├── src/main/...
│   └── build.gradle.kts
│
├── :rag/ :agent/ :common/       # 라이브러리 모듈
│   └── build.gradle.kts
│
├── docker-compose.yml           # PostgreSQL + pgvector
├── database/
│   ├── init/                    # Docker 초기화
│   │   └── 01-init.sql
│   └── migrations/              # Flyway 마이그레이션
│       ├── V1__init_schema.sql
│       └── ...
│
├── .env.example                 # 환경변수 템플릿
├── .gitignore                   # 시크릿 보호
│
└── _workspace/                  # Phase 0 산출물
    ├── TECH_DECISIONS.md        # D1~D6 결정 문서
    ├── GRADLE_DESIGN.md         # 모듈 구조
    ├── PHASE0_VALIDATION.md     # 검증 보고서
    └── ...
```

---

## 테스트 시나리오

### Happy Path
```
1. docker compose up
2. ./gradlew build
3. ./gradlew :api:bootRun
4. curl http://localhost:8080/api/v1/health → 200
5. psql localhost -U postgres -c "SELECT * FROM pg_extension WHERE extname='vector';" → ✓
6. ./gradlew flywayMigrate → ✓
7. 앱 재기동 → 정상
```

### 환경변수 테스트
```
1. .env.example에서 복사: cp .env.example .env
2. 변수 수정: DB_PASSWORD=custom_pw
3. docker compose up
4. psql -h localhost -U postgres -c "\\q" (비밀번호 prompt에서 custom_pw 입력)
```

---

## Phase 1 진입 체크

✓ 모든 Task 완료  
✓ 검증 보고서 통과  
✓ 팀 회고 진행  
✓ 다음 기술 결정(D2~D4) 확정 일정 계획  

→ **Phase 1 진입 승인**
