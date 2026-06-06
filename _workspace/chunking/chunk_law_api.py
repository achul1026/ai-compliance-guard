"""그룹 C 법령 본문 청킹 (국가법령정보 Open API).

사용:
    python chunk_law_api.py <MST> <law_short_name>
예:
    python chunk_law_api.py 257727 식품표시광고법
"""
import os
import sys
import re
import xml.etree.ElementTree as ET
from pathlib import Path
import requests
from dotenv import load_dotenv

from common import (
    Chunk,
    count_tokens,
    force_split_by_length,
    write_jsonl,
    MAX_TOKENS,
    MIN_TOKENS,
    SOFT_MAX_TOKENS,
)

load_dotenv(Path(__file__).parent / ".env")
OC = os.environ.get("LAW_OPEN_API_OC")
if not OC:
    print("ERROR: LAW_OPEN_API_OC 환경변수 미설정 (.env 확인)")
    sys.exit(1)

API_URL = "http://www.law.go.kr/DRF/lawService.do"
OUT_PATH = Path(__file__).parent.parent / "regulations_chunks.jsonl"

# MST → 메타데이터
LAW_META = {
    "257727": {
        "short": "식품표시광고법",
        "url": "https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=257727",
    },
    "259283": {
        "short": "건강기능식품법",
        "url": "https://www.law.go.kr/lsInfoP.do?lsiSeq=259283",
    },
    "270323": {
        "short": "화장품법",
        "url": "https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=270323",
    },
}


def fetch_law(mst: str) -> ET.Element:
    r = requests.get(API_URL, params={
        "OC": OC, "target": "law", "type": "XML", "MST": mst,
    }, timeout=60)
    r.raise_for_status()
    return ET.fromstring(r.text)


def get_text(elem, tag: str, default: str = "") -> str:
    node = elem.find(tag)
    if node is None or node.text is None:
        return default
    return node.text.strip()


def normalize(text: str) -> str:
    """XML 응답에 섞인 공백·개정표기 정리."""
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def yyyymmdd_to_iso(yyyymmdd: str) -> str:
    if len(yyyymmdd) == 8 and yyyymmdd.isdigit():
        return f"{yyyymmdd[:4]}-{yyyymmdd[4:6]}-{yyyymmdd[6:8]}"
    return yyyymmdd


def build_meta(root: ET.Element, short_name: str) -> dict:
    info = root.find("기본정보")
    law_name = get_text(info, "법령명_한글")
    enact = yyyymmdd_to_iso(get_text(info, "시행일자"))
    no = get_text(info, "공포번호")
    kind = get_text(info, "법종구분")
    return {
        "law_name": law_name,
        "version": f"{kind} 제{no}호" if no else kind,
        "enactment_date": enact,
        "source_url": LAW_META[next(k for k, v in LAW_META.items() if v["short"] == short_name)]["url"],
    }


def make_chunk(short_name: str, meta: dict, source_file: str,
               article_label: str, article_title, section, item, text: str,
               order: int) -> Chunk:
    id_parts = [short_name, article_label]
    if section:
        id_parts.append(section)
    if item:
        id_parts.append(f"호{item.rstrip('.')}")
    id_parts.append(f"{order:03d}")
    return Chunk(
        id="_".join(id_parts),
        law_name=meta["law_name"],
        law_short_name=short_name,
        article=article_label,
        article_title=article_title,
        section=section,
        subsection=None,
        item=item,
        text=text,
        source_file=source_file,
        source_url=meta["source_url"],
        enactment_date=meta["enactment_date"],
        version=meta["version"],
        violation_types=[],
        tokens=count_tokens(text),
        order=order,
    )


