"""BGE-m3 임베딩 검색 정확도 평가 (PoC).

골든 세트: 식품표시광고법 제8조 9유형 + 기타 자주 발생하는 위반 케이스.
각 쿼리에 대해 cosine similarity로 top-K 검색 후 정답 청크가 포함되는지 평가.
"""
import json
from pathlib import Path
import numpy as np
from FlagEmbedding import BGEM3FlagModel

import sys
WORKSPACE = Path(__file__).parent.parent
JSONL_PATH = Path(sys.argv[1]) if len(sys.argv) > 1 else WORKSPACE / "regulations_chunks.jsonl"
EMB_PATH = Path(sys.argv[2]) if len(sys.argv) > 2 else WORKSPACE / "regulations_embeddings.npy"
INDEX_PATH = EMB_PATH.with_suffix(".index.json") if len(sys.argv) > 2 else WORKSPACE / "regulations_embeddings_index.json"

# 골든 평가 세트
# expected_id_prefix: 정답 청크 ID의 prefix (정확 ID가 아닌 패턴 매칭 허용)
GOLDEN = [
    {
        "query": "이 상품은 당뇨병을 예방합니다",
        "expected_id_prefix": "식품표시광고법_제8조_①_호1",
        "violation": "질병 예방·치료 효능 표방",
    },
    {
        "query": "의사가 추천하는 영양제, 약처럼 효과적인",
        "expected_id_prefix": "식품표시광고법_제8조_①_호2",
        "violation": "의약품 오인",
    },
    {
        "query": "건강기능식품 같은 효과를 주는 일반 식품",
        "expected_id_prefix": "식품표시광고법_제8조_①_호3",
        "violation": "건강기능식품 오인",
    },
    {
        "query": "100% 천연 유기농, 부작용 전혀 없음",
        "expected_id_prefix": "식품표시광고법_제8조_①_호4",
        "violation": "거짓·과장 광고",
    },
    {
        "query": "한 달에 10kg 감량 보장, 모든 사람에게 효과",
        "expected_id_prefix": "식품표시광고법_제8조_①_호5",
        "violation": "소비자 기만",
    },
    {
        "query": "타사 제품보다 우수하며 경쟁사는 가짜다",
        "expected_id_prefix": "식품표시광고법_제8조_①_호6",
        "violation": "타 업체·제품 비방",
    },
    {
        "query": "근거 없이 우리 제품이 제일 좋다고 비교",
        "expected_id_prefix": "식품표시광고법_제8조_①_호7",
        "violation": "부당 비교",
    },
    {
        "query": "사행심을 부추기는 광고, 음란한 표현",
        "expected_id_prefix": "식품표시광고법_제8조_①_호8",
        "violation": "사행심·음란",
    },
    {
        "query": "기존 유명 상표와 비슷한 디자인의 식품 용기",
        "expected_id_prefix": "식품표시광고법_제8조_①_호9",
        "violation": "타 물품 오인 (상호·상표)",
    },
    {
        "query": "심의를 받지 않고 광고했음",
        "expected_id_prefix": "식품표시광고법_제8조_①_호10",
        "violation": "미심의 광고",
    },
    # 화장품법 관련
    {
        "query": "화장품인데 의약품처럼 치료 효과를 주장",
        "expected_id_prefix": "화장품법_제13조",
        "violation": "화장품 의약품 오인",
    },
    # 건강기능식품법
    {
        "query": "건강기능식품의 기능성을 광고할 때 자율심의",
        "expected_id_prefix": "식품표시광고법_제10조",
        "violation": "표시·광고의 자율심의",
    },
]


def cosine_topk(query_vec: np.ndarray, all_vecs: np.ndarray, k: int = 10) -> list:
    """query_vec과 all_vecs의 cosine similarity → top-K 인덱스·점수."""
    # 둘 다 normalize 되어 있다면 dot product = cosine similarity
    sims = all_vecs @ query_vec
    top_idx = np.argsort(-sims)[:k]
    return [(int(i), float(sims[i])) for i in top_idx]


def main():
    print("[1/3] 데이터 로드")
    with open(JSONL_PATH, "r", encoding="utf-8") as f:
        chunks = [json.loads(l) for l in f]
    emb = np.load(EMB_PATH)
    with open(INDEX_PATH, "r", encoding="utf-8") as f:
        index = json.load(f)
    assert index["ids"] == [c["id"] for c in chunks]
    print(f"      청크 {len(chunks)}, 임베딩 shape {emb.shape}")

    print("[2/3] BGE-m3 모델 로드 (캐시 사용)")
    model = BGEM3FlagModel("BAAI/bge-m3", use_fp16=False)

    print(f"[3/3] 골든 세트 {len(GOLDEN)}건 평가\n")

    recall_at = {1: 0, 3: 0, 5: 0, 10: 0}
    mrr_sum = 0.0
    details = []

    queries = [g["query"] for g in GOLDEN]
    q_result = model.encode(queries, batch_size=4, max_length=512,
                            return_dense=True, return_sparse=False, return_colbert_vecs=False)
    q_vecs = q_result["dense_vecs"].astype(np.float32)

    for gi, g in enumerate(GOLDEN):
        topk = cosine_topk(q_vecs[gi], emb, k=10)

        # 정답 위치 찾기
        rank = None
        for rank_idx, (chunk_idx, score) in enumerate(topk, 1):
            cid = chunks[chunk_idx]["id"]
            if cid.startswith(g["expected_id_prefix"]):
                rank = rank_idx
                break

        for k in recall_at:
            if rank is not None and rank <= k:
                recall_at[k] += 1
        if rank is not None:
            mrr_sum += 1 / rank

        # 출력
        status = f"rank={rank}" if rank else "MISS"
        print(f'Q{gi+1:02d} [{status}] "{g["query"][:35]}..." → {g["violation"]}')
        print(f"     expected_prefix: {g['expected_id_prefix']}")
        for rank_idx, (chunk_idx, score) in enumerate(topk[:3], 1):
            c = chunks[chunk_idx]
            mark = "★" if c["id"].startswith(g["expected_id_prefix"]) else " "
            print(f'     {mark} #{rank_idx} ({score:.3f}) {c["id"]} :: {c["text"][:60]}')
        print()

        details.append({
            "query": g["query"],
            "expected_prefix": g["expected_id_prefix"],
            "rank": rank,
            "top10": [{"id": chunks[ci]["id"], "score": s} for ci, s in topk],
        })

    n = len(GOLDEN)
    print("=" * 60)
    print("=== 평가 결과 ===")
    for k, v in recall_at.items():
        print(f"  Recall@{k:>2}: {v}/{n} = {v/n*100:.1f}%")
    print(f"  MRR     : {mrr_sum/n:.4f}")

    # 저장
    eval_out = WORKSPACE / "evaluation_result.json"
    with open(eval_out, "w", encoding="utf-8") as f:
        json.dump({
            "summary": {
                "n": n,
                "recall": {f"@{k}": v for k, v in recall_at.items()},
                "mrr": mrr_sum / n,
            },
            "details": details,
        }, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {eval_out}")


if __name__ == "__main__":
    main()
