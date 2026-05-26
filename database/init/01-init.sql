-- Docker 컨테이너 최초 기동 시 1회 실행. 이후 스키마 변경은 Flyway가 담당.
-- pgvector 확장만 활성화한다. 테이블 생성은 Flyway V1__init_schema.sql이 담당.
CREATE EXTENSION IF NOT EXISTS vector;
