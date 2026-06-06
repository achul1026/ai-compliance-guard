"""공통 청킹 유틸리티: 토큰 카운트, 조·항·호 파싱, JSONL writer."""
import json
import re
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import Optional
import tiktoken

ENCODER = tiktoken.get_encoding("cl100k_base")

MIN_TOKENS = 100
MAX_TOKENS = 500
SOFT_MAX_TOKENS = 700

RE_ARTICLE = re.compile(
    r"^제(\d+)조(?:의(\d+))?(?:\s*\(([^)]+)\))?",
    re.MULTILINE,
)
RE_SECTION_CIRCLED = re.compile(r"^[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳]")
RE_SECTION_PAREN = re.compile(r"^\(\s*(\d+)\s*\)")
RE_ITEM = re.compile(r"^\s*(\d+)\.\s")

SECTION_MAP = {c: i + 1 for i, c in enumerate("①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳")}


@dataclass
class Chunk:
    id: str
    law_name: str
    law_short_name: str
    article: str
    article_title: Optional[str]
    section: Optional[str]
    subsection: Optional[str]
    item: Optional[str]
    text: str
    source_file: str
    source_url: Optional[str]
    enactment_date: Optional[str]
    version: Optional[str]
    violation_types: list = field(default_factory=list)
    tokens: int = 0
    order: int = 0


def count_tokens(text: str) -> int:
    return len(ENCODER.encode(text))


def write_jsonl(chunks: list, out_path: Path, mode: str = "a") -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, mode, encoding="utf-8") as f:
        for c in chunks:
            f.write(json.dumps(asdict(c), ensure_ascii=False) + "\n")


def split_by_articles(text: str) -> list:
    """전체 본문 → [(조 번호, 본문, 조 제목)] 분할."""
    matches = list(RE_ARTICLE.finditer(text))
    results = []
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        article_num = f"제{m.group(1)}조" + (f"의{m.group(2)}" if m.group(2) else "")
        title = m.group(3)
        body = text[start:end].strip()
        results.append((article_num, body, title))
    return results


def split_by_sections(article_body: str) -> list:
    """조 본문 → [(항 라벨 또는 None, 본문)] 분할.

    원문자(①②...) 또는 (1)(2)... 패턴 인식. 어떤 패턴도 없으면 전체 반환.
    """
    lines = article_body.split("\n")
    sections = []
    current_key: Optional[str] = None
    buffer: list = []

    for line in lines:
        stripped = line.lstrip()
        m_circle = RE_SECTION_CIRCLED.match(stripped)
        m_paren = RE_SECTION_PAREN.match(stripped)
        if m_circle:
            if buffer:
                sections.append((current_key, buffer))
            current_key = m_circle.group(0)
            buffer = [line]
        elif m_paren:
            if buffer:
                sections.append((current_key, buffer))
            current_key = f"({m_paren.group(1)})"
            buffer = [line]
        else:
            buffer.append(line)
    if buffer:
        sections.append((current_key, buffer))

    return [(k, "\n".join(v).strip()) for k, v in sections]


def split_by_items(section_body: str) -> list:
    """항 본문 → [(호 라벨 또는 None, 본문)] 분할.

    호(號): "N. " 패턴. 들여쓰기 무관(PDF 추출 시 들여쓰기 손실 빈번).
    단, "제N조" 패턴과 충돌 방지를 위해 조 정규식 우선 검사.
    """
    lines = section_body.split("\n")
    items = []
    current_key: Optional[str] = None
    buffer: list = []

    for line in lines:
        # "제N조"로 시작하면 호가 아님
        if RE_ARTICLE.match(line.lstrip()):
            buffer.append(line)
            continue
        m = RE_ITEM.match(line)
        if m:
            if buffer:
                items.append((current_key, buffer))
            current_key = f"{m.group(1)}."
            buffer = [line]
        else:
            buffer.append(line)
    if buffer:
        items.append((current_key, buffer))

    return [(k, "\n".join(v).strip()) for k, v in items]


def split_main_and_appendix(text: str) -> dict:
    """본문을 (본칙, 부칙들, 별표들)로 분할."""
    result = {"main": "", "addenda": [], "appendices": []}

    # 별표 분리: "[별표 N]" 또는 "별 표 N" 마커
    appendix_pat = re.compile(r"(\[별\s*표\s*\d*\]|^별\s+표\s*\d*)", re.MULTILINE)
    apx_matches = list(appendix_pat.finditer(text))
    if apx_matches:
        body_end = apx_matches[0].start()
        for i, m in enumerate(apx_matches):
            start = m.start()
            end = apx_matches[i + 1].start() if i + 1 < len(apx_matches) else len(text)
            result["appendices"].append((m.group(0).strip(), text[start:end].strip()))
        text = text[:body_end]

    # 부칙 분리: "부 칙" 또는 "부칙" 마커 (단, 본문에 한 번만 나오면 첫 등장 이후가 부칙)
    addendum_pat = re.compile(r"^부\s*칙(?:\s*<[^>]+>)?", re.MULTILINE)
    add_matches = list(addendum_pat.finditer(text))
    if add_matches:
        result["main"] = text[: add_matches[0].start()].strip()
        for i, m in enumerate(add_matches):
            start = m.start()
            end = add_matches[i + 1].start() if i + 1 < len(add_matches) else len(text)
            result["addenda"].append((m.group(0).strip(), text[start:end].strip()))
    else:
        result["main"] = text.strip()

    return result


def force_split_by_length(text: str, max_tokens: int = MAX_TOKENS) -> list:
    """긴 텍스트를 줄 단위로 강제 분할. 최후 수단."""
    lines = text.split("\n")
    chunks = []
    buffer: list = []
    buf_tokens = 0
    for line in lines:
        line_tokens = count_tokens(line)
        if buf_tokens + line_tokens > max_tokens and buffer:
            chunks.append("\n".join(buffer).strip())
            buffer = [line]
            buf_tokens = line_tokens
        else:
            buffer.append(line)
            buf_tokens += line_tokens
    if buffer:
        chunks.append("\n".join(buffer).strip())
    return [c for c in chunks if c]


def normalize_text(text: str) -> str:
    """과도한 공백·페이지 헤더 흔적 정제 (보수적)."""
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r"[ \t]+\n", "\n", text)
    return text.strip()
