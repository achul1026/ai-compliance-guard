"""BGE-m3 임베딩 추론 서버 (ADR-005).

Spring Boot의 BgeM3EmbeddingAdapter가 호출하는 HTTP 엔드포인트 제공.
모델 로딩은 startup 시 1회 (cold start ~50초). 이후 요청은 즉시 처리.

엔드포인트:
  GET  /health         헬스 체크 (모델 로드 여부)
  POST /embed          텍스트 → 임베딩 변환
  GET  /info           모델·차원 메타데이터
"""
import logging
import os
import time
from contextlib import asynccontextmanager
from typing import List, Optional

import numpy as np
from fastapi import FastAPI, HTTPException
from FlagEmbedding import BGEM3FlagModel
from pydantic import BaseModel, Field

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("embedding-server")

MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "BAAI/bge-m3")
MAX_LENGTH = int(os.environ.get("EMBEDDING_MAX_LENGTH", "1024"))
USE_FP16 = os.environ.get("EMBEDDING_USE_FP16", "false").lower() == "true"
EMBEDDING_DIM = 1024

# 전역 모델 핸들 (lifespan에서 초기화)
_model: Optional[BGEM3FlagModel] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model
    logger.info("Loading model %s (use_fp16=%s, max_length=%d)", MODEL_NAME, USE_FP16, MAX_LENGTH)
    t0 = time.time()
    _model = BGEM3FlagModel(MODEL_NAME, use_fp16=USE_FP16)
    logger.info("Model loaded in %.1fs", time.time() - t0)
    yield
    logger.info("Shutting down")


app = FastAPI(
    title="AI Compliance Guard - Embedding Server",
    description="BGE-m3 추론 마이크로서비스 (ADR-005)",
    version="1.0.0",
    lifespan=lifespan,
)


class EmbedRequest(BaseModel):
    texts: List[str] = Field(..., min_length=1, max_length=128, description="임베딩할 텍스트 목록")
    normalize: bool = Field(default=True, description="L2 정규화 적용 여부")


class EmbedResponse(BaseModel):
    embeddings: List[List[float]]
    dim: int
    model: str
    count: int
    elapsed_ms: int


class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    model: str


class InfoResponse(BaseModel):
    model: str
    dim: int
    max_length: int
    use_fp16: bool


@app.get("/health", response_model=HealthResponse)
def health():
    return HealthResponse(
        status="ok" if _model is not None else "loading",
        model_loaded=_model is not None,
        model=MODEL_NAME,
    )


@app.get("/info", response_model=InfoResponse)
def info():
    return InfoResponse(
        model=MODEL_NAME,
        dim=EMBEDDING_DIM,
        max_length=MAX_LENGTH,
        use_fp16=USE_FP16,
    )


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if _model is None:
        raise HTTPException(status_code=503, detail="Model is still loading")

    # 빈 텍스트 사전 필터링
    cleaned = [t.strip() for t in req.texts]
    if any(not t for t in cleaned):
        raise HTTPException(status_code=400, detail="Empty text not allowed")

    t0 = time.time()
    result = _model.encode(
        cleaned,
        batch_size=min(len(cleaned), 16),
        max_length=MAX_LENGTH,
        return_dense=True,
        return_sparse=False,
        return_colbert_vecs=False,
    )
    dense = result["dense_vecs"].astype(np.float32)

    if req.normalize:
        # FlagEmbedding은 기본적으로 normalize 되어 있지만 명시적으로 한 번 더
        norms = np.linalg.norm(dense, axis=1, keepdims=True)
        norms = np.where(norms == 0, 1, norms)
        dense = dense / norms

    elapsed_ms = int((time.time() - t0) * 1000)
    return EmbedResponse(
        embeddings=dense.tolist(),
        dim=dense.shape[1],
        model=MODEL_NAME,
        count=len(cleaned),
        elapsed_ms=elapsed_ms,
    )
