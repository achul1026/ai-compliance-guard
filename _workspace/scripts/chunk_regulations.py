"""
DATA-002: 규정 PDF/HTML 파싱 및 청킹 (Phase 1, P1-2)

입력: _workspace/regulations/ 의 8개 문서
출력:
  - _workspace/regulations_chunks.jsonl
  - _workspace/CHUNKING_REPORT.md (별도 작성)
  - _workspace/sample_chunks_validation.md (별도 작성)

청킹 원칙
- 토큰 인코더: cl100k_base (Upstage/OpenAI 호환)
- 목표 토큰: 300~500, 상한 500
- 조(條) > 항(項) > 호(號) > 목 계층 보존
- 청크가 500 토큰을 초과하면 의미 단위(문장·줄바꿈) 경계로 분할
- 청크가 100 토큰 미만이면 같은 상위 단위 내 인접 청크와 병합 (단, 조 경계는 넘지 않음)
"""

from __future__ import annotations

import json
import logging
import os
import re
import sys
import warnings
from dataclasses import dataclass, asdict, field
from typing import Iterable, Optional

import pdfplumber
import tiktoken

logging.getLogger("pdfminer").setLevel(logging.ERROR)
logging.getLogger("pdfplumber").setLevel(logging.ERROR)
warnings.filterwarnings("ignore")

ROOT = "/Users/chul/projects/ai-compliance-guard/_workspace"
REG_DIR = os.path.join(ROOT, "regulations")
OUT_JSONL = os.path.join(ROOT, "regulations_chunks.jsonl")

# 토큰 인코더
ENC = tiktoken.get_encoding("cl100k_base")
TOKEN_MIN = 100
TOKEN_TARGET_LOW = 300
TOKEN_TARGET_HIGH = 500
TOKEN_HARD_MAX = 600  # 분할 후에도 이 한계는 초과하지 않음


def count_tokens(text: str) -> int:
    return len(ENC.encode(text))


# =====================
# 데이터 모델
# =====================


@dataclass
class Chunk:
    id: str
    law_name: str
    article: Optional[str]  # 예: "제8조" / 없으면 None
    section: Optional[str]  # 항(項): "①" 등 / 없으면 None
    subsection: Optional[str]  # 호(號): "1호" 등 / 없으면 None
    item: Optional[str]  # 목: "가목" 등 (식약처 고시용 추가 필드)
    text: str
    source_file: str
    enactment_date: Optional[str]
    last_revised: Optional[str]
    tokens: int
    order: int
    parser: str  # 어느 파서로 추출되었는지
    notes: Optional[str] = None  # 검증 메모


# =====================
# 메타데이터 사전 (인벤토리 기반)
# =====================

LAW_META = {
    "건강기능식품에 관한 법률": {
        "enactment_date": "2003-08-26",  # 최초 제정
        "last_revised": "2024-01-23",  # 인벤토리 기준 공포일
        "source_file": "regulations/건강기능식품법_law.go.kr_20260526.html",
    },
    "식품 등의 표시ㆍ광고에 관한 법률": {
        "enactment_date": "2018-03-13",
        "last_revised": "2025-03-18",
        "source_file": "regulations/식품표시광고법_law.go.kr_20260526.html",
    },
    "화장품법": {
        "enactment_date": "1999-09-07",
        "last_revised": "2024-07-09",
        "source_file": "regulations/화장품법_law.go.kr_20260526.html",
    },
    "식품등의 부당한 표시 또는 광고의 내용 기준": {
        "enactment_date": "2024-06-11",
        "last_revised": "2024-06-11",
        "source_file": "regulations/부당표시광고_내용기준_식약처_제2024-23호_20260526.pdf",
    },
    "건강기능식품 인체적용시험 표시·광고 가이드라인": {
        "enactment_date": "2022-09-01",
        "last_revised": "2022-09-01",
        "source_file": "regulations/건강기능식품_표시광고_가이드라인_식약처_20260526.pdf",
    },
    "화장품 표시·광고 관리 지침": {
        "enactment_date": "2024-12-01",
        "last_revised": "2024-12-01",
        "source_file": "regulations/화장품_표시광고_관리지침_식약처_2024.12_20260526.pdf",
    },
    "건강기능식품 표시·광고 자율심의기구 운영규정": {
        "enactment_date": "2014-02-12",
        "last_revised": "2024-04-01",
        "source_file": "regulations/협회_자율심의_운영규정_KHFF_20260526.pdf",
    },
    "온라인 부당광고 사례집": {
        "enactment_date": "2022-04-01",
        "last_revised": "2022-04-01",
        "source_file": "regulations/온라인_부당광고_사례집_식약처_2022_20260526.pdf",
    },
}


