# 🛡️ ai-compliance-guard

> Stateful Multi-Agent workflow for zero-hallucination compliance auditing, backed by Hybrid RAG (BM25 + pgvector) and enterprise-grade architecture.

**AI 기반 자동 컴플라이언스(준수성) 검사기** — 복잡한 규정·법률·가이드라인을 근거로 입력된 광고 카피의 법적 위반 소지를 실시간으로 검사하고, 바로 사용할 수 있는 안전한 대체 문구를 제시하는 **AI Micro-SaaS**입니다.

> ✅ **현재 상태 (2026-06): Phase 0~2 완료 · Phase 3 결제만 잔여** — 회원가입 → 로그인 → 광고 카피 입력 → AI 3-Agent 심의 → 위반·근거·대체문구 리포트 → 암호화 이력 저장까지 동작합니다. 결제(토스페이먼츠)는 사업자등록 후 연동 예정. 상세는 [ROADMAP.md](ROADMAP.md).

---

## 🎯 무엇을 해결하나

건강기능식품·화장품의 SNS 광고 카피는 식약처 표시·광고 규제가 엄격하여, 위반 시 행정처분·과징금·게시물 삭제 리스크가 따릅니다. 하지만 셀러·브랜드·마케팅 대행사가 광고 문구마다 법령을 직접 확인하기는 현실적으로 어렵습니다.

`ai-compliance-guard`는 광고를 게시하기 **전에** 위반 여부를 빠르고 정확하게 검사하고, 안전한 대안까지 제시하여 법적 리스크를 사전 예방합니다.

**1차 타겟 도메인**: 건강기능식품/화장품 SNS 광고 심의

## 💡 핵심 가치

- **환각(Hallucination) 제로화** — 비판관 에이전트의 근거 법령 교차 검증으로 잘못된 판정 차단.
- **Remediation(대안 제시)** — 단순 위험 탐지를 넘어 바로 쓸 수 있는 안전한 대체 문구 제안.
- **Data Privacy** — 기업 고객의 광고 카피·계약서가 유출되지 않는 보안 파이프라인.

## 🏗️ 동작 방식

유저가 입력한 콘텐츠를 **Hybrid RAG**로 근거 규정을 검색한 뒤, **Multi-Agent** 파이프라인을 거쳐 최종 리포트를 생성합니다.

```
유저 입력 (광고 카피)
      │
      ▼
 Orchestrator ──────► Hybrid RAG 검색
      │               (BM25 키워드 + 벡터 시맨틱 + Re-ranking)
      │                      │
      │                관련 규정 조항 컨텍스트
      ▼
 검사관 (Auditor)    ──► 법적 위반 소지 및 리스크 리포트 작성
      │
      ▼
 비판관 (Critique)   ──► 근거 법령 교차 검증 (환각 차단)
      │                  └─ 오류 감지 시 검사 단계로 피드백 (순환 루프)
      ▼
 교정관 (Remediator) ──► 안전한 대체 문구 제안
      │
      ▼
 최종 컴플라이언스 리포트
```

- **Stateful Workflow** — 검사 → 비판 → 교정이 순차 진행되며, 비판 단계에서 오류 감지 시 검사 단계로 되돌아가는 순환 루프 제어.
- **Hybrid RAG** — 조항 번호·고유명사 매칭용 키워드 검색(BM25)과 문맥 검색(Vector)을 결합하고 Re-ranking으로 재정렬.

## 🛠️ 기술 스택

| 레이어 | 기술 스택 |
| :--- | :--- |
| Backend | Java 17 / Spring Boot (Gradle 멀티모듈: `:api :agent :rag :infra :common`) |
| AI Orchestration | LangChain4j (LLM 계층) + 자체 상태머신 (D6, ADR-007) |
| Database | PostgreSQL + pgvector + ParadeDB BM25 |
| Search | Hybrid RAG (BM25 + Vector + RRF + Re-ranking) |
| Embedding | BGE-m3 로컬 1024차원 (D3, ADR-005 — 비용 0) |
| LLM Engine | **Gemini Flash 무료 티어** (D5, ADR-006 — 수익화 시 유료 전환) |
| Re-ranker | Upstage Solar / BGE-reranker-v2-m3 로컬 (provider 토글) |
| Security | Spring Security · JWT 이중 토큰(HttpOnly Cookie) · BCrypt · AES-256-GCM |
| Frontend | 바닐라 HTML/CSS/JS (라임 플랫 디자인, 앱인토스 WebView 규격) |
| Deploy | Dockerfile(멀티스테이지) + docker-compose |

## 🗺️ 개발 로드맵

| Phase | 내용 | 상태 |
| :--- | :--- | :--- |
| **Phase 0** | 프로젝트 기반 셋업 (Gradle 멀티모듈, Spring Boot, Docker/pgvector) | ✅ 완료 |
| **Phase 1** | 규정 데이터 구축 + Hybrid RAG 검색 API | ✅ 완료 (law_only Recall@10 88%) |
| **Phase 2** | Multi-Agent 파이프라인 + 하이라이팅 대시보드 UI | ✅ 완료 (3-Agent + 심의소 UI) |
| **Phase 3** | 엔터프라이즈 보안 + 수익화 런칭 | 🟡 인증·암호화·사용량·법적고지·배포 완료, **결제만 잔여** |

선행 기술 결정(D1~D6)은 전원 확정됐습니다. 단계별 상세 작업 항목과 검증 기준은 [ROADMAP.md](ROADMAP.md)를 참고하세요.

## 🚀 로컬 실행

```bash
# 1) DB(ParadeDB: PostgreSQL + pgvector + BM25) 기동
docker compose up -d postgres

# 2) 앱 실행 (로컬 프로파일)
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun

# 3) 접속
#  랜딩  http://localhost:8080/api/v1/landing.html
#  심의  http://localhost:8080/api/v1/index.html
```

`.env`에 `GEMINI_API_KEY`(무료, [발급](https://aistudio.google.com/apikey)), `JWT_SECRET`, `APP_AES_KEY`를 채웁니다. LLM 없이 동작만 보려면 `AGENT_LLM_PROVIDER=mock`. 운영 배포는 [DEPLOY.md](DEPLOY.md).

## 📚 프로젝트 문서

| 문서 | 내용 |
| :--- | :--- |
| [AI_Compliance_Auditor_Plan.md](AI_Compliance_Auditor_Plan.md) | 전체 기획서 (아키텍처·기술 스택·로드맵 개요) |
| [ROADMAP.md](ROADMAP.md) | 무엇을 어떤 순서로 만드는가 (Phase 0~3 상세) |
| [OPERATIONS.md](OPERATIONS.md) | 어떻게 신뢰성 있게 운영하는가 (규정 최신성·품질·비용·법적 리스크) |
| [STRATEGY.md](STRATEGY.md) | 누구에게 어떻게 파는가 (타겟·가격·GTM) |
