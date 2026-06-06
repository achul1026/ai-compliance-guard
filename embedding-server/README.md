# Embedding Server (BGE-m3)

ADR-005에 따라 BGE-m3를 별도 컨테이너로 분리한 임베딩 추론 마이크로서비스.

Spring Boot 백엔드의 `BgeM3EmbeddingAdapter`가 HTTP로 호출한다.

## 엔드포인트

| Method | Path | 용도 |
|---|---|---|
| GET | `/health` | 헬스 체크 (모델 로드 완료 여부) |
| GET | `/info` | 모델 메타데이터 (차원, max_length 등) |
| POST | `/embed` | 텍스트 임베딩 |

### POST /embed 요청/응답

요청:
```json
{
  "texts": ["식품표시광고법 제8조에 따라...", "다른 텍스트"],
  "normalize": true
}
```

응답:
```json
{
  "embeddings": [[0.012, -0.034, ...], [...]],
  "dim": 1024,
  "model": "BAAI/bge-m3",
  "count": 2,
  "elapsed_ms": 412
}
```

## 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `EMBEDDING_MODEL` | `BAAI/bge-m3` | HuggingFace 모델 ID |
| `EMBEDDING_MAX_LENGTH` | `1024` | 최대 토큰 길이 |
| `EMBEDDING_USE_FP16` | `false` | GPU 환경에서 true 권장 |

## 로컬 실행 (Docker 없이)

```bash
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

## Docker 실행

```bash
docker build -t compliance-embedding-server .
docker run -p 8001:8000 -v hf_cache:/root/.cache/huggingface compliance-embedding-server
```

## 메모리·성능 노트

- 모델 로딩: 첫 시작 시 ~50초 (CPU), 모델 다운로드 포함 시 +2~3분
- 메모리: 최소 4GB 권장 (모델 ~2.5GB + 추론 버퍼)
- CPU 추론: 약 2~3 chunks/sec (배치 16 기준)
- GPU 가속 시 `EMBEDDING_USE_FP16=true` 권장
