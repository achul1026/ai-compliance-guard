#!/usr/bin/env python3
"""
EVAL-003: Hybrid Search (Vector + BM25 + Re-ranker) 평가

Spring Boot 하이브리드 검색 API를 호출하여 성능을 측정한다.

전제 조건:
- Spring Boot 앱이 localhost:8080에서 실행 중
- /api/v1/search 엔드포인트 사용 가능
- 임베딩 파이프라인 완료 (1,369개 청크 임베딩됨)
"""

import argparse
import json
import re
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional
import requests

# 법령명 정규화 매핑
LAW_ALIASES = {
    "식품 등의 표시·광고에 관한 법률": "식품 등의 표시ㆍ광고에 관한 법률",
    "식품 등의 표시ㆍ광고에 관한 법률": "식품 등의 표시ㆍ광고에 관한 법률",
}

JOSU_RE = re.compile(r"제\s*(\d+)\s*조")
HANG_RE = re.compile(r"제?\s*(\d+)\s*항")
HO_RE = re.compile(r"제?\s*(\d+)\s*호")
MOK_RE = re.compile(r"([가나다라마바사아자차카타파하])\s*목")


def normalize_law(name: str) -> str:
    if not name:
        return ""
    s = name.strip()
    s = s.replace("·", "ㆍ")
    return LAW_ALIASES.get(s, s)


def parse_article(article: str) -> tuple[Optional[str], Optional[str], Optional[str], Optional[str]]:
    """조항 표기를 (조, 항, 호, 목)으로 분해."""
    if not article or article.strip() == "전체":
        return (None, None, None, None)
    s = article.strip()
    jo = JOSU_RE.search(s)
    jo_s = f"제{jo.group(1)}조" if jo else None
    hang = HANG_RE.search(s.replace(jo.group(0), "") if jo else s)
    hang_s = f"제{hang.group(1)}항" if hang else None
    ho = HO_RE.search(s)
    ho_s = f"제{ho.group(1)}호" if ho else None
    mok = MOK_RE.search(s)
    mok_s = f"{mok.group(1)}목" if mok else None
    return (jo_s, hang_s, ho_s, mok_s)


def db_article_key(article: Optional[str]) -> tuple[Optional[str], Optional[str], Optional[str], Optional[str]]:
    """API 응답의 article을 (조, 항, 호, 목) 튜플로 변환."""
    if not article or article == "N/A":
        return (None, None, None, None)

    s = article.strip()
    jo = JOSU_RE.search(s)
    jo_s = f"제{jo.group(1)}조" if jo else None
    hang = HANG_RE.search(s)
    hang_s = f"제{hang.group(1)}항" if hang else None
    ho = HO_RE.search(s)
    ho_s = f"제{ho.group(1)}호" if ho else None
    mok = MOK_RE.search(s)
    mok_s = f"{mok.group(1)}목" if mok else None
    return (jo_s, hang_s, ho_s, mok_s)


def match_strict(correct_law: str, correct_article: str, result: dict) -> bool:
    """완전 매칭: 법령명 + 조항 일치."""
    if normalize_law(correct_law) != normalize_law(result["law"]):
        return False

    c_jo, c_hang, c_ho, c_mok = parse_article(correct_article)
    r_jo, r_hang, r_ho, r_mok = db_article_key(result["article"])

    # 전체인 경우 law 일치만으로 인정
    if c_jo is None and c_hang is None and c_ho is None and c_mok is None:
        return True

    if c_jo and c_jo != r_jo:
        return False
    if c_hang and c_hang != r_hang:
        return False
    if c_ho and c_ho != r_ho:
        return False
    if c_mok and c_mok != r_mok:
        return False
    return True


def match_loose(correct_law: str, result: dict) -> bool:
    """부분 매칭: 법령명만 일치."""
    return normalize_law(correct_law) == normalize_law(result["law"])


