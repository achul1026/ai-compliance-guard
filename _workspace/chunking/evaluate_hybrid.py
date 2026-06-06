"""Hybrid search 평가 (BM25 + BGE-m3 Dense Vector + RRF).

dense-only / bm25-only / hybrid 3가지 모드의 Recall@K·MRR을 동시 측정.
원본 청크(`text` 필드)와 원본 임베딩(non-enriched) 사용.
"""
import json
import re
import sys
from pathlib import Path
import numpy as np
from rank_bm25 import BM25Okapi
from FlagEmbedding import BGEM3FlagModel

WORKSPACE = Path(__file__).parent.parent
JSONL_PATH = WORKSPACE / "regulations_chunks.jsonl"
EMB_PATH = WORKSPACE / "regulations_embeddings.npy"

# 골든 세트: expected는 list (여러 정답 허용)
GOLDEN = [
    {
        "query": "이 상품은 당뇨병을 예방합니다",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호1",      # 본법
            "부당표시광고_내용기준_제2조_호4",   # 식약처 고시 (질병 예방·치료)
        ],
        "violation": "질병 예방·치료 효능 표방",
    },
    {
        "query": "의사가 추천하는 영양제, 약처럼 효과적인",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호2",
            "부당표시광고_내용기준_제2조_호1",  # 의약품 오인
        ],
        "violation": "의약품 오인",
    },
    {
        "query": "건강기능식품 같은 효과를 주는 일반 식품",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호3",
            "부당표시광고_내용기준_제2조_호2",  # 건기식 오인
        ],
        "violation": "건강기능식품 오인",
    },
    {
        "query": "100% 천연 유기농, 부작용 전혀 없음",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호4",
            "부당표시광고_내용기준_제2조_호3",  # 거짓·과장
        ],
        "violation": "거짓·과장 광고",
    },
    {
        "query": "한 달에 10kg 감량 보장, 모든 사람에게 효과",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호5",
        ],
        "violation": "소비자 기만",
    },
    {
        "query": "타사 제품보다 우수하며 경쟁사는 가짜다",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호6",
            "부당표시광고_내용기준_제2조_호4",  # 비방
        ],
        "violation": "타 업체·제품 비방",
    },
    {
        "query": "근거 없이 우리 제품이 제일 좋다고 비교",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호7",
        ],
        "violation": "부당 비교",
    },
    {
        "query": "사행심을 부추기는 광고, 음란한 표현",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호8",
        ],
        "violation": "사행심·음란",
    },
    {
        "query": "기존 유명 상표와 비슷한 디자인의 식품 용기",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호9",
        ],
        "violation": "타 물품 오인 (상호·상표)",
    },
    {
        "query": "심의를 받지 않고 광고했음",
        "expected_prefixes": [
            "식품표시광고법_제8조_①_호10",
            "식품표시광고법_제10조",  # 자율심의 조항
        ],
        "violation": "미심의 광고",
    },
    {
        "query": "화장품인데 의약품처럼 치료 효과를 주장",
        "expected_prefixes": [
            "화장품법_제13조",
        ],
        "violation": "화장품 의약품 오인",
    },
    {
        "query": "건강기능식품의 기능성을 광고할 때 자율심의",
        "expected_prefixes": [
            "식품표시광고법_제10조",
            "협회_자율심의_운영규정",
        ],
        "violation": "표시·광고의 자율심의",
    },
]


def tokenize_ko(text: str) -> list:
    """공백 + 구두점 단순 토크나이저. 형태소 분석기 없이 한국어 BM25 동작."""
    text = re.sub(r"[^\w\s가-힣]", " ", text)
    return [t for t in text.split() if len(t) >= 2]


def rrf_merge(rankings: list, k: int = 60) -> list:
    """Reciprocal Rank Fusion. rankings: [[idx, ...], [idx, ...]] 형식 (각 검색 결과)."""
    scores = {}
    for rank_list in rankings:
        for rank_idx, idx in enumerate(rank_list, 1):
            scores[idx] = scores.get(idx, 0.0) + 1.0 / (k + rank_idx)
    return sorted(scores.keys(), key=lambda i: -scores[i])


