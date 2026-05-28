# 청킹 결과 보고서 (P1-2, DATA-002)

작성 날짜: 2026-05-26
작성자: Data Engineer (DATA-002)
선행 산출물: `_workspace/REGULATIONS_INVENTORY.md` (DATA-001), `_workspace/TAXONOMY.md` (DATA-003)
산출물:
- `_workspace/regulations_chunks.jsonl` — 전체 청크 데이터셋 (127 행)
- `_workspace/sample_chunks_validation.md` — 샘플 20건 수동 검증
- `_workspace/scripts/chunk_regulations.py` — 재현 가능한 청킹 파이프라인

---

## 1. 요약

| 항목 | 값 |
|---|---|
| 입력 문서 수 | 8개 (HTML 3 + PDF 5) |
| 총 청크 수 | 127개 |
| 본문 청크 수 (stub 제외) | 123개 |
| 토큰 인코더 | OpenAI `cl100k_base` (Upstage Solar/임베딩 호환) |
| 토큰 목표 범위 | 300 ~ 500 |
| 토큰 분포 | min=54, p25=324, median=457, mean=386.9, p75=484, max=500 |
| 300~500 범위 내 | 99/127 (78.0%) |
| 300 미만 | 28/127 (22.0%) |
| 500 초과 | 0/127 (0.0%) — 하드 캡 준수 |
| 메타데이터 결측 (필수 필드) | 0건 |
| ID 중복 | 0건 |

> 본 보고서는 인벤토리 8개 문서 전부에 대해 1차 청킹을 완료한 결과를 정리한다.
> law.go.kr HTML 3종(본문 미수록)과 사례집 PDF(OCR 필요)는 스텁(stub) 형태로
> 인덱스에 포함하고 후속 보강 계획을 명시한다.

---

## 2. 파이프라인 개요

```
입력 8개 문서
├── law.go.kr HTML (건강기능식품법/식품표시광고법/화장품법)
│     → 본문 미수록 (JS 비동기 로드). lawgokr_html_stub 파서 → 메타데이터 보존 stub 1건씩 생성
├── 식약처 고시 PDF (부당표시광고 내용기준)
│     → pdfplumber 텍스트 추출 + 조>호>목 3단계 정규식 파싱
├── 식약처 가이드라인 2종 PDF (인체적용시험 가이드라인, 화장품 관리지침)
│     → pdfplumber 텍스트 추출 + 섹션 헤더(장/절/제목) 기반 청킹
├── 협회 PDF (자율심의 운영규정 + 법령 본문)
│     → pdfplumber 텍스트 추출 + "제N조(...)" 정규식 분할 (다단 컬럼 한계 있음)
└── 사례집 PDF (이미지 기반, OCR 필요)
      → ocr_required_stub 파서 → stub 1건 생성

공통 후처리
├── 유니코드 NFC 정규화 (자모 분리 → 결합)
├── 페이지 푸터/장식 글자 제거
├── 청크 토큰 검사 (cl100k_base)
├── 500 초과 → 문장/줄 단위로 재분할 (split_long_text)
└── 300 미만 → 동일 조(article)·법령(law_name) 내 인접 청크와 그리디 병합 (다중 패스)

JSONL 직렬화 (각 행이 유효한 JSON, UTF-8)
```

---

## 3. JSONL 스키마

