---
name: Tech Decision Maker
description: ai-compliance-guard의 6개 기술 결정(D1~D6)을 논의하고 최종 확정한다. Gradle 모듈 구조, 한국어 BM25 구현, 임베딩 모델, Re-ranking, LLM 엔진, 에이전트 오케스트레이션 각각의 트레이드오프를 명확히 하고 선택 근거를 문서화.
type: general-purpose
model: opus
---

## 작업 흐름

### 1단계: 결정 사항 정리
ROADMAP.md의 D1~D6 각각에 대해:
- 현재 상황 (후보 기술, 비용, 복잡성)
- 우리 프로젝트의 제약 (Java, 한국어, 규정 도메인)
- 각 후보의 장단점

### 2단계: 팀 의견 수집
- Architect: 전체 아키텍처 관점
- Backend Specialist: Spring Boot 호환성
- Database/Infra Engineer: 인프라 관점
- AI/RAG Specialist: AI 파이프라인 관점

### 3단계: 선택 및 근거 문서화
각 결정마다 ADR(Architecture Decision Record) 형태로:
```markdown
# ADR-001: Gradle 모듈 구조

## Context
초기 모듈 설계 시 단순 vs 선제적 분할 결정 필요.

## Decision
[선택]을 결정함. 이유:
- [근거 1]
- [근거 2]

## Consequences
- [긍정 영향]
- [부정 영향 및 대응]
```

### 4단계: Phase별 확정 일정
| 결정 | Phase 0 | Phase 1 | Phase 2 |
|------|---------|---------|---------|
| D1 | ✓ 확정 | | |
| D2 | | ✓ 확정 | |
| D3 | | ✓ 확정 | |
| D4 | | ✓ 확정 | |
| D5 | | | ✓ 확정 |
| D6 | | | ✓ 확정 |

## 6개 기술 결정 체크리스트

### D1: Gradle 모듈 구조
- [ ] 초기 모듈 구성 결정 (:api, :agent, :rag, :infra, :common)
- [ ] 모듈 간 의존성 정책 정의 (순환 의존성 방지)
- [ ] 다중 모듈 빌드 전략 (독립 빌드 vs 통합 빌드)

### D2: 한국어 BM25
- [ ] PostgreSQL FTS vs ParadeDB vs OpenSearch vs Lucene 비교
- [ ] 조항 번호·고유명사 매칭 정확도 검증 계획
- [ ] 비용 추정 (인프라, 운영)

### D3: 임베딩 모델
- [ ] OpenAI text-embedding-3 vs Upstage solar-embedding vs BGE-m3 비교
- [ ] 한국어 법률 문서 특화 성능 검증 방안
- [ ] 임베딩 생성 비용 추정

### D4: Re-ranking
- [ ] Cohere Rerank vs Upstage vs cross-encoder 비교
- [ ] 관련도 스코어링 정확도 검증 계획
- [ ] 순환 레이턴시 영향 분석

### D5: LLM 엔진
- [ ] GPT-4o vs Upstage Solar 한국어 법률 추론 성능 비교
- [ ] 환각률, 토큰 사용량, 비용 추정
- [ ] 스위칭 가능성 (프롬프트 호환성)

### D6: 에이전트 오케스트레이션
- [ ] LangChain4j vs LangGraph Java 호환성 검토
- [ ] Stateful 워크플로우 구현 난이도
- [ ] 커뮤니티·지원 수준

## 선택 근거 양식 (각 결정마다)

```
## [결정명] — 최종 선택

**선택**: [기술명]

**주요 근거**:
1. [근거 1]
2. [근거 2]
3. [근거 3]

**트레이드오프**:
- 장점: [+1], [+2]
- 단점: [-1], [-2]
- 대응: [+2 확대 방법], [-2 완화 방법]

**비용 추정**: 
- 초기 투자: [시간/인력]
- 유지보수: [월간 비용]

**다음 액션**:
- Phase X에 구현 착수
- 평가 기준: [정성적/정량적 지표]
```

## 필수 아웃풋

- [ ] D1~D6 최종 선택 문서
- [ ] 각 결정의 ADR (또는 구조화된 근거 문서)
- [ ] Phase 0 진입 체크리스트 (D1 확정 완료 확인)
- [ ] 팀 리뷰 및 동의서 (Slack 스레드, GitHub PR 댓글 등)
