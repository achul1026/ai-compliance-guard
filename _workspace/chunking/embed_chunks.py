"""BGE-m3로 청크 임베딩 생성 (오프라인 PoC).

입력:  _workspace/regulations_chunks.jsonl
출력:
  _workspace/regulations_embeddings.npy  (shape: [N, 1024], float32)
  _workspace/regulations_embeddings_index.json  (id ↔ row index 매핑)

사용:
  python embed_chunks.py [batch_size]
"""
import json
import sys
import time
from pathlib import Path
import numpy as np
from FlagEmbedding import BGEM3FlagModel

WORKSPACE = Path(__file__).parent.parent
DEFAULT_JSONL = WORKSPACE / "regulations_chunks.jsonl"
DEFAULT_EMB = WORKSPACE / "regulations_embeddings.npy"
DEFAULT_INDEX = WORKSPACE / "regulations_embeddings_index.json"

MODEL_NAME = "BAAI/bge-m3"


def load_chunks(path: Path) -> list:
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            rows.append(json.loads(line))
    return rows


def main(batch_size: int = 8, src: Path = DEFAULT_JSONL,
         out_emb: Path = DEFAULT_EMB, out_index: Path = DEFAULT_INDEX,
         text_field: str = "text"):
    print(f"[1/4] 청크 로드: {src} (field={text_field})")
    rows = load_chunks(src)
    print(f"      {len(rows)}개")

    print(f"[2/4] BGE-m3 모델 로드")
    t0 = time.time()
    model = BGEM3FlagModel(MODEL_NAME, use_fp16=False)
    print(f"      로드 완료 ({time.time() - t0:.1f}초)")

    texts = [r.get(text_field, r["text"]) for r in rows]

    print(f"[3/4] 임베딩 생성 (batch_size={batch_size}, CPU)")
    t0 = time.time()
    result = model.encode(
        texts,
        batch_size=batch_size,
        max_length=1024,
        return_dense=True,
        return_sparse=False,
        return_colbert_vecs=False,
    )
    dense = result["dense_vecs"]
    elapsed = time.time() - t0
    print(f"      완료 ({elapsed:.1f}초, {len(texts) / elapsed:.1f} chunks/sec)")
    print(f"      shape: {dense.shape}, dtype: {dense.dtype}")

    # float32 통일
    dense = dense.astype(np.float32)

    print(f"[4/4] 저장")
    np.save(out_emb, dense)
    print(f"      {out_emb} ({out_emb.stat().st_size / 1024 / 1024:.2f} MB)")

    index = {"ids": [r["id"] for r in rows], "model": MODEL_NAME,
             "dim": int(dense.shape[1]), "text_field": text_field}
    with open(out_index, "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
    print(f"      {out_index}")

    print("\n=== 완료 ===")
    print(f"청크 수: {len(rows)}")
    print(f"임베딩 차원: {dense.shape[1]}")
    print(f"평균 norm: {np.linalg.norm(dense, axis=1).mean():.4f}")


if __name__ == "__main__":
    # Usage: python embed_chunks.py [batch_size] [src.jsonl] [out.npy] [text_field]
    bs = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    src = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_JSONL
    out_emb = Path(sys.argv[3]) if len(sys.argv) > 3 else DEFAULT_EMB
    text_field = sys.argv[4] if len(sys.argv) > 4 else "text"
    out_index = out_emb.with_suffix(".index.json")
    main(bs, src, out_emb, out_index, text_field)
