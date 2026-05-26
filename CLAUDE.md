# ai-compliance-guard CLAUDE.md

## 🛡️ 하네스: AI Compliance Guard Phase 0

**목표:** Gradle 멀티모듈 + Spring Boot + PostgreSQL + pgvector 기반 프로젝트 기반 셋업. 기술 결정 6개(D1~D6) 확정 및 코드 작성 가능한 빌드 환경 완성.

**트리거:** ai-compliance-guard Phase 0 작업 요청 시 `phase0-orchestrator` 스킬을 사용한다. 선행 기술 결정만 필요한 경우 `tech-decision-maker` 사용 가능.

---

## 🏗️ 하네스 구조

### 에이전트 팀 (5명)

| 에이전트 | 역할 | 파일 |
|---------|------|------|
| Architect | 기술 아키텍처 설계, D1~D6 결정 | `.claude/agents/architect.md` |
| Backend Specialist | Spring Boot 부트스트랩 | `.claude/agents/backend-specialist.md` |
| Database/Infra Engineer | Docker + PostgreSQL + Flyway | `.claude/agents/database-infra-engineer.md` |
| AI/RAG Specialist | LangChain4j/LangGraph 기초 | `.claude/agents/ai-rag-specialist.md` |
| QA/Validator | Phase 0 검증 | `.claude/agents/qa-validator.md` |

### 스킬 (5개)

| 스킬 | 담당 에이전트 | 파일 |
|-----|-------------|------|
| Tech Decision Maker | Architect | `.claude/skills/tech-decision-maker/SKILL.md` |
| Spring Boot Bootstrapper | Backend Specialist | `.claude/skills/spring-boot-bootstrapper/SKILL.md` |
| Docker Infra Composer | Database/Infra Engineer | `.claude/skills/docker-infra-composer/SKILL.md` |
| Phase 0 Orchestrator | 팀 조율 (메인) | `.claude/skills/phase0-orchestrator/SKILL.md` |

### 실행 모드
**에이전트 팀** — 5명이 협업하며 TaskCreate/SendMessage로 자체 조율. 병렬 진행 가능.

---

## 🚀 사용 방법

### Phase 0 시작
```
사용자: "Phase 0 시작해줄래?"
→ phase0-orchestrator 스킬 트리거
→ 기술 결정 → 아키텍처 설계 → Spring Boot/Docker 구현 → 검증
→ 3~4일 내 Phase 0 완료
```

### 기술 결정만 먼저
```
사용자: "D1~D6 기술 결정만 확정해줄래?"
→ tech-decision-maker 스킬 트리거
→ ADR 형태의 결정 문서 생성
→ Architect가 팀과 논의하여 최종 결정
```

---

## 📋 관련 문서

| 문서 | 위치 | 용도 |
|------|------|------|
| README | `../README.md` | 프로젝트 전체 그림 |
| ROADMAP | `../ROADMAP.md` | Phase 0~3 상세 작업 |
| AI_Compliance_Auditor_Plan | `../AI_Compliance_Auditor_Plan.md` | 기획서 |
| OPERATIONS | `../OPERATIONS.md` | 규정 데이터·품질·비용·법적 리스크 운영 |
| STRATEGY | `../STRATEGY.md` | 타겟·가격·GTM |

---

## 🔄 선행 기술 결정 (D1~D6)

아래 6개 결정은 Phase 진행 중 번복하면 비용이 크다. 해당 Phase 진입 전 반드시 확정한다.

| 결정 | Phase 진입 | 후보 및 선택 기준 |
|------|----------|----------------|
| D1: Gradle 모듈 | Phase 0 | 초기 단순 vs 선제적 분할 |
| D2: 한국어 BM25 | Phase 1 | ParadeDB vs OpenSearch vs Lucene |
| D3: 임베딩 모델 | Phase 1 | OpenAI vs Upstage vs BGE-m3 |
| D4: Re-ranking | Phase 1 | Cohere vs Upstage vs cross-encoder |
| D5: LLM 엔진 | Phase 2 | GPT-4o vs Upstage Solar |
| D6: 에이전트 오케스트레이션 | Phase 2 | LangChain4j vs LangGraph |

**현재 상태**: ✅ D1 확정 (2026-05-26) → Phase 0 완료, Phase 1 진입 준비

---

## 📊 Phase 0 작업 분해

| 작업 | 담당 | 의존성 | 일정 |
|------|------|--------|------|
| 기술 결정 (D1~D6) | Architect | 없음 | 1일 |
| Gradle 멀티모듈 | Architect | 기술 결정 | 1일 |
| Spring Boot 부트스트랩 | Backend Specialist | Gradle | 1일 |
| Docker Compose 구성 | DB/Infra Engineer | 없음 (병렬) | 1일 |
| Flyway 마이그레이션 | DB/Infra Engineer | Spring Boot | 1일 |
| AI 아키텍처 검토 | AI/RAG Specialist | D6 결정 | 1일 |
| Phase 0 검증 | QA/Validator | 모두 완료 | 1일 |

