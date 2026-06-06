#!/usr/bin/env python3
"""
골든 세트의 정답 조항이 DB(regulations)에 적재되어 있는지 진단.
EVAL-002 보고서의 '미달 원인 분석'을 데이터로 뒷받침한다.
"""
from __future__ import annotations
import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

PSQL_BASE = [
    "docker", "exec", "compliance-postgres",
    "psql", "-U", "postgres", "-d", "compliance_db",
    "-X", "-A", "-t", "-F", "\t",
]

JOSU_RE = re.compile(r"제\s*(\d+)\s*조")
HANG_RE = re.compile(r"제?\s*(\d+)\s*항")
HO_RE = re.compile(r"제?\s*(\d+)\s*호")
MOK_RE = re.compile(r"([가나다라마바사아자차카타파하])\s*목")


def normalize_law(name: str) -> str:
    if not name:
        return ""
    s = name.strip().replace("·", "ㆍ")
    return s


def query_db(sql: str) -> list[list[str]]:
    proc = subprocess.run(
        PSQL_BASE + ["-c", sql],
        capture_output=True, text=True, timeout=20,
    )
    rows = []
    for line in proc.stdout.strip().splitlines():
        if not line.strip():
            continue
        rows.append(line.split("\t"))
    return rows


def find_exact_chunk(law: str, article: str) -> int:
    """정답 조항이 DB에 존재하는 청크 수."""
    nlaw = normalize_law(law).replace("'", "''")
    jo = JOSU_RE.search(article or "")
    ho = HO_RE.search(article or "")
    mok = MOK_RE.search(article or "")

    where = [f"law_name = '{nlaw}'"]
    if jo:
        where.append(f"article_number = '제{jo.group(1)}조'")
    if ho:
        where.append(f"item_number LIKE '%{ho.group(1)}호%' OR item_number LIKE '제{ho.group(1)}호%'")
    if mok:
        where.append(f"item_number LIKE '%{mok.group(1)}목%'")
    where_sql = " AND ".join(where)
    sql = f"SELECT COUNT(*) FROM regulations WHERE {where_sql};"
    rows = query_db(sql)
    return int(rows[0][0]) if rows else 0


def find_law_chunks(law: str) -> int:
    nlaw = normalize_law(law).replace("'", "''")
    sql = f"SELECT COUNT(*) FROM regulations WHERE law_name = '{nlaw}';"
    rows = query_db(sql)
    return int(rows[0][0]) if rows else 0


def find_law_substantive_chunks(law: str) -> int:
    """본문이 있는 청크 (article_number != 'N/A')."""
    nlaw = normalize_law(law).replace("'", "''")
    sql = (
        f"SELECT COUNT(*) FROM regulations "
        f"WHERE law_name = '{nlaw}' AND article_number <> 'N/A';"
    )
    rows = query_db(sql)
    return int(rows[0][0]) if rows else 0


def main():
    gpath = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("../golden_eval_set.jsonl")
    samples = [json.loads(l) for l in gpath.read_text(encoding="utf-8").splitlines() if l.strip()]

    # 정답 조항 unique 목록
    answers = []
    for s in samples:
        for c in s.get("correct_articles", []):
            answers.append((c["law"], c["article"]))
    unique_answers = sorted(set(answers))

    print("=" * 80)
    print("골든 세트 정답 조항 DB 적재 진단")
    print("=" * 80)
    print(f"\n전체 unique 정답 (law, article) 조합: {len(unique_answers)}")

    # 법령별 적재 현황
    print("\n[법령별 DB 적재 현황]")
    laws = sorted(set(a[0] for a in unique_answers))
    law_stats = []
    for law in laws:
        total = find_law_chunks(law)
        sub = find_law_substantive_chunks(law)
        law_stats.append((law, total, sub))
        print(f"  - {law}: total={total}, 본문청크={sub}")

    # 정답 조항별 매칭
    print("\n[정답 조항별 DB 매칭 수]")
    missing = []
    present = []
    for law, art in unique_answers:
        n = find_exact_chunk(law, art)
        status = "OK" if n > 0 else "MISSING"
        print(f"  [{status}] {law} | {art} → DB 청크 {n}건")
        if n == 0:
            missing.append((law, art))
        else:
            present.append((law, art, n))

    print(f"\n=== 요약 ===")
    print(f"전체 정답 조항: {len(unique_answers)}")
    print(f"적재됨        : {len(present)}")
    print(f"미적재        : {len(missing)} ({len(missing)/len(unique_answers)*100:.1f}%)")

    print(f"\n[미적재 목록]")
    for law, art in missing:
        print(f"  - {law} | {art}")


if __name__ == "__main__":
    main()
