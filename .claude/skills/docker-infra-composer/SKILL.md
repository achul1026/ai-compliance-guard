---
name: Docker Infra Composer
description: Docker Compose로 PostgreSQL + pgvector 로컬 개발 환경을 구축한다. 데이터베이스 마이그레이션 프레임워크(Flyway) 기초 설정, 초기 스키마 설계. "Docker Compose 설정해줘", "PostgreSQL 셋업", "pgvector 확장", "DB 마이그레이션 구조" 요청에서 반드시 사용할 것.
type: general-purpose
model: opus
---

## 작업 흐름

### 1단계: docker-compose.yml 작성
```yaml
version: '3.8'

services:
  postgres:
    image: ankane/pgvector:latest
    environment:
      POSTGRES_DB: compliance_db
      POSTGRES_USER: ${DB_USER:-postgres}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-postgres}
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./database/init:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
```

### 2단계: 초기화 스크립트
**database/init/01-init.sql**:
```sql
-- 기본 확장 활성화
CREATE EXTENSION IF NOT EXISTS vector;

-- 규정 청크 테이블 생성 (Phase 1 대비)
CREATE TABLE IF NOT EXISTS regulations (
  id SERIAL PRIMARY KEY,
  source VARCHAR(255) NOT NULL,
  law_name VARCHAR(255) NOT NULL,
  article_number VARCHAR(50) NOT NULL,
  chunk_text TEXT NOT NULL,
  embedding vector(1536),
  metadata JSONB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 인덱스 (Phase 1 대비)
CREATE INDEX idx_law_name ON regulations(law_name);
CREATE INDEX idx_article ON regulations(article_number);
-- pgvector HNSW 인덱스 (Phase 1에서 임베딩 후 추가)
-- CREATE INDEX ON regulations USING hnsw (embedding vector_cosine_ops);

-- 사용자/분석 이력 테이블 (Phase 3 대비)
CREATE TABLE IF NOT EXISTS audit_analyses (
  id SERIAL PRIMARY KEY,
  user_id VARCHAR(100),
  input_text TEXT NOT NULL,
  analysis_result JSONB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 3단계: Flyway 마이그레이션 프레임워크 설정
**build.gradle.kts** (`:infra` 모듈):
```kotlin
plugins {
  id("org.flywaydb.flyway") version "9.22.3"
}

dependencies {
  implementation("org.flywaydb:flyway-core:9.22.3")
  implementation("org.flywaydb:flyway-database-postgresql:9.22.3")
}

flyway {
  url = "jdbc:postgresql://localhost:5432/compliance_db"
  user = "postgres"
  password = "postgres"
  locations = arrayOf("filesystem:database/migrations")
}
```

### 4단계: 마이그레이션 파일 구조
```
database/
├── migrations/
│   ├── V1__init_schema.sql (테이블, 확장 초기화)
│   ├── V2__regulations_index.sql (인덱스)
│   ├── V3__audit_table.sql (분석 이력)
│   └── ...
└── init/
    └── 01-init.sql (Docker 초기화용)
```

**V1__init_schema.sql**:
```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE regulations (
  id SERIAL PRIMARY KEY,
  source VARCHAR(255) NOT NULL,
  law_name VARCHAR(255) NOT NULL,
  article_number VARCHAR(50) NOT NULL,
  chunk_text TEXT NOT NULL,
  embedding vector(1536) NULL,
  metadata JSONB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_law_name ON regulations(law_name);
CREATE INDEX idx_article ON regulations(article_number);
```

### 5단계: Spring Boot와 통합
**application-local.yml**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/compliance_db
    username: postgres
    password: postgres
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
```

### 6단계: 환경변수 관리
**.env.example**:
```
DB_USER=postgres
DB_PASSWORD=postgres
DB_NAME=compliance_db
```

## 실행 가이드

```bash
# 1. 환경변수 설정
cp .env.example .env

# 2. Docker 컨테이너 실행
docker compose up -d

# 3. DB 접속 확인
psql -h localhost -U postgres -d compliance_db -c "SELECT * FROM pg_extension WHERE extname='vector';"

# 4. Flyway 마이그레이션 실행
./gradlew flywayMigrate

# 5. Spring Boot 기동 시 자동 마이그레이션
./gradlew :api:bootRun
```

## 검증 체크리스트

- [ ] `docker compose up` 성공
- [ ] PostgreSQL 컨테이너 healthy 상태
- [ ] pgvector 확장 설치 확인: `SELECT * FROM pg_extension WHERE extname='vector';`
- [ ] 초기 테이블 생성됨: `\dt regulations;`
- [ ] Flyway 마이그레이션 이력 테이블: `SELECT * FROM flyway_schema_history;`
- [ ] Spring Boot 연동 확인
- [ ] 포트 바인딩: 5432

## 데이터 보안

- `.env` 파일은 `.gitignore`에 포함
- 프로덕션 환경에서는 AWS RDS, GCP Cloud SQL 등 관리형 서비스 사용
- 마이그레이션 파일은 버전 관리

## Phase 1 확장 계획

- 규정 데이터 청크 로드 스크립트 추가
- 벡터 임베딩 배치 작업
- 성능 인덱스 최적화 (pgvector HNSW)
