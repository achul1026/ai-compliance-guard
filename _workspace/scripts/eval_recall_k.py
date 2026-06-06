#!/usr/bin/env python3
"""
EVAL-002: Recall@K 측정 스크립트

골든 평가 세트(25개)의 각 샘플을 검색하여 Recall@5/Recall@10을 측정한다.

전제 조건:
- PostgreSQL(ParadeDB)이 localhost:5432에서 실행 중
- regulations 테이블에 chunk 데이터 적재됨
- ParadeDB BM25 인덱스(idx_regulations_bm25_chunk) 구성됨

검색 방식 (Plan B):
- 운영 환경 한계로 임베딩이 0건 적재됨 → 벡터 검색 / Re-ranker 사용 불가
- 본 평가는 BM25 단독 검색으로 수행하며, 그 결과를 보고서에 명시한다.
- 하이브리드(BM25 + Vector + Reranker) 전체 평가는 임베딩 적재 후 별도 실행.

정답 매칭 규칙:
1. 완전 매칭(strict): law_name + article_number 동시 일치
2. 부분 매칭(loose): law_name만 일치 (데이터 미적재 진단용)

사용법:
    PGPASSWORD=postgres python3 eval_recall_k.py \
        --golden ../golden_eval_set.jsonl \
        --out ../eval_recall_k_results.json
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from collections import defaultdict
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Iterable

# ---------------------------------------------------------------------------
# 1. 정답 키 정규화
# ---------------------------------------------------------------------------

# 법령명 정규화 매핑 (골든 세트 표기 ↔ DB 표기 차이 흡수)
LAW_ALIASES = {
    # 식품표시광고법: 골든은 'ㆍ' DB는 'ㆍ' 동일하지만 안전망
    "식품 등의 표시·광고에 관한 법률": "식품 등의 표시ㆍ광고에 관한 법률",
    "식품 등의 표시ㆍ광고에 관한 법률": "식품 등의 표시ㆍ광고에 관한 법률",
}


def normalize_law(name: str) -> str:
    if not name:
        return ""
    s = name.strip()
    s = s.replace("·", "ㆍ")
    return LAW_ALIASES.get(s, s)


# 조항 번호 정규화: '제8조 제1항 1호' → '제8조|제1항|제1호'
JOSU_RE = re.compile(r"제\s*(\d+)\s*조")
HANG_RE = re.compile(r"제?\s*(\d+)\s*항")
HO_RE = re.compile(r"제?\s*(\d+)\s*호")
MOK_RE = re.compile(r"([가나다라마바사아자차카타파하])\s*목")


def parse_article(article: str) -> tuple[str | None, str | None, str | None, str | None]:
    """
    골든의 article 표기를 (조, 항, 호, 목)으로 분해.
    예: '제8조 제1항 1호' → ('제8조', '제1항', '제1호', None)
        '제2조 1호 가목'   → ('제2조', None, '제1호', '가목')
        '제2조 3호 차목'  → ('제2조', None, '제3호', '차목')
        '제18조'           → ('제18조', None, None, None)
        '전체'             → (None, None, None, None)  -- 가이드라인 전체 매칭은 law 일치로 판정
    """
    if not article or article.strip() == "전체":
        return (None, None, None, None)
    s = article.strip()
    jo = JOSU_RE.search(s)
    jo_s = f"제{jo.group(1)}조" if jo else None
    hang = HANG_RE.search(s.replace(jo.group(0), "") if jo else s)
    hang_s = f"제{hang.group(1)}항" if hang else None
    # 호: 끝 뒤 잘림 방지 — '1호' 등 검출
    ho = HO_RE.search(s)
    ho_s = f"제{ho.group(1)}호" if ho else None
    mok = MOK_RE.search(s)
    mok_s = f"{mok.group(1)}목" if mok else None
    return (jo_s, hang_s, ho_s, mok_s)


def db_article_key(article_number: str | None, paragraph_number: str | None,
                   item_number: str | None) -> tuple[str | None, str | None, str | None, str | None]:
    """DB 행의 (article_number, paragraph_number, item_number)를 동일한 키 튜플로."""
    def norm(x):
        return x.strip() if x and x.strip() and x.strip() != "N/A" else None
    art = norm(article_number)
    par = norm(paragraph_number)
    item = norm(item_number)
    # item이 '가목'처럼 목 형태이면 mok로
    mok = None
    ho = None
    if item:
        m = MOK_RE.search(item)
        if m:
            mok = f"{m.group(1)}목"
        h = HO_RE.search(item)
        if h:
            ho = f"제{h.group(1)}호"
    return (art, par, ho, mok)


# ---------------------------------------------------------------------------
# 2. PostgreSQL BM25 쿼리 (docker exec psql)
# ---------------------------------------------------------------------------

PSQL_BASE = [
    "docker", "exec", "compliance-postgres",
    "psql", "-U", "postgres", "-d", "compliance_db",
    "-X", "-A", "-t",  # extended off, unaligned, tuples only
    "-F", "\t",        # tab 구분
]


def bm25_search(query: str, top_k: int) -> list[dict]:
    """ParadeDB BM25로 상위 top_k 후보를 반환."""
    # 쿼리 내 작은따옴표 이스케이프
    safe_query = query.replace("'", "''")
    sql = (
        "SELECT r.id, r.law_name, r.article_number, "
        "COALESCE(r.paragraph_number, ''), COALESCE(r.item_number, ''), "
        "paradedb.score(r.id) AS bm25 "
        f"FROM regulations r WHERE r.chunk_text @@@ '{safe_query}' "
        f"ORDER BY bm25 DESC LIMIT {top_k};"
    )
    proc = subprocess.run(
        PSQL_BASE + ["-c", sql],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        sys.stderr.write(f"[psql ERR] {proc.stderr}\n")
        return []
    rows = []
    for line in proc.stdout.strip().splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 6:
            continue
        rows.append({
            "id": int(parts[0]),
            "law_name": parts[1],
            "article_number": parts[2],
            "paragraph_number": parts[3],
            "item_number": parts[4],
            "score": float(parts[5]),
        })
    return rows


# ---------------------------------------------------------------------------
# 3. 매칭 판정
# ---------------------------------------------------------------------------

def match_strict(correct_law: str, correct_article: str,
                 result_row: dict) -> bool:
    """완전 매칭: 법령명 + (조,항,호,목) 모두 일치(존재하는 만큼)."""
    if normalize_law(correct_law) != normalize_law(result_row["law_name"]):
        return False
    c_jo, c_hang, c_ho, c_mok = parse_article(correct_article)
    r_jo, r_hang, r_ho, r_mok = db_article_key(
        result_row["article_number"],
        result_row["paragraph_number"],
        result_row["item_number"],
    )
    # 골든이 '전체'(=None)인 경우 law 일치만으로 매칭 인정
    if c_jo is None and c_hang is None and c_ho is None and c_mok is None:
        return True
    # 조항이 명시되어 있다면 모두 일치해야 함 (None인 항/호/목은 비교 생략)
    if c_jo and c_jo != r_jo:
        return False
    if c_hang and c_hang != r_hang:
        return False
    if c_ho and c_ho != r_ho:
        return False
    if c_mok and c_mok != r_mok:
        return False
    return True


def match_loose(correct_law: str, result_row: dict) -> bool:
    """부분 매칭: 법령명만 일치 (데이터 미적재 진단용)."""
    return normalize_law(correct_law) == normalize_law(result_row["law_name"])


# ---------------------------------------------------------------------------
# 4. 평가 메인 루프
# ---------------------------------------------------------------------------

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
    matched_articles_in_top10_strict: list[str] = field(default_factory=list)
    matched_articles_in_top10_loose: list[str] = field(default_factory=list)
    elapsed_ms: int = 0


def evaluate_sample(sample: dict) -> SampleEval:
    sid = sample["sample_id"]
    query = sample["violation_text"]
    start = time.time()
    top10 = bm25_search(query, top_k=10)
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

    # 각 정답 조항에 대해 top10 / top5 안에 있는지 판정
    correct = sample.get("correct_articles", [])
    matched_strict = []
    matched_loose = []
    for c in correct:
        law = c["law"]
        art = c["article"]
        label = f"{law} {art}"
        # strict
        if any(match_strict(law, art, r) for r in top10):
            matched_strict.append(label)
        # loose
        if any(match_loose(law, r) for r in top10):
            matched_loose.append(label)

    se.matched_articles_in_top10_strict = matched_strict
    se.matched_articles_in_top10_loose = matched_loose

    # hit@K: 최소 1개 정답 조항이 top-K 안에 포함되면 hit
    top5 = top10[:5]
    se.hit_at_5_strict = any(
        match_strict(c["law"], c["article"], r)
        for c in correct for r in top5
    )
    se.hit_at_10_strict = len(matched_strict) > 0
    se.hit_at_5_loose = any(
        match_loose(c["law"], r) for c in correct for r in top5
    )
    se.hit_at_10_loose = len(matched_loose) > 0

    return se


def load_golden(path: Path) -> list[dict]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        out.append(json.loads(line))
    return out


def aggregate(results: list[SampleEval]) -> dict:
    n = len(results)
    if n == 0:
        return {}
    overall = {
        "recall_at_5_strict": sum(r.hit_at_5_strict for r in results) / n,
        "recall_at_10_strict": sum(r.hit_at_10_strict for r in results) / n,
        "recall_at_5_loose": sum(r.hit_at_5_loose for r in results) / n,
        "recall_at_10_loose": sum(r.hit_at_10_loose for r in results) / n,
        "n_samples": n,
    }
    # 위반 유형별
    by_type: dict[str, list[SampleEval]] = defaultdict(list)
    for r in results:
        by_type[r.violation_type].append(r)
    by_type_stats = {}
    for k, v in by_type.items():
        nn = len(v)
        by_type_stats[k] = {
            "n": nn,
            "recall_at_5_strict": sum(x.hit_at_5_strict for x in v) / nn,
            "recall_at_10_strict": sum(x.hit_at_10_strict for x in v) / nn,
            "recall_at_5_loose": sum(x.hit_at_5_loose for x in v) / nn,
            "recall_at_10_loose": sum(x.hit_at_10_loose for x in v) / nn,
        }
    # 난이도별
    by_diff: dict[str, list[SampleEval]] = defaultdict(list)
    for r in results:
        by_diff[r.difficulty].append(r)
    by_diff_stats = {}
    for k, v in by_diff.items():
        nn = len(v)
        by_diff_stats[k] = {
            "n": nn,
            "recall_at_5_strict": sum(x.hit_at_5_strict for x in v) / nn,
            "recall_at_10_strict": sum(x.hit_at_10_strict for x in v) / nn,
            "recall_at_5_loose": sum(x.hit_at_5_loose for x in v) / nn,
            "recall_at_10_loose": sum(x.hit_at_10_loose for x in v) / nn,
        }
    return {
        "overall": overall,
        "by_violation_type": by_type_stats,
        "by_difficulty": by_diff_stats,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--golden", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    samples = load_golden(args.golden)
    sys.stderr.write(f"[INFO] {len(samples)} samples loaded\n")

    results = []
    for s in samples:
        se = evaluate_sample(s)
        sys.stderr.write(
            f"[{se.sample_id}] hit@5(strict)={int(se.hit_at_5_strict)} "
            f"hit@10(strict)={int(se.hit_at_10_strict)} "
            f"hit@5(loose)={int(se.hit_at_5_loose)} "
            f"hit@10(loose)={int(se.hit_at_10_loose)} "
            f"top10={len(se.top10)} {se.elapsed_ms}ms\n"
        )
        results.append(se)

    agg = aggregate(results)

    out = {
        "search_mode": "BM25_ONLY (ParadeDB pg_search) — 임베딩 미적재로 벡터/리랭커 미사용",
        "n_samples": len(results),
        "aggregate": agg,
        "samples": [asdict(r) for r in results],
    }
    args.out.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    sys.stderr.write(f"[INFO] wrote {args.out}\n")

    # 콘솔 요약
    print("\n=== 종합 결과 (BM25 단독, top10 / top5) ===")
    print(f"Recall@5  strict  = {agg['overall']['recall_at_5_strict']*100:.1f}%")
    print(f"Recall@10 strict  = {agg['overall']['recall_at_10_strict']*100:.1f}%")
    print(f"Recall@5  loose   = {agg['overall']['recall_at_5_loose']*100:.1f}%")
    print(f"Recall@10 loose   = {agg['overall']['recall_at_10_loose']*100:.1f}%")


if __name__ == "__main__":
    main()