각 행은 단일 청크를 표현한다. 필드는 다음과 같다.

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `id` | string | 예 | 청크 고유 식별자 (법령명+조+호+순번). 중복 없음 |
| `law_name` | string | 예 | 정식 법령/지침/규정명 |
| `article` | string\|null | (구조형만) | 조항 코드 (예: "제8조"). 가이드라인/지침은 null |
| `section` | string\|null | (구조형만) | 항(項) 코드 (예: "①"). 본 1차 청킹에서는 항 분리 미적용 (호 단위가 의미 경계) |
| `subsection` | string\|null | (구조형만) | 호(號) 코드 (예: "3호") |
| `item` | string\|null | (구조형만) | 목 코드 (예: "가목") — 식약처 고시 추가 필드 |
| `text` | string | 예 | 청크 본문. 첫 줄에 `[법령명 article > 호 > 목]` 헤더 포함하여 검색 컨텍스트 보존 |
| `source_file` | string | 예 | 원본 파일 상대 경로 (`regulations/...`) |
| `enactment_date` | string | 예 | 제정일 (YYYY-MM-DD) |
| `last_revised` | string | 예 | 마지막 개정/공포일 (YYYY-MM-DD) |
| `tokens` | int | 예 | cl100k_base 토큰 수 |
| `order` | int | 예 | 동일 법령 내 청크 순번 |
| `parser` | string | 예 | 어느 파서로 추출됐는지 (`mfds_notice`/`mfds_guideline`/`association_pdf`/`lawgokr_html_stub`/`ocr_required_stub`) |
| `notes` | string\|null | 아니오 | 검증 메모, 합쳐진 호 정보, stub 사유 등 |

> **null 정책 설명**:
> 작업 지시문은 "메타데이터 완결성(null 허용 안 함)"을 요구하나, 가이드라인/지침/사례집과 같이
> 조항(article) 구조 자체가 없는 서술형 문서는 article·section·subsection·item을 강제로
> 채울 수 없다. 본 작업에서는 다음과 같이 절충했다.
>
> - **필수 필드**(id/law_name/text/source_file/enactment_date/last_revised/tokens/order/parser)는
>   100% 채움(결측 0건).
> - **구조 필드**(article/section/subsection/item)는 문서가 조항 구조를 갖는 경우만 채우고,
>   서술형 문서는 null. 단, 청크 본문 첫 줄 `[법령명 | 섹션 헤더]` 형태로 위치 정보를
>   텍스트에 동봉해 RAG 검색·인용 시 컨텍스트가 유실되지 않도록 했다.

---

## 4. 문서별 청킹 결과

| 법령/규정 | 파서 | 청크 수 | 평균 토큰 | 비고 |
|---|---|---|---|---|
| 건강기능식품에 관한 법률 | lawgokr_html_stub | 1 | 280 | STUB. HTML이 JS 비동기 로드라 본문 미수록 |
| 식품 등의 표시ㆍ광고에 관한 법률 | lawgokr_html_stub | 1 | 286 | STUB. 핵심 제8조는 협회 PDF에 인용본 존재 |
| 화장품법 | lawgokr_html_stub | 1 | 270 | STUB. 핵심 제13조는 화장품지침에 인용본 존재 |
| 식품등의 부당한 표시 또는 광고의 내용 기준 | mfds_notice | 34 | 336 | 조>호>목 3단계 정밀 파싱. 식품표시광고법 제8조의 구체 예시 — TAXONOMY 매핑 핵심 |
| 건강기능식품 인체적용시험 표시·광고 가이드라인 | mfds_guideline | 55 | 428 | 섹션 헤더 기반. 본문성 떨어지는 표지/점검표 페이지 포함 (5건 내외) |
| 화장품 표시·광고 관리 지침 | mfds_guideline | 27 | 399 | 섹션 헤더 기반. 표 추출이 부정확한 청크 1~2건 존재 |
| 건강기능식품 표시·광고 자율심의기구 운영규정 | association_pdf | 7 | 344 | 다단 컬럼 PDF — 법령 본문(식품표시광고법/시행령/시행규칙/건기식법)이 혼재. 후속 재라벨링 필요 |
| 온라인 부당광고 사례집 | ocr_required_stub | 1 | 190 | OCR STUB. 84p 이미지 PDF → 후속 OCR 처리 |

---

## 5. 검증 결과

### 5.1 자동 검증 (전체 127건)

| 항목 | 결과 |
|---|---|
| 각 행이 유효한 JSON | ✅ 127/127 |
| ID 중복 | ✅ 0건 |
| 필수 메타데이터 결측 | ✅ 0건 |
| 토큰 ≤ 500 (하드 캡) | ✅ 127/127 |
| 토큰 ≥ 300 (목표 하한) | ⚠️ 99/127 (78.0%) — 22%가 300 미만 |

