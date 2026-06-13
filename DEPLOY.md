# 배포 가이드

> ai-compliance-guard 운영 배포. 보안 점검은 [`_workspace/PROD_SECURITY_CHECKLIST.md`](_workspace/PROD_SECURITY_CHECKLIST.md) 참고.

## 구성

| 서비스 | 역할 | 컨테이너 |
|---|---|---|
| `app` | Spring Boot 애플리케이션 (REST + 정적 UI) | `Dockerfile` |
| `postgres` | ParadeDB(PostgreSQL + pgvector + BM25) | `paradedb/paradedb` |
| `embedding-server` | BGE-m3 임베딩 추론 (선택) | `embedding-server/` |

`app`은 `profiles: [app]`로 분리되어 있어, 로컬 개발(앱은 IDE/Gradle 실행) 시에는 뜨지 않고 DB만 띄울 수 있다.

## 필수 환경변수 (운영)

`application-prod.yml`은 시크릿 미주입 시 **기동 실패(fail-fast)**한다.

```bash
JWT_SECRET=$(openssl rand -base64 32)      # 32바이트 이상
APP_AES_KEY=$(openssl rand -base64 32)     # base64 32바이트. 분실 = 이력 복호화 불가 → 백업 필수
GEMINI_API_KEY=...                          # https://aistudio.google.com/apikey
DB_PASSWORD=...                             # 운영 DB 비밀번호
```

`.env`에 채우거나 배포 환경의 시크릿 매니저로 주입한다. `.env`는 `.gitignore` 보호 대상.

## 로컬에서 운영 이미지 빌드·기동

```bash
# 전체 스택(app + postgres) 기동
docker compose --profile app up -d --build

# DB만 (앱은 Gradle로 실행)
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

빌드만 검증:
```bash
docker build -t compliance-app .
```

## 배포 전 체크리스트 (요약)

- [ ] HTTPS 종단(리버스 프록시) + `X-Forwarded-Proto` 신뢰 — Secure 쿠키·HSTS 전제
- [ ] 시크릿 4종 주입(`JWT_SECRET`, `APP_AES_KEY`, `GEMINI_API_KEY`, `DB_PASSWORD`)
- [ ] `APP_AES_KEY` 백업 (분실 시 분석 이력 영구 복호화 불가)
- [ ] Flyway 마이그레이션 자동 적용 확인 (`ddl-auto=validate`)
- [ ] ADMIN 계정: `UPDATE users SET role='ADMIN' WHERE email='...'` (signup은 USER 고정)
- [ ] 무료 티어 동안 실고객 데이터 금지 고지 노출(`legal.html`) 확인

상세는 [`_workspace/PROD_SECURITY_CHECKLIST.md`](_workspace/PROD_SECURITY_CHECKLIST.md).

## 클라우드 배포 (타겟 미정)

현재 클라우드 타겟은 미확정. 컨테이너 이미지가 준비됐으므로 다음 중 선택 가능:
- 단일 VM + docker compose (가장 단순, 초기 권장)
- 관리형 컨테이너(Cloud Run, ECS 등) + 관리형 PostgreSQL(pgvector 지원 필요 — ParadeDB BM25는 자체 호스팅 필요할 수 있음)

> 주의: ParadeDB의 `pg_search`(BM25)는 관리형 PostgreSQL에서 미지원일 수 있다. 매니지드 DB 채택 시 BM25 대체(벡터 단독 또는 별도 검색) 검토 필요.
