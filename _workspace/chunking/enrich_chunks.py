"""청크에 부모 컨텍스트 prepend 적용 → 시맨틱 임베딩 품질 개선용 신규 필드 생성.

입력: _workspace/regulations_chunks.jsonl
출력: _workspace/regulations_chunks_enriched.jsonl

원본 `text`는 보존하고 `text_for_embedding`을 추가한다.
임베딩은 enriched 본문으로 생성, 검색 결과 표시는 원본 text 사용.
"""
import json
from pathlib import Path

WORKSPACE = Path(__file__).parent.parent
SRC = WORKSPACE / "regulations_chunks.jsonl"
DST = WORKSPACE / "regulations_chunks_enriched.jsonl"


def build_prefix(chunk: dict) -> str:
    """부모 컨텍스트 머리말 생성. 비어 있으면 None 반환."""
    parts = [chunk["law_short_name"]]

    art = chunk["article"]
    title = chunk["article_title"]
    if art:
        if title:
            parts.append(f"{art}({title})")
        else:
            parts.append(art)

    sec = chunk["section"]
    if sec:
        parts.append(f"{sec}항")

    return " ".join(parts)


def needs_enrichment(chunk: dict) -> bool:
    """짧거나, 호 단위로 분할된 청크에만 prepend 적용."""
    if chunk["tokens"] < 150:
        return True
    if chunk["item"]:  # 호 단위
        return True
    return False


def is_prefix_redundant(text: str, prefix: str) -> bool:
    """prefix 핵심 토큰이 이미 text 앞부분에 있으면 중복 prepend 회피."""
    head = text[:80]
    # 법령명·조번호가 이미 본문에 있으면 skip
    for token in prefix.split():
        if token not in head:
            return False
    return True


def main():
    with open(SRC, "r", encoding="utf-8") as f:
        rows = [json.loads(l) for l in f]

    enriched_count = 0
    out_rows = []
    for r in rows:
        text = r["text"]
        if needs_enrichment(r):
            prefix = build_prefix(r)
            if prefix and not is_prefix_redundant(text, prefix):
                text_for_embed = f"{prefix}.\n{text}"
                enriched_count += 1
            else:
                text_for_embed = text
        else:
            text_for_embed = text
        new_r = dict(r)
        new_r["text_for_embedding"] = text_for_embed
        out_rows.append(new_r)

    with open(DST, "w", encoding="utf-8") as f:
        for r in out_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"입력: {len(rows)}개")
    print(f"prepend 적용: {enriched_count}개 ({enriched_count/len(rows)*100:.1f}%)")
    print(f"출력: {DST}")

    # 샘플 확인
    print("\n=== 적용 예시 ===")
    samples_shown = 0
    for r in out_rows:
        if r["text"] != r["text_for_embedding"] and samples_shown < 3:
            print(f"\n[{r['id']}]")
            print(f"  원본    : {r['text'][:80]}")
            print(f"  embed용 : {r['text_for_embedding'][:120]}")
            samples_shown += 1


if __name__ == "__main__":
    main()