def chunk_law(root: ET.Element, short_name: str) -> list:
    meta = build_meta(root, short_name)
    source_file = f"law.go.kr_API_MST={[k for k, v in LAW_META.items() if v['short'] == short_name][0]}"
    chunks = []
    order = 0

    for art in root.findall("조문/조문단위"):
        is_article = get_text(art, "조문여부") == "조문"
        if not is_article:
            continue  # '전문' 등은 일단 스킵

        article_num = get_text(art, "조문번호")
        sub = get_text(art, "조문가지번호")  # 제8조의2 같은 경우
        article_label = f"제{article_num}조" + (f"의{sub}" if sub else "")
        title = get_text(art, "조문제목") or None

        # 조 전체 텍스트 조립 (조문내용 + 항/호)
        full_parts = [normalize(get_text(art, "조문내용"))]
        sections = art.findall("항")
        for s in sections:
            full_parts.append(normalize(get_text(s, "항내용")))
            for it in s.findall("호"):
                full_parts.append(normalize(get_text(it, "호내용")))
        full_text = "\n".join(p for p in full_parts if p)
        full_tokens = count_tokens(full_text)

        # Case 1: 조 전체가 임계치 이하 → 1 청크
        if full_tokens <= MAX_TOKENS:
            order += 1
            chunks.append(make_chunk(
                short_name, meta, source_file,
                article_label=article_label, article_title=title,
                section=None, item=None,
                text=full_text, order=order,
            ))
            continue

        # Case 2: 항 단위 분할
        if not sections:
            # 항이 없는데 조가 크다 → 길이 강제 분할
            for piece in force_split_by_length(full_text, MAX_TOKENS):
                order += 1
                chunks.append(make_chunk(
                    short_name, meta, source_file,
                    article_label=article_label, article_title=title,
                    section=None, item=None,
                    text=piece, order=order,
                ))
            continue

        # 조문내용(머리말)은 첫 항에 prepend
        head = normalize(get_text(art, "조문내용"))

        for idx, s in enumerate(sections):
            section_key = normalize(get_text(s, "항번호")) or None
            section_text = normalize(get_text(s, "항내용"))
            items = s.findall("호")

            # 항 본문 + 모든 호 텍스트 합쳐서 토큰 측정
            section_full_parts = [section_text] + [
                normalize(get_text(it, "호내용")) for it in items
            ]
            section_full = "\n".join(p for p in section_full_parts if p)
            if idx == 0 and head and head != f"{article_label}({title})" and title not in head:
                section_full = head + "\n" + section_full
            section_tokens = count_tokens(section_full)

            if section_tokens <= MAX_TOKENS:
                order += 1
                chunks.append(make_chunk(
                    short_name, meta, source_file,
                    article_label=article_label, article_title=title,
                    section=section_key, item=None,
                    text=section_full, order=order,
                ))
                continue

            # 호 단위 분할
            if not items:
                for piece in force_split_by_length(section_full, MAX_TOKENS):
                    order += 1
                    chunks.append(make_chunk(
                        short_name, meta, source_file,
                        article_label=article_label, article_title=title,
                        section=section_key, item=None,
                        text=piece, order=order,
                    ))
                continue

            # 항 머리말(호 없는 부분)은 별도 청크
            if section_text and count_tokens(section_text) >= MIN_TOKENS:
                order += 1
                chunks.append(make_chunk(
                    short_name, meta, source_file,
                    article_label=article_label, article_title=title,
                    section=section_key, item=None,
                    text=section_text, order=order,
                ))

            for it in items:
                item_key = normalize(get_text(it, "호번호")) or None
                item_text = normalize(get_text(it, "호내용"))
                item_tokens = count_tokens(item_text)

                if item_tokens <= SOFT_MAX_TOKENS:
                    order += 1
                    chunks.append(make_chunk(
                        short_name, meta, source_file,
                        article_label=article_label, article_title=title,
                        section=section_key, item=item_key,
                        text=item_text, order=order,
                    ))
                else:
                    for piece in force_split_by_length(item_text, MAX_TOKENS):
                        order += 1
                        chunks.append(make_chunk(
                            short_name, meta, source_file,
                            article_label=article_label, article_title=title,
                            section=section_key, item=item_key,
                            text=piece, order=order,
                        ))

    return chunks


def main():
    if len(sys.argv) != 3:
        print("Usage: python chunk_law_api.py <MST> <law_short_name>")
        print(f"Options: {[(k, v['short']) for k, v in LAW_META.items()]}")
        sys.exit(1)

    mst = sys.argv[1]
    short_name = sys.argv[2]

    print(f"[1/3] API 호출: MST={mst}, OC={OC}")
    root = fetch_law(mst)
    info_name = get_text(root.find("기본정보"), "법령명_한글")
    print(f"      수신 완료: {info_name}")

    print(f"[2/3] 청킹: {short_name}")
    chunks = chunk_law(root, short_name)
    print(f"      총 {len(chunks)}개 청크")

    print(f"[3/3] JSONL append: {OUT_PATH}")
    write_jsonl(chunks, OUT_PATH, mode="a")

    if chunks:
        tokens = [c.tokens for c in chunks]
        print(
            f"\n토큰 분포: min={min(tokens)}, "
            f"max={max(tokens)}, avg={sum(tokens) // len(tokens)}"
        )
        over = [c.id for c in chunks if c.tokens > SOFT_MAX_TOKENS]
        if over:
            print(f"WARN SOFT_MAX({SOFT_MAX_TOKENS}) 초과 {len(over)}개: {over[:3]}")


if __name__ == "__main__":
    main()
