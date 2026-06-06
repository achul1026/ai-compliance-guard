"""그룹 A 텍스트 PDF 청킹.

사용:
    python chunk_pdf.py <pdf_path> <law_short_name>
예:
    python chunk_pdf.py ../regulations/부당표시광고_내용기준_식약처_제2024-23호_20260526.pdf 부당표시광고_내용기준
"""
import sys
from pathlib import Path
import pdfplumber
from common import (
    Chunk,
    count_tokens,
    force_split_by_length,
    normalize_text,
    split_by_articles,
    split_by_items,
    split_by_sections,
    split_main_and_appendix,
    write_jsonl,
    MAX_TOKENS,
    MIN_TOKENS,
    SOFT_MAX_TOKENS,
)

META = {
    "부당표시광고_내용기준": {
        "law_name": "식품등의 부당한 표시 또는 광고의 내용 기준",
        "version": "식품의약품안전처고시 제2024-23호",
        "enactment_date": "2026-01-01",
        "source_url": "https://www.mfds.go.kr/brd/m_211/view.do?seq=14829",
    },
    "협회_자율심의_운영규정": {
        "law_name": "건강기능식품의 표시·광고 자율심의기구 운영규정",
        "version": "2024-02-22 개정 / 2024-04-01 시행",
        "enactment_date": "2024-04-01",
        "source_url": "https://ad.khff.or.kr/user/intro/IntroUser6.do?_menuNo=328",
    },
    "건강기능식품_표시광고_가이드라인": {
        "law_name": "한눈에 보는 건강기능식품 인체적용시험 표시·광고 가이드라인",
        "version": "민원인 안내서 등록번호 안내서-1223-01",
        "enactment_date": "2022-09-01",
        "source_url": "https://www.mfds.go.kr/brd/m_1060/view.do?seq=15075",
    },
    "화장품_표시광고_관리지침": {
        "law_name": "화장품 표시·광고 관리 지침",
        "version": "2024-12 발행 (식약처 바이오생약국)",
        "enactment_date": "2024-12-01",
        "source_url": "https://kcia.or.kr/inc/down.php?dir=BOARD&file_name=202411_173269863239420_2.pdf",
    },
}

OUT_PATH = Path(__file__).parent.parent / "regulations_chunks.jsonl"


def extract_text(pdf_path: Path) -> str:
    parts = []
    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages:
            t = page.extract_text() or ""
            parts.append(t)
    return normalize_text("\n".join(parts))