# =====================
# 텍스트 전처리
# =====================

# pdfplumber가 한글 자모 분리(NFD) 형태로 추출하는 경우가 있어 NFC로 정규화
import unicodedata


def clean_pdf_text(text: str) -> str:
    if not text:
        return ""
    # 자모 분리 정규화
    text = unicodedata.normalize("NFC", text)
    # 중복 공백/제어문자 정리
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text)
    # 페이지 푸터 패턴 제거: "- 1 -" 등
    text = re.sub(r"\n\s*-\s*\d+\s*-\s*\n", "\n", text)
    # 동일 글자 반복 (목차 장식: "한한한한한") 제거: 5회 이상 연속 동일 글자
    text = re.sub(r"(\S)\1{4,}", r"\1", text)
    # 다중 공백
    text = re.sub(r"[ \t]+", " ", text)
    # 다중 줄바꿈
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def remove_running_headers(pages_text: list[str]) -> list[str]:
    """페이지마다 같은 헤더/푸터가 반복되면 제거"""
    if len(pages_text) < 3:
        return pages_text
    # 첫 줄들이 반복되는지 검사
    first_lines = [p.split("\n", 1)[0].strip() for p in pages_text if p.strip()]
    if not first_lines:
        return pages_text
    from collections import Counter

    c = Counter(first_lines)
    common = {k for k, v in c.items() if v >= max(3, len(first_lines) // 2) and len(k) < 60}
    cleaned = []
    for p in pages_text:
        lines = p.split("\n")
        new_lines = [ln for ln in lines if ln.strip() not in common]
        cleaned.append("\n".join(new_lines))
    return cleaned


# =====================
# 청크 분할 유틸
# =====================


def split_long_text(text: str, hard_max: int = TOKEN_HARD_MAX, target_high: int = TOKEN_TARGET_HIGH) -> list[str]:
    """긴 텍스트를 문장/줄 단위 경계로 잘라 target_high를 넘기지 않도록"""
    if count_tokens(text) <= target_high:
        return [text]
    # 1차 분할: 문장 종결 부호
    sentences = re.split(r"(?<=[\.\!\?。·])\s+|\n+", text)
    sentences = [s for s in sentences if s.strip()]
    chunks: list[str] = []
    buf = ""
    for s in sentences:
        candidate = (buf + " " + s).strip() if buf else s
        if count_tokens(candidate) > target_high and buf:
            chunks.append(buf.strip())
            buf = s
        else:
            buf = candidate
    if buf.strip():
        chunks.append(buf.strip())
    # 잔류 hard-max 초과: 강제 토큰 단위 분할
    final = []
    for c in chunks:
        if count_tokens(c) <= hard_max:
            final.append(c)
            continue
        toks = ENC.encode(c)
        for i in range(0, len(toks), target_high):
            final.append(ENC.decode(toks[i : i + target_high]))
    return final


def merge_small_chunks(chunks: list["Chunk"], same_article_only: bool = True) -> list["Chunk"]:
    """연속된 청크 중 TOKEN_TARGET_LOW(300) 미만은 같은 article (또는 같은 law_name) 내에서
    target_high(500)를 넘지 않는 한 그리디 병합.

    same_article_only=True: 동일 article 안에서만 (법령·고시 권장)
    same_article_only=False: 동일 law_name 내 article 경계 무시 (가이드라인용)
    """
    if not chunks:
        return chunks
    merged: list[Chunk] = []
    for c in chunks:
        if not merged:
            merged.append(c)
            continue
        prev = merged[-1]
        if prev.law_name != c.law_name:
            merged.append(c)
            continue
        if same_article_only and prev.article != c.article:
            merged.append(c)
            continue
        # 작은 청크 우선 병합
        if (
            (prev.tokens < TOKEN_TARGET_LOW or c.tokens < TOKEN_TARGET_LOW)
            and prev.tokens + c.tokens <= TOKEN_TARGET_HIGH
        ):
            new_text = prev.text + "\n" + c.text
            prev.text = new_text
            prev.tokens = count_tokens(new_text)
            # 합쳐진 호 정보 메모
            if c.subsection and c.subsection != prev.subsection:
                tag = f"합쳐진 호: {prev.subsection or '-'}+{c.subsection}"
                prev.notes = (prev.notes + " | " if prev.notes else "") + tag
        else:
            merged.append(c)
    return merged


def make_id(law_name: str, article: Optional[str], section: Optional[str], subsection: Optional[str], idx: int) -> str:
    parts = [law_name.replace(" ", "").replace("·", "").replace("ㆍ", "")]
    if article:
        parts.append(article)
    if section:
        parts.append(section)
    if subsection:
        parts.append(subsection)
    parts.append(f"{idx:03d}")
    return "_".join(parts)


# =====================
# 파서: 식약처 고시 (부당표시광고 내용기준)
# =====================

ARTICLE_RE = re.compile(r"^제\s*(\d+)\s*조(?:\(([^)]+)\))?")
HO_RE = re.compile(r"^(\d+)\.\s+(.+)$")  # "1. 식품등을..."
GA_RE = re.compile(r"^([가-힣])\.\s+(.+)$")  # "가. 한약의..."
HANG_RE = re.compile(r"^([①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮])\s*(.+)$")


def parse_mfds_notice(law_name: str, source_file: str, pdf_path: str) -> list[Chunk]:
    """식약처 고시 PDF (조·호·목 3단계)"""
    chunks: list[Chunk] = []
    with pdfplumber.open(pdf_path) as pdf:
        pages_text = [clean_pdf_text(p.extract_text() or "") for p in pdf.pages]
    pages_text = remove_running_headers(pages_text)
    full = "\n".join(pages_text)
    # 라인 단위 파싱
    lines = [ln.rstrip() for ln in full.split("\n")]

    current_article = None  # 예: "제1조"
    current_article_title = None
    current_ho = None  # "1호"
    current_ho_title = None
    current_section = None  # "가목"
    buf_lines: list[str] = []
    order_idx = 0

    def flush(article, article_title, ho, ho_title, section, lines_buf):
        nonlocal order_idx
        if not lines_buf:
            return
        text = "\n".join(lines_buf).strip()
        if not text:
            return
        # 컨텍스트 헤더 (검색에 도움)
        header_parts = []
        if article:
            header_parts.append(f"{article}({article_title})" if article_title else article)
        if ho:
            header_parts.append(f"{ho}호: {ho_title}" if ho_title else f"{ho}호")
        if section:
            header_parts.append(f"{section}목")
        header = " > ".join(header_parts)
        body = f"[{law_name} {header}]\n{text}" if header else f"[{law_name}]\n{text}"

        # 토큰 검사 후 분할
        for piece in split_long_text(body):
            order_idx += 1
            chunks.append(
                Chunk(
                    id=make_id(law_name, article, None, f"{ho}호" if ho else None, order_idx),
                    law_name=law_name,
                    article=article,
                    section=None,
                    subsection=f"{ho}호" if ho else None,
                    item=f"{section}목" if section else None,
                    text=piece,
                    source_file=source_file,
                    enactment_date=LAW_META[law_name]["enactment_date"],
                    last_revised=LAW_META[law_name]["last_revised"],
                    tokens=count_tokens(piece),
                    order=order_idx,
                    parser="mfds_notice",
                )
            )

    for raw in lines:
        line = raw.strip()
        if not line:
            buf_lines.append("")
            continue

        m_art = ARTICLE_RE.match(line)
        if m_art:
            # flush 이전 컨텍스트
            flush(current_article, current_article_title, current_ho, current_ho_title, current_section, buf_lines)
            buf_lines = []
            current_article = f"제{m_art.group(1)}조"
            current_article_title = m_art.group(2)
            current_ho = None
            current_ho_title = None
            current_section = None
            # 본문 잔여 (괄호 뒤 텍스트)
            rest = line[m_art.end() :].strip()
            if rest:
                buf_lines.append(rest)
            continue

        m_ho = HO_RE.match(line)
        # 호는 조 컨텍스트 안에서만 인정. 또한 "가." 패턴과 충돌하지 않도록 숫자 호 우선
        if current_article and m_ho:
            flush(current_article, current_article_title, current_ho, current_ho_title, current_section, buf_lines)
            buf_lines = []
            current_ho = m_ho.group(1)
            current_ho_title = m_ho.group(2)
            current_section = None
            buf_lines.append(line)
            continue

        m_ga = GA_RE.match(line)
        if current_article and m_ga:
            flush(current_article, current_article_title, current_ho, current_ho_title, current_section, buf_lines)
            buf_lines = []
            current_section = m_ga.group(1)
            buf_lines.append(line)
            continue

        buf_lines.append(line)

    flush(current_article, current_article_title, current_ho, current_ho_title, current_section, buf_lines)
    # 1차: 같은 article 내 작은 호 청크 병합 (호 경계 유지)
    chunks = merge_small_chunks(chunks, same_article_only=True)
    # 2차: 같은 article 내 인접 작은 청크 추가 병합 (호 경계는 약하게 해제, 메타는 첫 호 보존)
    chunks = merge_small_chunks(chunks, same_article_only=True)
    return chunks


# =====================
# 파서: 협회 자율심의 운영규정 (법령 본문 짧게 포함)
# =====================


def parse_associaton_pdf(source_file: str, pdf_path: str) -> list[Chunk]:
    """협회 PDF: 다단 컬럼이라 텍스트가 단편적. 조 단위 정규식으로만 청크 추출"""
    with pdfplumber.open(pdf_path) as pdf:
        full = "\n".join(clean_pdf_text(p.extract_text() or "") for p in pdf.pages)

    # 협회 PDF는 4단 컬럼이라 텍스트가 가로로 섞임. "제N조(제목)" 단위로 끊고
    # 다음 "제N조"가 나올 때까지를 한 단위로 본다.
    pattern = re.compile(r"(제\s*\d+\s*조\([^)]+\))")
    parts = pattern.split(full)
    # parts[0]은 헤더, 이후 (article_header, body) 쌍
    chunks: list[Chunk] = []
    order_idx = 0
    # 협회 PDF에는 식품표시광고법 + 시행령 + 시행규칙 + 건기식법 + 운영규정 본문이 혼재.
    # 본 단계에서는 모두 "건강기능식품 표시·광고 자율심의기구 운영규정" 한 법령명으로 보관하고
    # 후속 재조립은 보고서에서 권고.
    law_name = "건강기능식품 표시·광고 자율심의기구 운영규정"
    # 단, 식품표시광고법 본문이 핵심이므로 law_name='식품 등의 표시ㆍ광고에 관한 법률'로
    # 별도 라벨링되는 분기 처리는 후속(개선) 단계로 미룸.
    for i in range(1, len(parts), 2):
        art_header = parts[i].strip()
        body = parts[i + 1].strip() if i + 1 < len(parts) else ""
        # article 코드
        m = re.match(r"제\s*(\d+)\s*조\(([^)]+)\)", art_header)
        if not m:
            continue
        article = f"제{m.group(1)}조"
        article_title = m.group(2)
        text = f"[{law_name} {article}({article_title})]\n{body}".strip()
        if count_tokens(text) < 20:
            continue
        for piece in split_long_text(text):
            order_idx += 1
            chunks.append(
                Chunk(
                    id=make_id(law_name, article, None, None, order_idx),
                    law_name=law_name,
                    article=article,
                    section=None,
                    subsection=None,
                    item=None,
                    text=piece,
                    source_file=source_file,
                    enactment_date=LAW_META[law_name]["enactment_date"],
                    last_revised=LAW_META[law_name]["last_revised"],
                    tokens=count_tokens(piece),
                    order=order_idx,
                    parser="association_pdf",
                    notes="협회 PDF 다단 컬럼 — 식품표시광고법/시행령/시행규칙/건기식법/운영규정 혼재. 후속 재라벨링 필요",
                )
            )
    return chunks


# =====================
# 파서: 식약처 가이드라인/지침 PDF (서술형, 섹션 기반)
# =====================

SECTION_HEAD_RE = re.compile(r"^(?:\s*)((?:제\s*\d+\s*[장절]|[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+|[IVX]+\.|\d+\.\d+|\d+\.|【[^】]+】|\([가-하]\)))\s+(.+)$")


def parse_mfds_guideline(law_name: str, source_file: str, pdf_path: str) -> list[Chunk]:
    """식약처 가이드라인/지침: 조항 단위가 없으므로 페이지·헤더 기반 청킹"""
    chunks: list[Chunk] = []
    with pdfplumber.open(pdf_path) as pdf:
        pages_text = [clean_pdf_text(p.extract_text() or "") for p in pdf.pages]
    pages_text = remove_running_headers(pages_text)

    # 첫 4페이지는 표지/점검표/목차로 가정 — 본문성이 떨어지지만 일단 포함하고
    # 토큰 합치기로 자연스럽게 작은 청크는 병합
    full = "\n\n".join(pages_text)
    # 섹션 헤더로 분할
    lines = full.split("\n")
    sections: list[tuple[str, list[str]]] = []  # (header, body_lines)
    current_header = "서두"
    current_body: list[str] = []
    for ln in lines:
        m = SECTION_HEAD_RE.match(ln.strip())
        if m and len(ln.strip()) < 120:
            if current_body:
                sections.append((current_header, current_body))
            current_header = ln.strip()
            current_body = []
        else:
            current_body.append(ln)
    if current_body:
        sections.append((current_header, current_body))

    order_idx = 0
    for header, body in sections:
        body_text = "\n".join(body).strip()
        if not body_text:
            continue
        text = f"[{law_name} | {header}]\n{body_text}"
        # 가이드라인의 섹션은 길이 편차가 크므로 split_long_text로 분할
        for piece in split_long_text(text):
            tok = count_tokens(piece)
            if tok < 30:  # 너무 짧은 단편은 스킵 (병합은 후처리에서)
                # 그래도 보존하되 병합 대상으로 표시
                pass
            order_idx += 1
            chunks.append(
                Chunk(
                    id=make_id(law_name, None, None, None, order_idx),
                    law_name=law_name,
                    article=None,
                    section=None,
                    subsection=None,
                    item=None,
                    text=piece,
                    source_file=source_file,
                    enactment_date=LAW_META[law_name]["enactment_date"],
                    last_revised=LAW_META[law_name]["last_revised"],
                    tokens=tok,
                    order=order_idx,
                    parser="mfds_guideline",
                    notes=f"섹션 헤더: {header[:60]}",
                )
            )
    # 가이드라인은 조항이 없으므로 article 경계 무시하고 다중 패스로 병합
    for _ in range(3):
        chunks = merge_small_chunks(chunks, same_article_only=False)
    return chunks


# =====================
# 파서: 법령 HTML (law.go.kr) — 본문 미포함 케이스의 메타데이터 보존 청크
# =====================


def parse_lawgokr_html_stub(law_name: str, source_file: str) -> list[Chunk]:
    """law.go.kr HTML은 JS 비동기 로딩이라 본문이 없음.
    인덱스 일관성을 위해 '본문 미수록' 스텁 청크 1건만 생성하고, CHUNKING_REPORT.md에 후속 작업으로 표기."""
    meta = LAW_META[law_name]
    text = (
        f"[{law_name}] 본 청크는 law.go.kr에서 수집한 HTML 파일이 JavaScript 비동기 로드 방식이라 "
        f"본문이 포함되지 않은 스텁입니다. 본문 텍스트는 elaw Open API(OC 인증 필요) 또는 "
        f"국가법령정보센터의 lsBdyInfoP 비공식 엔드포인트, 또는 PDF 인쇄본 재수집을 통해 보강해야 합니다. "
        f"본 법령의 핵심 조항(예: 식품표시광고법 제8조, 건강기능식품법 제18조, 화장품법 제13조)은 "
        f"식약처 고시·가이드라인·협회 운영규정 PDF에 부분적으로 인용되어 있어 검색 가능합니다. "
        f"공포일/시행일 메타: 공포 {meta['last_revised']}."
    )
    return [
        Chunk(
            id=make_id(law_name, None, None, None, 1) + "_STUB",
            law_name=law_name,
            article=None,
            section=None,
            subsection=None,
            item=None,
            text=text,
            source_file=source_file,
            enactment_date=meta["enactment_date"],
            last_revised=meta["last_revised"],
            tokens=count_tokens(text),
            order=1,
            parser="lawgokr_html_stub",
            notes="STUB - 본문 미수록. P1-2 후속 작업으로 본문 보강 필요",
        )
    ]


# =====================
# 파서: OCR 필요 PDF 스텁
# =====================


def parse_ocr_required_stub(law_name: str, source_file: str, pdf_path: str) -> list[Chunk]:
    with pdfplumber.open(pdf_path) as pdf:
        n_pages = len(pdf.pages)
    meta = LAW_META[law_name]
    text = (
        f"[{law_name}] 본 청크는 OCR 처리 전 스텁입니다. 원문 PDF는 {n_pages}페이지 이미지 기반으로 "
        f"pdfplumber 텍스트 추출이 0자입니다. Tesseract Korean 또는 PaddleOCR을 적용해 본문 텍스트를 "
        f"확보한 뒤 본 청크를 교체해야 합니다. 본 자료는 식약처가 발간한 공식 부당광고 사례집으로, "
        f"실제 적발 사례를 부당광고 유형별로 분류·해설하여 FAQ 역할을 수행합니다. "
        f"발행: {meta['enactment_date']}."
    )
    return [
        Chunk(
            id=make_id(law_name, None, None, None, 1) + "_OCR_STUB",
            law_name=law_name,
            article=None,
            section=None,
            subsection=None,
            item=None,
            text=text,
            source_file=source_file,
            enactment_date=meta["enactment_date"],
            last_revised=meta["last_revised"],
            tokens=count_tokens(text),
            order=1,
            parser="ocr_required_stub",
            notes=f"OCR STUB - {n_pages}p 이미지 PDF. Tesseract Korean 또는 PaddleOCR 후속 처리 필요",
        )
    ]


# =====================
# 메인
# =====================


def main():
    all_chunks: list[Chunk] = []

    # 1) law.go.kr HTML 3종 (본문 미수록 스텁)
    all_chunks += parse_lawgokr_html_stub(
        "건강기능식품에 관한 법률",
        "regulations/건강기능식품법_law.go.kr_20260526.html",
    )
    all_chunks += parse_lawgokr_html_stub(
        "식품 등의 표시ㆍ광고에 관한 법률",
        "regulations/식품표시광고법_law.go.kr_20260526.html",
    )
    all_chunks += parse_lawgokr_html_stub(
        "화장품법",
        "regulations/화장품법_law.go.kr_20260526.html",
    )

    # 2) 식약처 고시 (부당표시광고 내용기준)
    all_chunks += parse_mfds_notice(
        "식품등의 부당한 표시 또는 광고의 내용 기준",
        "regulations/부당표시광고_내용기준_식약처_제2024-23호_20260526.pdf",
        os.path.join(REG_DIR, "부당표시광고_내용기준_식약처_제2024-23호_20260526.pdf"),
    )

    # 3) 식약처 가이드라인 (건기식 인체적용시험)
    all_chunks += parse_mfds_guideline(
        "건강기능식품 인체적용시험 표시·광고 가이드라인",
        "regulations/건강기능식품_표시광고_가이드라인_식약처_20260526.pdf",
        os.path.join(REG_DIR, "건강기능식품_표시광고_가이드라인_식약처_20260526.pdf"),
    )

    # 4) 식약처 지침 (화장품 표시광고 관리지침)
    all_chunks += parse_mfds_guideline(
        "화장품 표시·광고 관리 지침",
        "regulations/화장품_표시광고_관리지침_식약처_2024.12_20260526.pdf",
        os.path.join(REG_DIR, "화장품_표시광고_관리지침_식약처_2024.12_20260526.pdf"),
    )

    # 5) 협회 자율심의 운영규정 PDF
    all_chunks += parse_associaton_pdf(
        "regulations/협회_자율심의_운영규정_KHFF_20260526.pdf",
        os.path.join(REG_DIR, "협회_자율심의_운영규정_KHFF_20260526.pdf"),
    )

    # 6) 온라인 부당광고 사례집 (OCR 필요 스텁)
    all_chunks += parse_ocr_required_stub(
        "온라인 부당광고 사례집",
        "regulations/온라인_부당광고_사례집_식약처_2022_20260526.pdf",
        os.path.join(REG_DIR, "온라인_부당광고_사례집_식약처_2022_20260526.pdf"),
    )

    # ID 중복 검사 및 글로벌 인덱스 재부여
    seen_ids = set()
    out_chunks: list[Chunk] = []
    for i, c in enumerate(all_chunks, 1):
        if c.id in seen_ids:
            c.id = c.id + f"_dup{i}"
        seen_ids.add(c.id)
        out_chunks.append(c)

    # JSONL 직렬화
    with open(OUT_JSONL, "w", encoding="utf-8") as f:
        for c in out_chunks:
            f.write(json.dumps(asdict(c), ensure_ascii=False) + "\n")

    # 통계 요약 (간단)
    by_law: dict[str, dict] = {}
    by_parser: dict[str, int] = {}
    tokens_all: list[int] = []
    for c in out_chunks:
        by_law.setdefault(c.law_name, {"count": 0, "tokens": []})
        by_law[c.law_name]["count"] += 1
        by_law[c.law_name]["tokens"].append(c.tokens)
        by_parser[c.parser] = by_parser.get(c.parser, 0) + 1
        tokens_all.append(c.tokens)

    print(f"TOTAL chunks: {len(out_chunks)}")
    print(f"output: {OUT_JSONL}")
    if tokens_all:
        tokens_all_sorted = sorted(tokens_all)
        n = len(tokens_all_sorted)
        median = tokens_all_sorted[n // 2]
        avg = sum(tokens_all_sorted) / n
        in_range = sum(1 for t in tokens_all_sorted if TOKEN_TARGET_LOW <= t <= TOKEN_TARGET_HIGH)
        below = sum(1 for t in tokens_all_sorted if t < TOKEN_TARGET_LOW)
        above = sum(1 for t in tokens_all_sorted if t > TOKEN_TARGET_HIGH)
        print(f"tokens median={median} avg={avg:.1f} min={tokens_all_sorted[0]} max={tokens_all_sorted[-1]}")
        print(f"in 300~500: {in_range}/{n} ({100*in_range/n:.1f}%)")
        print(f"below 300: {below}/{n} ({100*below/n:.1f}%)")
        print(f"above 500: {above}/{n} ({100*above/n:.1f}%)")
    print("--- by parser ---")
    for k, v in sorted(by_parser.items()):
        print(f"  {k}: {v}")
    print("--- by law ---")
    for k, v in sorted(by_law.items()):
        toks = v["tokens"]
        avg = sum(toks) / len(toks)
        print(f"  {k}: {v['count']} chunks, avg tokens {avg:.1f}")


if __name__ == "__main__":
    main()
