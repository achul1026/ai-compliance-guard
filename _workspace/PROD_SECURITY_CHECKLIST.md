# 운영 배포 보안 체크리스트 (P3-5)

> 정식 런칭 전 반드시 점검. ADR-008(인증)·ADR-009(암호화) 기반.

## 1. 필수 시크릿 주입 (미주입 시 기동 실패 = fail-fast)

`prod` 프로파일은 다음을 **기본값 없이** 환경변수로 요구한다. 하나라도 빠지면 앱이 기동하지 않는다.

| 환경변수 | 용도 | 비고 |
|---|---|---|
| `JWT_SECRET` | JWT 서명 | 32바이트 이상. 무작위 생성, 재사용 금지 |
| `APP_AES_KEY` | 분석 이력 AES-256 | **base64 32바이트. 분실 = 복호화 영구 불가 → 시크릿 매니저 + 백업** |
| `GEMINI_API_KEY` | LLM | 유료 전환 시 갱신 |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | DB 접속 | |

생성 예:
```bash
openssl rand -base64 32   # JWT_SECRET
openssl rand -base64 32   # APP_AES_KEY (base64 32바이트)
```

## 2. 적용된 보안 설정 (코드)

- [x] 비밀번호 BCrypt 해시 (평문·단순해시 없음)
- [x] JWT HttpOnly 쿠키 + `JWT_COOKIE_SECURE=true`(prod 강제) → HTTPS 전용
- [x] Refresh 토큰 DB 화이트리스트 + 회전(서버측 무효화)
- [x] 분석 이력 AES-256-GCM 암호화 (DB 유출 시 평문 노출 0)
- [x] 보안 헤더: `X-Content-Type-Options=nosniff`, `X-Frame-Options=SAMEORIGIN`, HSTS(1년)
- [x] `/admin/**` ADMIN role 보호
- [x] 로그에 광고 카피 원문 미기록 (`copyLength`만), PII 마스킹 유틸

## 3. 배포 전 점검

- [ ] **HTTPS 필수** — Secure 쿠키·HSTS가 전제. 리버스 프록시(Nginx 등)에서 TLS 종단, `X-Forwarded-Proto` 신뢰 설정.
- [ ] **CORS** — 동일 출처 서빙이면 불필요. 프론트 분리 시 허용 출처 명시(현재 정적 UI는 같은 서버).
- [ ] **앱인토스 입점 시**: API 서버 CORS 허용 목록에 앱인토스 도메인 추가, 서버간 통신 mTLS (입점 가이드 참조).
- [ ] **ADMIN 계정**: `signup`은 USER 고정 → 운영 ADMIN은 DB에서 `UPDATE users SET role='ADMIN' WHERE email=...` 수동 부여.
- [ ] **Flyway**: `ddl-auto=validate`(prod) — 스키마 변경은 마이그레이션으로만.
- [ ] **무료 티어 정책**: `LLM_DATA_COLLECTION_OPT_OUT=false`(무료) 동안 실고객 데이터 금지 고지 노출 확인(legal.html). 유료 전환 시 `true` + 유료 모델.

## 4. 잔여 리스크 (문서화된 한계)

- 무료 Gemini 전송분은 학습될 수 있음 → 저장 암호화로 못 막음. 정책(실고객 금지) + 유료 전환으로만 종결. (ADR-009 §트레이드오프)
- AES 키 분실 = 이력 복호화 불가. 키 백업이 유일한 방어.
- Access 토큰(15분)은 stateless라 탈취 시 최대 15분 유효(Refresh는 즉시 무효화 가능).
