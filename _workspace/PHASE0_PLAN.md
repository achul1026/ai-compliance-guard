# Phase 0 마스터 플랜 — ai-compliance-guard

> 작성일: 2026-05-26
> 오케스트레이터: phase0-orchestrator
> 팀원: architect, backend-specialist, database-infra-engineer, ai-rag-specialist, qa-validator

---

## 1. Phase 0 목표

코드를 작성할 수 있는 **빌드/실행 환경 완성**.

- Gradle 멀티모듈 + Spring Boot 부트스트랩
- Docker Compose + PostgreSQL + pgvector
- Flyway 마이그레이션 기초
- 환경변수/시크릿 관리 체계
- D1 기술 결정(Gradle 모듈 구조) 확정
- D6 사전 검토(LangChain4j vs LangGraph) — 결정은 Phase 2

---

## 2. 작업 분담표 (RACI)

| ID | 작업 | 담당 | 의존성 | 산출물 | 검증 기준 |
|----|------|------|--------|--------|-----------|
| TECH-001 | D1 Gradle 모듈 구조 ADR | architect | 없음 | `_workspace/ADR-001-gradle-modules.md` | 팀 리뷰 동의 |
| ARCH-001 | Gradle 멀티모듈 골격 + wrapper | architect | TECH-001 | `settings.gradle.kts`, root `build.gradle.kts`, 모듈별 `build.gradle.kts`, gradlew | `./gradlew projects` 성공 |
| DB-001 | Docker Compose + pgvector | database-infra-engineer | 없음 (병렬) | `docker-compose.yml`, `database/init/01-init.sql`, `.env.example` | `docker compose up` 성공 + 확장 설치 확인 |
| BACK-001 | `:api` 모듈 부트스트랩 | backend-specialist | ARCH-001 | `Application.java`, `HealthController`, `application*.yml` | `GET /health` 200 |
| DB-002 | Flyway 마이그레이션 기초 | database-infra-engineer | ARCH-001, BACK-001 | `database/migrations/V1__init_schema.sql`, Flyway 설정 | 마이그레이션 실행 성공 |
| AI-001 | D6 사전 의견 메모 | ai-rag-specialist | 없음 (병렬) | `_workspace/AI-001-orchestration-review.md` | 팀 공유 |
| QA-001 | Phase 0 점진 검증 | qa-validator | 위 전부 | `_workspace/PHASE0_VALIDATION.md` | 체크리스트 전부 통과 |

---

## 3. 의존성 그래프

```
TECH-001 ──► ARCH-001 ──► BACK-001 ──┐
                       │              ├─► DB-002 ──┐
DB-001 ──────────────────────────────┘              ├─► QA-001
AI-001 (병렬)──────────────────────────────────────┘
```

병렬 진행 가능: `TECH-001`, `DB-001`, `AI-001` 동시 착수.

---

## 4. 예상 일정 (3일)

| Day | 작업 |
|-----|------|
| Day 1 | TECH-001 (D1 확정) → ARCH-001 (Gradle 골격) / DB-001 (Docker) / AI-001 (D6 메모) 병렬 |
| Day 2 | BACK-001 (Spring Boot 부트스트랩) → DB-002 (Flyway) |
| Day 3 | QA-001 점진 검증 → Phase 1 진입 승인 |

---

## 5. 통신 프로토콜

| 시나리오 | 방식 |
|---------|------|
| 산출물 공유 | 파일 기반 (`/Users/chul/projects/ai-compliance-guard/` 하위) |
| 작업 완료 보고 | `_workspace/STATUS.md` 업데이트 |
| 문제 발생 | `_workspace/ISSUES.md`에 등록 후 담당 에이전트에게 재할당 |
| 결정 사항 | ADR 형식으로 `_workspace/ADR-*.md` |

---

## 6. 검증 계획 (QA-001)

### 6-1. 빌드 검증
- `./gradlew clean build` 성공
- 모든 모듈이 정상 컴파일

### 6-2. 기동 검증
- `./gradlew :api:bootRun` 정상 기동
- `curl http://localhost:8080/api/v1/health` → 200 + `{"status":"UP",...}`
- 로그에 ERROR 없음 (DB 미연동 상태에서도 기동 성공)

### 6-3. 인프라 검증
- `docker compose up -d` 성공
- `docker compose ps` 모든 컨테이너 healthy
- `psql -h localhost -U postgres -d compliance_db -c "SELECT extname FROM pg_extension WHERE extname='vector';"` → vector 행 반환

### 6-4. DB 통합 검증
- Spring Boot가 PostgreSQL에 정상 연결
- Flyway 마이그레이션이 `flyway_schema_history`에 V1 기록
- `regulations` 테이블이 정상 생성

### 6-5. 설정 검증
- `application-local.yml`, `application-prod.yml` 분리
- `.gitignore`에 `.env`, `application-local.yml`, `build/`, `.idea/` 포함
- 환경변수 오버라이드: `DB_PASSWORD=custom docker compose up` 시 반영

---

## 7. 문제 시 대응

| 문제 | 1차 대응자 | 에스컬레이션 |
|------|-----------|-------------|
| Gradle 빌드 실패 | backend-specialist | architect |
| Spring Boot 기동 실패 | backend-specialist | architect |
| Docker/pgvector 문제 | database-infra-engineer | architect |
| Flyway 마이그레이션 실패 | database-infra-engineer | backend-specialist |
| 설정/환경변수 문제 | backend-specialist | database-infra-engineer |

---

## 8. Phase 1 진입 조건

- [ ] 모든 검증 체크리스트 통과
- [ ] D1 ADR 확정 및 팀 동의
- [ ] D6 사전 메모 공유 (Phase 2 결정 입력)
- [ ] `_workspace/PHASE0_VALIDATION.md` 보고서 작성
- [ ] 산출물 git 커밋 가능 상태 (단, 커밋 자체는 사용자 승인 후)