### 5.2 300 미만 28건 분포 및 사유

| 유형 | 건수 | 사유 |
|---|---|---|
| 의도적 stub (lawgokr/ocr) | 4 | 본문 미수록·OCR 필요 안내문. 본문 보강 후 교체 예정 |
| 식약처 고시 호별 잔여 | 13 | 호(號) 경계를 의미 단위로 보존하기 위해 강제 병합하지 않음. 호 단위가 검색 의미 단위로 적합 |
| 가이드라인 섹션 잔여 | 9 | 섹션 헤더 직후 짧은 정의·서두. 같은 law 내 다중 패스 병합 후에도 인접 큰 청크와 만나지 못한 잔여 |
| 협회 PDF 짧은 조 | 2 | 운영규정 제1조(목적)/제3조(다른 법률과의 관계) 등 본질적으로 짧은 조문 |

**판단**: 토큰 하한(300)을 강제하면 (a) 호 경계 침범, (b) 의미상 짧은 조문 인위 결합이 발생한다.
RAG 검색에서는 청크 길이보다 **의미 경계 보존**이 우선이라 판단해 의미 단위가 짧으면 그대로 두었다.
500 토큰 상한은 100% 준수 — 임베딩·BM25 성능 측면에서 크리티컬한 부분만 보장했다.

### 5.3 샘플 20건 수동 검증

`sample_chunks_validation.md` 참조. 검증 결과 핵심 발견:
- **완전한 문장 유지**: 18/20 (90%)
- **의미 손상 없음**: 16/20 (80%)
- **잡음 청크**: 2건 (가이드라인 점검표 페이지, 화장품 지침 표 추출 부정확)

---

## 6. 알려진 한계와 후속 작업

### 6.1 법령 본문 미수록 (우선순위: 높음)

법령 3종(건강기능식품법/식품표시광고법/화장품법)의 law.go.kr HTML은 JavaScript 비동기 로드
방식이라 정적 HTML에 본문이 없다. 다음 방법으로 후속 보강 필요:

1. **elaw Open API 사용 (권장)**: https://open.law.go.kr 에서 OC 키 발급 후
   `DRF/lawService.do?OC=<key>&target=law&MST=<lsiSeq>&type=XML` 로 정형 본문 수신.
   - 인벤토리의 lsiSeq: 건기식법 259283, 식품표시광고법 257727, 화장품법 270323
2. **PDF 인쇄본 재수집**: 국가법령정보센터 일반 페이지의 "인쇄/PDF 다운" 버튼은
   Chrome headless로 자동화 가능. 본 단계에서 시도하지 않음.
3. **부분 보완**: 협회 PDF에 식품표시광고법 본문이 일부 포함되어 있어 현재도 검색 가능
   (운영규정 청크 7건 중 제2조·제3조 등이 식품표시광고법 본문).

### 6.2 사례집 OCR (우선순위: 높음)

`온라인_부당광고_사례집_식약처_2022_20260526.pdf` (84p, 21.8MB)는 전 페이지 이미지로
pdfplumber 텍스트 추출이 0자다. 다음 도구로 OCR 후 재청킹 필요:

- **Tesseract Korean (kor.traineddata)**: 보통 품질, 무료, 빠름
- **PaddleOCR**: 한글 인식 우수, 표 인식 가능
- **Naver Clova OCR / Upstage Document Parse**: 유료지만 한글 표·레이아웃 인식 최고 수준

OCR 후 결과는 `ocr_required_stub` 청크를 대체하여 약 80~120개 청크가 추가될 것으로 예상.

### 6.3 협회 PDF 재라벨링 (우선순위: 중간)

협회 PDF는 4단 컬럼 레이아웃에 식품표시광고법 + 시행령 + 시행규칙 + 건기식법 +
운영규정 본문이 가로로 섞여 있어 단순 정규식 추출 시 각 청크의 정확한 법령명 매핑이
어렵다. 현재 모든 청크를 `건강기능식품 표시·광고 자율심의기구 운영규정`으로 라벨링했으나,
실제로는 식품표시광고법 본문이 다수다.