**예상 총 일정**: 3~4일 (병렬 진행)

---

## ✅ Phase 0 검증 체크리스트 (완료 2026-05-26)

- [x] Gradle 빌드 성공 (`./gradlew build`)
- [x] Spring Boot 기동 성공 (`./gradlew :api:bootRun`)
- [x] 헬스체크 엔드포인트 응답 (`GET /health` → 200)
- [x] Docker Compose 컨테이너 정상
- [x] PostgreSQL + pgvector 확장 설치 확인
- [x] Flyway 마이그레이션 성공
- [x] 환경변수 오버라이드 작동
- [x] `.gitignore` 정확 (시크릿 보호)

---

## 📝 변경 이력

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-05-26 | 초기 구성 | 전체 | ai-compliance-guard Phase 0 하네스 구성 |

---

---

## 🛡️ 하네스: AI Compliance Guard Phase 1

**목표:** 규정 데이터 수집 + Hybrid RAG 파이프라인 완성. D2/D3/D4 기술 결정 → 규정 PDF 수집·파싱 → 벡터+키워드 검색 → Re-ranking → 검색 API 완성. 6명의 팀(기존 5명 + Data Engineer 추가)이 병렬 진행, 7~10일 내 완료.

**트리거:** ai-compliance-guard Phase 1 작업 요청 시 `phase1-orchestrator` 스킬을 사용한다. 단순 기술 결정만 필요한 경우 `tech-decision-maker` 사용 가능.

### 에이전트 팀 (6명)

기존 5명 + Data Engineer 추가:

| 에이전트 | 역할 | 파일 |
|---------|------|------|
| Architect | D2/D3/D4 기술 결정 | `.claude/agents/architect.md` |
| Backend Specialist | 검색 API 구현 | `.claude/agents/backend-specialist.md` |
| Database/Infra Engineer | DB 스키마 최적화, 인덱스 | `.claude/agents/database-infra-engineer.md` |
| AI/RAG Specialist | 임베딩, 하이브리드 검색 | `.claude/agents/ai-rag-specialist.md` |
| Data Engineer | PDF 수집·파싱·청킹 | `.claude/agents/data-engineer.md` |
| QA/Validator | Recall@K 검증 | `.claude/agents/qa-validator.md` |

### 스킬 (8개: Phase 0 스킬 + Phase 1 신규 4개)

| 스킬 | 담당 에이전트 | 파일 |
|-----|-------------|------|
| Tech Decision Maker | Architect | `.claude/skills/tech-decision-maker/SKILL.md` |
| Regulation Collector | Data Engineer | `.claude/skills/regulation-collector/SKILL.md` |
| PDF Chunker | Data Engineer | `.claude/skills/pdf-chunker/SKILL.md` |
| Hybrid RAG Builder | AI/RAG Specialist + Backend Specialist | `.claude/skills/hybrid-rag-builder/SKILL.md` |
| Phase 1 Orchestrator | 팀 조율 (메인) | `.claude/skills/phase1-orchestrator/SKILL.md` |

### 실행 모드
**에이전트 팀** — 6명이 협업하며 TaskCreate/SendMessage로 자체 조율. 병렬 진행 가능 (Step 1과 Step 2 동시).

### Phase 1 사용 방법

```
사용자: "Phase 1 시작해줄래?"
→ phase1-orchestrator 스킬 트리거
→ Step 1: D2/D3 기술 결정 + Step 2: 규정 수집·청킹 병렬
→ Step 3: Hybrid RAG 파이프라인 (Step 1/2 완료 후)
→ Step 4: 검증 및 평가
→ 7~10일 내 Phase 1 완료
```

---

## 🎯 다음 액션

1. **Phase 0 완료 후**: `phase1-orchestrator` 스킬 호출
2. **기술 결정**: D2(BM25), D3(임베딩 모델), D4(Re-ranking)
3. **팀 확장**: Data Engineer 추가 (총 6명)
4. **병렬 진행**: 기술 결정 + 규정 수집·청킹 동시
5. **7~10일 내 Phase 1 완료**: 검색 API 동작 확인 후 Phase 2 준비

---

## 📝 변경 이력

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-05-26 | 초기 구성 | Phase 0 | ai-compliance-guard Phase 0 하네스 구성 |
| 2026-05-26 | Phase 1 하네스 추가 | Phase 1 | Data Engineer + 규정 수집·RAG 파이프라인 하네스 |

---

## 🔍 더 읽을 자료

- 백엔드 개발자 학습 지도: `/Users/chul/Library/Mobile Documents/iCloud~md~obsidian/Documents/aiden/wiki/백엔드 학습 지도.md`
- AI 에이전트와 RAG: `/Users/chul/Library/Mobile Documents/iCloud~md~obsidian/Documents/aiden/wiki/AI 에이전트와 RAG 학습 지도.md`
