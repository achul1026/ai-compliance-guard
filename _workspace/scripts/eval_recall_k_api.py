#!/usr/bin/env python3
"""Step 5-12: 자바 검색 API로 Hybrid Recall@K 평가.

- 골든셋 25건을 POST /api/v1/search 로 호출.
- 정답 매칭 규칙:
    (a) law_strict + article_strict: law_name 부분일치 AND article_number 핵심 부분 일치
    (b) law_only: law_name 부분일치만 (진단용)
- Recall@1, @5, @10을 두 규칙으로 동시 측정.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path
from urllib import request, error

API_URL = "http://localhost:8080/api/v1/search"


def normalize_law(name: str) -> str:
    return re.sub(r"\s+", "", (name or "").replace("ㆍ", "·").replace("ᆞ", "·"))


def extract_article_core(article: str) -> str:
    """'제8조 제1항 1호' → '제8조' / '제2조' → '제2조' / 'STUB' → ''"""
    if not article:
        return ""
    m = re.search(r"제\s*(\d+)\s*조", article)
    if m:
        return f"제{m.group(1)}조"
    if article.isdigit():
        return f"제{article}조"
    return ""


def match_strict(law_g: str, art_g: str, law_r: str, art_r: str) -> bool:
    lg, lr = normalize_law(law_g), normalize_law(law_r)
    if not lg or not lr:
        return False
    if lg not in lr and lr not in lg:
        return False
    ag = extract_article_core(art_g)
    ar = extract_article_core(art_r)
    if not ag:
        return True
    return ag == ar


def match_law_only(law_g: str, law_r: str) -> bool:
    lg, lr = normalize_law(law_g), normalize_law(law_r)
    if not lg or not lr:
        return False
    return lg in lr or lr in lg


def call_search(query: str, top_k: int = 10) -> dict:
    body = json.dumps({"query": query, "top_k": top_k}).encode()
    req = request.Request(API_URL, data=body, headers={"Content-Type": "application/json"})
    with request.urlopen(req, timeout=90) as resp:
        return json.loads(resp.read().decode())


def evaluate(golden_path: Path, out_path: Path) -> None:
    samples = [json.loads(line) for line in golden_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    results = []
    sums = {f"{rule}@{k}": 0 for rule in ("strict", "law_only") for k in (1, 5, 10)}
    n = len(samples)

    for i, s in enumerate(samples, 1):
        query = s["violation_text"]
        t0 = time.time()
        try:
            resp = call_search(query, 10)
            hits = resp.get("results", [])
            elapsed_ms = int((time.time() - t0) * 1000)
        except Exception as e:
            print(f"[{i}/{n}] {s['sample_id']} 호출 실패: {e}", file=sys.stderr)
            results.append({"sample_id": s["sample_id"], "error": str(e)})
            continue

        per_sample = {
            "sample_id": s["sample_id"],
            "query": query,
            "elapsed_ms": elapsed_ms,
            "correct_articles": s["correct_articles"],
            "hits_top10": [{"law": h.get("law"), "article": h.get("article"), "score": h.get("relevance_score")} for h in hits[:10]],
        }
        for rule in ("strict", "law_only"):
            for k in (1, 5, 10):
                found = False
                for h in hits[:k]:
                    for c in s["correct_articles"]:
                        if rule == "strict":
                            if match_strict(c["law"], c["article"], h.get("law", ""), h.get("article", "")):
                                found = True
                                break
                        else:
                            if match_law_only(c["law"], h.get("law", "")):
                                found = True
                                break
                    if found:
                        break
                per_sample[f"hit_{rule}@{k}"] = found
                if found:
                    sums[f"{rule}@{k}"] += 1
        results.append(per_sample)
        print(f"[{i:2d}/{n}] {s['sample_id']:11s} strict@10={per_sample['hit_strict@10']} law_only@10={per_sample['hit_law_only@10']} ({elapsed_ms}ms)")

    summary = {k: round(v / n, 3) for k, v in sums.items()}
    out_path.write_text(json.dumps({"summary": summary, "samples": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n=== 요약 ===")
    for k, v in summary.items():
        print(f"  {k}: {v:.1%}")
    print(f"\n결과 저장: {out_path}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--golden", type=Path, default=Path(__file__).resolve().parent.parent / "golden_eval_set.jsonl")
    p.add_argument("--out", type=Path, default=Path(__file__).resolve().parent.parent / "eval_recall_phase1_final.json")
    args = p.parse_args()
    evaluate(args.golden, args.out)