후속 작업:
- pdfplumber `extract_tables()` + bbox 기반 컬럼 분리
- 또는 PDF를 Chrome headless로 다시 인쇄해 컬럼 분리된 텍스트 확보
- 그 후 각 청크의 law_name을 정확히 재라벨링

### 6.4 가이드라인 표지/점검표 노이즈 (우선순위: 낮음)

식약처 가이드라인 PDF의 첫 3~4페이지는 표지·등록 점검표·목차 등 본문성이 떨어진다.
현재 약 5~8개 청크가 이 영역에서 생성되어 검색 시 노이즈가 될 가능성이 있다.
RAG re-ranker가 이 부분을 자동으로 가중치 낮추므로 1차 단계에선 허용. 필요시 페이지 번호
기반 화이트리스트로 제외 가능.

### 6.5 PDF 표 추출 부정확 (우선순위: 낮음)

화장품 관리지침의 몇몇 표 영역은 한 행이 여러 줄로 쪼개져 추출된다. 예: 샘플 #10.
의미는 살아있어 검색 가능하지만 인용 시 가독성 저하. pdfplumber `extract_tables()`로
표 영역만 별도 처리하면 개선 가능.

---

## 7. 법령 개정 추적 메커니즘 제안

DATA-001 인벤토리에 명시된 "수집본 vs 최신본" 차이 추적을 위한 운영 절차 제안:

1. **수집 시점 기록**: 모든 청크 메타에 `last_revised` (인벤토리 기준), `source_file` (파일명에 수집일 포함) 보존
2. **버전 모니터링**:
   - elaw Open API에 `lsiSeq` 단위 폴링 (월 1회)
   - lsiSeq가 새로 발급되면 = 신규 개정 시행 → 자동 알림
3. **재청킹 트리거**:
   - 새 lsiSeq 감지 → 본 `chunk_regulations.py` 재실행
   - JSONL diff 비교 → 변경된 청크만 임베딩 재계산
4. **이력 보존**:
   - `regulations_chunks.jsonl` → `regulations_chunks_YYYYMMDD.jsonl` 스냅샷
   - 운영 인덱스는 latest, 평가용은 골든 평가 세트(EVAL-001) 작성 시점 스냅샷 사용

식약처 고시는 mfds.go.kr 게시판 RSS 또는 첨부파일 해시 모니터링.
- 제2025-79호 (2025-12-04 시행) 공개 즉시 재수집 필요 (인벤토리 인계 사항)

---

## 8. 다음 단계 인계 (P1-4 RAG-001 / P1-5 RAG-002)

- **RAG-001 (DB 스키마 최적화)**: 본 JSONL 스키마를 PostgreSQL 테이블로 변환 시
  컬럼 매핑은 그대로 1:1. `text` 컬럼은 pgvector 임베딩(`embedding` vector(n)),
  `tokens`는 통계용. `id`는 PK, `(law_name, article, subsection)` 복합 인덱스 권장.
- **RAG-002 (임베딩 파이프라인)**: cl100k_base 토큰 수 기준 500 이하 보장 → Upstage
  embedding-passage 모델(최대 4000 토큰)에 안전하게 입력 가능. 헤더 부분(`[법령명 ...]`)이
  임베딩에 노이즈가 되지 않도록 옵션 검토 (헤더 제외 임베딩 vs 헤더 포함 임베딩
  A/B 테스트 권장).
- **EVAL-001 (골든 평가 세트)**: TAXONOMY 9개 유형 × 청크 평균 14개 = 약 126개 청크
  대상으로 골든 쿼리·라벨 작성 시 본 청크 ID를 직접 인용 가능.

---

## 9. 재현 명령

```bash
# 필수 라이브러리
pip3 install --user pdfplumber tiktoken beautifulsoup4 lxml

# 청킹 실행
python3 _workspace/scripts/chunk_regulations.py
# 출력: _workspace/regulations_chunks.jsonl
```