def make_chunk(
    short_name: str,
    meta: dict,
    source_file: str,
    article_num: str,
    article_title,
    section,
    item,
    text: str,
    order: int,
) -> Chunk:
    id_parts = [short_name, article_num]
    if section:
        id_parts.append(section.replace("(", "").replace(")", ""))
    if item:
        id_parts.append(f"호{item.replace('.', '')}")
    id_parts.append(f"{order:03d}")
    return Chunk(
        id="_".join(id_parts),
        law_name=meta["law_name"],
        law_short_name=short_name,
        article=article_num,
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


def _chunk_articles(
    body_text: str,
    short_name: str,
    meta: dict,
    source_file: str,
    article_prefix: str,
    start_order: int,
) -> tuple:
    """본문(또는 부칙)의 조 단위 청킹. article_prefix로 본칙/부칙 구분."""
    chunks = []
    order = start_order
    articles = split_by_articles(body_text)

    if not articles:
        # 조 구조 없음 → 길이 기반 강제 분할
        for piece in force_split_by_length(body_text, MAX_TOKENS):
            order += 1
            chunks.append(make_chunk(
                short_name, meta, source_file,
                article_num=f"{article_prefix}(전문)", article_title=None,
                section=None, item=None,
                text=piece, order=order,
            ))
        return chunks, order

    for article_num, body, title in articles:
        article_label = f"{article_prefix}{article_num}" if article_prefix else article_num
        body_tokens = count_tokens(body)

        if body_tokens <= MAX_TOKENS:
            order += 1
            chunks.append(make_chunk(
                short_name, meta, source_file,
                article_num=article_label, article_title=title,
                section=None, item=None,
                text=body, order=order,
            ))
            continue

        sections = split_by_sections(body)
        # 항 분할 결과가 1개뿐(=항 구조 없음)이면 바로 호 분할 시도
        if len(sections) <= 1:
            sections = [(None, body)]

        for section_key, section_body in sections:
            section_tokens = count_tokens(section_body)

            if section_tokens <= MAX_TOKENS:
                order += 1
                chunks.append(make_chunk(
                    short_name, meta, source_file,
                    article_num=article_label, article_title=title,
                    section=section_key, item=None,
                    text=section_body, order=order,
                ))
                continue

            items = split_by_items(section_body)
            if len(items) <= 1:
                # 호 분할 불가 → 길이 강제 분할
                for piece in force_split_by_length(section_body, MAX_TOKENS):
                    order += 1
                    chunks.append(make_chunk(
                        short_name, meta, source_file,
                        article_num=article_label, article_title=title,
                        section=section_key, item=None,
                        text=piece, order=order,
                    ))
                continue

            for item_key, item_body in items:
                item_tokens = count_tokens(item_body)
                if item_tokens <= SOFT_MAX_TOKENS:
                    order += 1
                    chunks.append(make_chunk(
                        short_name, meta, source_file,
                        article_num=article_label, article_title=title,
                        section=section_key, item=item_key,
                        text=item_body, order=order,
                    ))
                else:
                    # 호 하나가 여전히 크면 길이 강제 분할
                    for piece in force_split_by_length(item_body, MAX_TOKENS):
                        order += 1
                        chunks.append(make_chunk(
                            short_name, meta, source_file,
                            article_num=article_label, article_title=title,
                            section=section_key, item=item_key,
                            text=piece, order=order,
                        ))

    return chunks, order


def chunk_document(text: str, short_name: str, source_file: str) -> list:
    meta = META[short_name]
    parts = split_main_and_appendix(text)

    all_chunks = []
    order = 0

    # 본칙
    main_chunks, order = _chunk_articles(
        parts["main"], short_name, meta, source_file,
        article_prefix="", start_order=order,
    )
    all_chunks.extend(main_chunks)

    # 부칙
    for addendum_label, addendum_body in parts["addenda"]:
        prefix = f"{addendum_label.replace(' ', '')}_"
        addendum_chunks, order = _chunk_articles(
            addendum_body, short_name, meta, source_file,
            article_prefix=prefix, start_order=order,
        )
        all_chunks.extend(addendum_chunks)

    # 별표 (길이 기반 분할)
    for apx_label, apx_body in parts["appendices"]:
        for piece in force_split_by_length(apx_body, MAX_TOKENS):
            order += 1
            all_chunks.append(make_chunk(
                short_name, meta, source_file,
                article_num=apx_label, article_title=None,
                section=None, item=None,
                text=piece, order=order,
            ))

    return all_chunks


def main():
    if len(sys.argv) != 3:
        print("Usage: python chunk_pdf.py <pdf_path> <law_short_name>")
        print(f"law_short_name options: {list(META.keys())}")
        sys.exit(1)

    pdf_path = Path(sys.argv[1])
    short_name = sys.argv[2]

    if short_name not in META:
        print(f"Unknown short_name: {short_name}")
        print(f"Options: {list(META.keys())}")
        sys.exit(1)

    print(f"[1/3] 본문 추출: {pdf_path.name}")
    text = extract_text(pdf_path)
    print(f"      총 {len(text):,}자")

    print(f"[2/3] 청킹: {short_name}")
    chunks = chunk_document(text, short_name, pdf_path.name)
    print(f"      총 {len(chunks)}개 청크")

    print(f"[3/3] JSONL append: {OUT_PATH}")
    write_jsonl(chunks, OUT_PATH, mode="a")

    if chunks:
        token_counts = [c.tokens for c in chunks]
        print(
            f"\n토큰 분포: min={min(token_counts)}, "
            f"max={max(token_counts)}, "
            f"avg={sum(token_counts) // len(token_counts)}"
        )
        over = [c.id for c in chunks if c.tokens > MAX_TOKENS]
        under = [c.id for c in chunks if c.tokens < MIN_TOKENS]
        if over:
            print(f"WARN MAX({MAX_TOKENS}) 초과 {len(over)}개: {over[:3]}")
        if under:
            print(f"WARN MIN({MIN_TOKENS}) 미달 {len(under)}개: {under[:3]}")


if __name__ == "__main__":
    main()