def hybrid_search(query: str, top_k: int = 10) -> list[dict]:
    """Spring Boot 하이브리드 검색 API 호출."""
    try:
        response = requests.post(
            "http://localhost:8080/api/v1/search",
            json={"query": query, "limit": top_k},
            timeout=30
        )
        response.raise_for_status()
        data = response.json()
        results = data.get("results", [])

        # API 응답 형식: {"law": "...", "article": "...", "text": "...", "relevance_score": ...}
        # 평가 형식으로 변환
        return [
            {
                "law": r.get("law"),
                "article": r.get("article"),
                "text": r.get("text"),
                "score": r.get("relevance_score", 0),
            }
            for r in results
        ]
    except Exception as e:
        sys.stderr.write(f"[API ERR] {e}\n")
        return []


@dataclass
class SampleEval:
    sample_id: str
    violation_type: str
    violation_text: str
    difficulty: str
    correct_articles: list[dict]
    top10: list[dict] = field(default_factory=list)
    hit_at_5_strict: bool = False
    hit_at_10_strict: bool = False
    hit_at_5_loose: bool = False
    hit_at_10_loose: bool = False
    elapsed_ms: int = 0


def evaluate_sample(sample: dict) -> SampleEval:
    """샘플 평가."""
    sid = sample["sample_id"]
    query = sample["violation_text"]
    start = time.time()
    top10 = hybrid_search(query, top_k=10)
    elapsed_ms = int((time.time() - start) * 1000)

    se = SampleEval(
        sample_id=sid,
        violation_type=sample.get("violation_type", ""),
        violation_text=query,
        difficulty=sample.get("difficulty", ""),
        correct_articles=sample.get("correct_articles", []),
        top10=top10,
        elapsed_ms=elapsed_ms,
    )

    # 정답 매칭
    correct = sample.get("correct_articles", [])
    for c in correct:
        law = c["law"]
        art = c["article"]

        # strict
        if any(match_strict(law, art, r) for r in top10[:5]):
            se.hit_at_5_strict = True
        if any(match_strict(law, art, r) for r in top10):
            se.hit_at_10_strict = True

        # loose
        if any(match_loose(law, r) for r in top10[:5]):
            se.hit_at_5_loose = True
        if any(match_loose(law, r) for r in top10):
            se.hit_at_10_loose = True

    return se


def main():
    parser = argparse.ArgumentParser(description="Hybrid Search 평가")
    parser.add_argument("--golden", required=True, help="Golden eval set JSONL")
    parser.add_argument("--out", required=True, help="Output JSON")
    args = parser.parse_args()

    golden_path = Path(args.golden)
    if not golden_path.exists():
        print(f"❌ 파일 없음: {golden_path}")
        sys.exit(1)

    # 골든 세트 로드
    samples = []
    with open(golden_path) as f:
        for line in f:
            if line.strip():
                samples.append(json.loads(line))

    print(f"[INFO] {len(samples)} samples loaded")

    # 평가
    results = []
    for sample in samples:
        result = evaluate_sample(sample)
        results.append(result)

        hit_str = (
            f"hit@5(strict)={int(result.hit_at_5_strict)} "
            f"hit@10(strict)={int(result.hit_at_10_strict)} "
            f"hit@5(loose)={int(result.hit_at_5_loose)} "
            f"hit@10(loose)={int(result.hit_at_10_loose)} "
            f"top10={len(result.top10)} {result.elapsed_ms}ms"
        )
        print(f"[{result.sample_id}] {hit_str}")

    # 결과 저장
    output_data = [asdict(r) for r in results]
    with open(args.out, "w") as f:
        json.dump(output_data, f, ensure_ascii=False, indent=2)

    print(f"[INFO] wrote {args.out}")

    # 통계
    recall_5_strict = sum(1 for r in results if r.hit_at_5_strict) / len(results) * 100
    recall_10_strict = sum(1 for r in results if r.hit_at_10_strict) / len(results) * 100
    recall_5_loose = sum(1 for r in results if r.hit_at_5_loose) / len(results) * 100
    recall_10_loose = sum(1 for r in results if r.hit_at_10_loose) / len(results) * 100

    print(f"\n=== 하이브리드 검색 결과 (Vector + BM25 + Reranker) ===")
    print(f"Recall@5  strict  = {recall_5_strict:.1f}%")
    print(f"Recall@10 strict  = {recall_10_strict:.1f}%")
    print(f"Recall@5  loose   = {recall_5_loose:.1f}%")
    print(f"Recall@10 loose   = {recall_10_loose:.1f}%")


if __name__ == "__main__":
    main()