def is_correct(chunk_id: str, expected_prefixes: list) -> bool:
    return any(chunk_id.startswith(p) for p in expected_prefixes)


def evaluate(name: str, results_per_query: list, golden: list, ks=(1, 3, 5, 10)) -> dict:
    recall = {k: 0 for k in ks}
    mrr_sum = 0.0
    n = len(golden)
    for g, ranked_ids in zip(golden, results_per_query):
        rank = None
        for r, cid in enumerate(ranked_ids, 1):
            if is_correct(cid, g["expected_prefixes"]):
                rank = r
                break
        for k in ks:
            if rank and rank <= k:
                recall[k] += 1
        if rank:
            mrr_sum += 1 / rank
    return {"name": name, "recall": {k: f"{v/n*100:.1f}% ({v}/{n})" for k, v in recall.items()},
            "mrr": f"{mrr_sum/n:.4f}"}


def main():
    print("[1/5] 데이터 로드")
    with open(JSONL_PATH, "r", encoding="utf-8") as f:
        chunks = [json.loads(l) for l in f]
    emb = np.load(EMB_PATH)
    print(f"      청크 {len(chunks)}, 임베딩 shape {emb.shape}")

    print("[2/5] BM25 인덱스 구축")
    tokenized_corpus = [tokenize_ko(c["text"]) for c in chunks]
    bm25 = BM25Okapi(tokenized_corpus)

    print("[3/5] BGE-m3 쿼리 임베딩")
    model = BGEM3FlagModel("BAAI/bge-m3", use_fp16=False)
    queries = [g["query"] for g in GOLDEN]
    q_result = model.encode(queries, batch_size=4, max_length=512,
                            return_dense=True, return_sparse=False, return_colbert_vecs=False)
    q_vecs = q_result["dense_vecs"].astype(np.float32)

    print("[4/5] 검색 실행")
    top_n = 50  # RRF 결합용 충분히 큰 N
    dense_results, bm25_results, hybrid_results = [], [], []

    for gi, g in enumerate(GOLDEN):
        # Dense
        sims = emb @ q_vecs[gi]
        dense_top = np.argsort(-sims)[:top_n].tolist()

        # BM25
        bm25_scores = bm25.get_scores(tokenize_ko(g["query"]))
        bm25_top = np.argsort(-bm25_scores)[:top_n].tolist()

        # RRF
        rrf_top = rrf_merge([dense_top, bm25_top], k=60)[:top_n]

        dense_results.append([chunks[i]["id"] for i in dense_top])
        bm25_results.append([chunks[i]["id"] for i in bm25_top])
        hybrid_results.append([chunks[i]["id"] for i in rrf_top])

    print("[5/5] 평가\n")

    # 상세 출력
    for gi, g in enumerate(GOLDEN):
        d_rank = b_rank = h_rank = None
        for r, cid in enumerate(dense_results[gi], 1):
            if is_correct(cid, g["expected_prefixes"]):
                d_rank = r; break
        for r, cid in enumerate(bm25_results[gi], 1):
            if is_correct(cid, g["expected_prefixes"]):
                b_rank = r; break
        for r, cid in enumerate(hybrid_results[gi], 1):
            if is_correct(cid, g["expected_prefixes"]):
                h_rank = r; break
        d_mark = f"#{d_rank}" if d_rank else "MISS"
        b_mark = f"#{b_rank}" if b_rank else "MISS"
        h_mark = f"#{h_rank}" if h_rank else "MISS"
        print(f"Q{gi+1:02d} dense={d_mark:>5}  bm25={b_mark:>5}  hybrid={h_mark:>5}  | {g['violation']}")

    print("\n" + "=" * 70)
    print("=== 모드별 성능 ===\n")
    for r in [
        evaluate("Dense (BGE-m3 only)", dense_results, GOLDEN),
        evaluate("BM25 only", bm25_results, GOLDEN),
        evaluate("Hybrid (RRF)", hybrid_results, GOLDEN),
    ]:
        print(f"{r['name']}")
        for k, v in r["recall"].items():
            print(f"  Recall@{k:>2}: {v}")
        print(f"  MRR     : {r['mrr']}")
        print()


if __name__ == "__main__":
    main()
