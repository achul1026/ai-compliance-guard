#!/usr/bin/env python3
"""
Phase 1.5 Step 2: law.go.kr PDF에서 법령 본문 추출

목표:
  - law.go.kr에서 3개 법령 PDF 다운로드
  - PDF → 텍스트 추출 → 조항 파싱 → 청킹
  - regulations_chunks.jsonl 스텁 교체
"""

import pdfplumber
import requests
import json
import re
from pathlib import Path
from typing import List, Dict, Optional


# 2개 법령 메타데이터 (식품표시광고법은 파일 검증 후 추가)
LAWS = [
    {
        "name": "건강기능식품에 관한 법률",
        "url": "https://www.law.go.kr/DRF/GetHTML.aspx?OC=hg001&OV=1.0&type=HTML&source=http://www.law.go.kr/법령/건강기능식품법.pdf",
        "enactment_date": "2003-08-26",
        "last_revised": "2024-01-23"
    },
    {
        "name": "화장품법",
        "url": "https://www.law.go.kr/DRF/GetHTML.aspx?OC=hc001&OV=1.0&type=HTML&source=http://www.law.go.kr/법령/화장품법.pdf",
        "enactment_date": "1999-09-07",
        "last_revised": "2024-07-09"
    }
]

RAW_LAWS_DIR = Path(__file__).parent.parent / "raw" / "laws"

# HTTP 헤더 (User-Agent 필수)
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36'
}


def download_pdf(law_info: Dict) -> Optional[Path]:
    """
    law.go.kr에서 PDF 다운로드 (또는 이미 존재하는 파일 사용)

    Args:
        law_info: 법령 정보 (name, url)

    Returns:
        다운로드 파일 경로, 실패 시 None
    """
    law_name = law_info["name"]
    url = law_info["url"]

    # 파일명 생성: "건강기능식품에 관한 법률" → "건강기능식품법.pdf"
    # "에 관한 법률", "ㆍ" 등 제거
    filename = law_name.replace("에 관한 법률", "").replace("ㆍ", "").strip() + ".pdf"
    save_path = RAW_LAWS_DIR / filename

    # 이미 폴더에 있는 PDF 찾기 (파일명 약간 다를 수 있으니 와일드카드 사용)
    search_name = law_name.replace("에 관한 법률", "").replace("ㆍ", "").strip()
    existing_files = list(RAW_LAWS_DIR.glob(f"*{search_name}*.pdf"))

    if existing_files:
        found_path = existing_files[0]
        print(f"    이미 존재: {found_path.name}")
        return found_path

    if save_path.exists():
        print(f"    이미 존재: {filename}")
        return save_path

    # PDF 다운로드 (User-Agent 헤더 포함)
    try:
        response = requests.get(url, headers=HEADERS, timeout=15)
        response.raise_for_status()  # HTTP 오류 발생 시 exception 발생

        # 파일이 너무 작으면 HTML 응답일 가능성 높음 (최소 100KB)
        if len(response.content) < 100000:
            print(f"    ⚠️  다운로드된 파일이 너무 작습니다 ({len(response.content) / 1024:.1f} KB)")
            print(f"    → law.go.kr 구조 변경 가능성. elaw API 사용 고려 필요")
            return None

        # 바이너리 모드로 파일 저장
        with open(save_path, 'wb') as f:
            f.write(response.content)

        print(f"    다운로드 완료: {filename} ({len(response.content) / 1024 / 1024:.1f} MB)")
        return save_path

    except requests.exceptions.RequestException as e:
        print(f"    ❌ 다운로드 오류: {e}")
        return None


def extract_text(pdf_path: Path) -> str:
    """
    PDF에서 텍스트 추출

    Args:
        pdf_path: PDF 파일 경로

    Returns:
        추출된 전체 텍스트
    """
    full_text = []

    with pdfplumber.open(pdf_path) as pdf:
        total_pages = len(pdf.pages)

        for page_num, page in enumerate(pdf.pages, 1):
            # 각 페이지에서 텍스트 추출
            text = page.extract_text()
            if text:
                full_text.append(text)

            # 진행상황 표시 (10페이지마다)
            if page_num % 10 == 0:
                print(f"      진행: {page_num}/{total_pages} 페이지")

    # 모든 페이지 텍스트를 개행으로 연결
    result = "\n".join(full_text)
    print(f"      총 {total_pages} 페이지에서 {len(result)} 글자 추출")

    return result


def preprocess_text(text: str) -> str:
    """
    텍스트 전처리 (공백, 개행, 특수문자 정리)

    Args:
        text: 원본 텍스트

    Returns:
        정제된 텍스트
    """
    # 1단계: 여러 공백을 하나의 공백으로
    text = re.sub(r' +', ' ', text)

    # 2단계: 여러 개행을 하나의 개행으로 (3개 이상 개행 → 1개)
    text = re.sub(r'\n\n+', '\n', text)

    # 3단계: 한글 조사/조수사 앞의 공백 제거
    # 예: "식품 등", "법령 을" → "식품등", "법령을"
    text = re.sub(r'([가-힣]) (등|이|가|을|를|에|와|과|으로|으로서|에게|에서|로부터|처럼|처럼|같이|보다|마다)', r'\1\2', text)

    # 4단계: 조항 번호 앞의 과도한 공백 제거
    # 예: "   제1조" → "제1조"
    text = re.sub(r'\n\s+(?=제\d+조)', '\n', text)
    text = re.sub(r'^\s+(?=제\d+조)', '', text, flags=re.MULTILINE)

    # 5단계: 각 줄의 앞뒤 공백 제거
    lines = [line.strip() for line in text.split('\n')]
    text = '\n'.join(lines)

    # 6단계: 빈 줄 제거
    text = '\n'.join([line for line in text.split('\n') if line.strip()])

    return text


def parse_articles(text: str) -> List[Dict]:
    """
    텍스트에서 조항(Article)을 파싱

    Args:
        text: 전처리된 법령 텍스트

    Returns:
        조항 리스트, 각 조항은 {article, section, text} 포함
    """
    articles = []

    # 정규식으로 "제X조" 패턴 찾기
    # 예: 제1조, 제10조, 제100조 등
    article_pattern = r'제(\d+)조'
    matches = list(re.finditer(article_pattern, text))

    if not matches:
        print("    ⚠️  조항을 찾을 수 없습니다")
        return articles

    # 각 조항을 순회하면서 시작부터 다음 조항 이전까지를 추출
    for idx, match in enumerate(matches):
        article_num = int(match.group(1))
        start_pos = match.start()

        # 다음 조항이 있으면 그곳까지, 없으면 문서 끝까지
        if idx + 1 < len(matches):
            end_pos = matches[idx + 1].start()
        else:
            end_pos = len(text)

        # 조항 텍스트 추출
        article_text = text[start_pos:end_pos].strip()

        # 조항 내에서 "제X항" 패턴이 있으면 항(section)으로 분할
        # 예: "제1조제1항", "제1조제2항"
        section_pattern = r'제(\d+)항'
        section_matches = list(re.finditer(section_pattern, article_text))

        if section_matches:
            # 항이 있으면 항별로 분할
            for sec_idx, sec_match in enumerate(section_matches):
                section_num = int(sec_match.group(1))
                sec_start = sec_match.start()

                # 다음 항이 있으면 그곳까지, 없으면 조항 끝까지
                if sec_idx + 1 < len(section_matches):
                    sec_end = section_matches[sec_idx + 1].start()
                else:
                    sec_end = len(article_text)

                section_text = article_text[sec_start:sec_end].strip()

                articles.append({
                    "article": article_num,
                    "section": section_num,
                    "text": section_text
                })
        else:
            # 항이 없으면 전체 조항을 하나의 청크로
            articles.append({
                "article": article_num,
                "section": None,
                "text": article_text
            })

    print(f"      총 {len(articles)}개 조항 파싱 완료")
    return articles


def chunk_articles(articles: List[Dict], max_tokens: int = 500, min_tokens: int = 50) -> List[Dict]:
    """
    조항을 토큰 기준으로 청킹

    Args:
        articles: 조항 리스트
        max_tokens: 최대 토큰 수 (기본 500)
        min_tokens: 최소 토큰 수 (기본 50)

    Returns:
        청크 리스트, 각 청크는 {article, section, text, tokens}
    """
    chunks = []

    for article in articles:
        text = article["text"]
        article_num = article["article"]
        section_num = article["section"]

        # 토큰 추정: 한글 약 1글자 = 1.3토큰
        estimated_tokens = int(len(text) * 1.3)

        # 토큰이 max_tokens를 초과하면 문장 단위로 재분할
        if estimated_tokens > max_tokens:
            # 마침표, 줄바꿈 기준으로 문장 분리
            sentences = re.split(r'(?<=[。.!?])\s+|(?<=\n)', text)
            sentences = [s.strip() for s in sentences if s.strip()]

            current_chunk = ""
            current_tokens = 0

            for sentence in sentences:
                sentence_tokens = int(len(sentence) * 1.3)

                # 현재 청크에 문장을 추가해도 max_tokens 이하면 추가
                if current_tokens + sentence_tokens <= max_tokens:
                    current_chunk += " " + sentence if current_chunk else sentence
                    current_tokens += sentence_tokens
                else:
                    # 현재 청크가 너무 작지 않으면 저장
                    if current_tokens >= min_tokens:
                        chunks.append({
                            "article": article_num,
                            "section": section_num,
                            "text": current_chunk.strip(),
                            "tokens": current_tokens
                        })

                    # 새로운 청크 시작
                    current_chunk = sentence
                    current_tokens = sentence_tokens

            # 마지막 청크 저장
            if current_chunk.strip() and current_tokens >= min_tokens:
                chunks.append({
                    "article": article_num,
                    "section": section_num,
                    "text": current_chunk.strip(),
                    "tokens": current_tokens
                })

        else:
            # 토큰이 적당하면 그대로 저장
            if estimated_tokens >= min_tokens:
                chunks.append({
                    "article": article_num,
                    "section": section_num,
                    "text": text,
                    "tokens": estimated_tokens
                })

    print(f"      청킹 완료: {len(chunks)}개 청크 생성")
    return chunks


def to_json_format(chunks: List[Dict], law_info: Dict, order_start: int = 1) -> List[Dict]:
    """
    청크를 regulations_chunks.jsonl 스키마로 변환

    Args:
        chunks: 청크 리스트
        law_info: 법령 정보 (name, enactment_date, last_revised)
        order_start: 시작 order 번호

    Returns:
        JSON 포맷 청크 리스트
    """
    json_chunks = []
    law_name = law_info["name"]

    # 법령명을 영문 파일명으로 변환
    # 예: "건강기능식품에 관한 법률" → "건강기능식품법"
    law_name_short = law_name.replace("에 관한 법률", "").replace("ㆍ", "").strip()

    # 파일명 생성 (PDF 파일명)
    source_file = f"regulations/{law_name_short}.pdf"

    for idx, chunk in enumerate(chunks, start=1):
        # ID 생성: "{법령명}_{article:03d}_{section:02d}"
        article = chunk["article"]
        section = chunk.get("section") or 0
        chunk_id = f"{law_name_short}_{article:03d}_{section:02d}_{idx:03d}"

        # JSON 객체 생성
        json_chunk = {
            "id": chunk_id,
            "law_name": law_name,
            "article": article,
            "section": chunk.get("section"),
            "subsection": None,
            "item": None,
            "text": chunk["text"],
            "source_file": source_file,
            "enactment_date": law_info["enactment_date"],
            "last_revised": law_info["last_revised"],
            "tokens": chunk["tokens"],
            "order": order_start + idx - 1,
            "parser": "pdf_article_extractor",
            "notes": ""
        }

        json_chunks.append(json_chunk)

    return json_chunks


def main():
    """메인 플로우"""
    print("=" * 60)
    print("Phase 1.5 Step 2: law.go.kr 법령 본문 추출")
    print("=" * 60)

    all_chunks = []
    order = 1

    for law_info in LAWS:
        print(f"\n[{law_info['name']}]")

        # 1단계: PDF 다운로드
        print(f"  → PDF 다운로드 중...")
        pdf_path = download_pdf(law_info)
        if not pdf_path:
            print(f"  ❌ 다운로드 실패")
            continue
        print(f"  ✅ 다운로드 완료: {pdf_path.name}")

        # 2단계: 텍스트 추출
        print(f"  → 텍스트 추출 중...")
        text = extract_text(pdf_path)
        print(f"  ✅ 추출 완료: {len(text)} 글자")

        # 3단계: 전처리
        print(f"  → 텍스트 전처리 중...")
        text = preprocess_text(text)

        # 4단계: 조항 파싱
        print(f"  → 조항 파싱 중...")
        articles = parse_articles(text)
        print(f"  ✅ 조항 파싱 완료: {len(articles)}개")

        # 5단계: 청킹
        print(f"  → 청킹 중...")
        chunks = chunk_articles(articles)
        print(f"  ✅ 청킹 완료: {len(chunks)}개 청크")

        # 6단계: JSON 포맷 변환
        print(f"  → JSON 포맷 변환 중...")
        json_chunks = to_json_format(chunks, law_info, order_start=order)
        all_chunks.extend(json_chunks)
        order += len(json_chunks)
        print(f"  ✅ 변환 완료")

    # 7단계: 기존 regulations_chunks.jsonl에서 스텁 제거, 새 청크 추가
    print(f"\n[통합 단계]")
    print(f"  → 기존 regulations_chunks.jsonl 로드...")

    existing_chunks_path = Path(__file__).parent.parent / "_workspace" / "regulations_chunks.jsonl"
    existing_chunks = []
    with open(existing_chunks_path, 'r', encoding='utf-8') as f:
        for line in f:
            chunk = json.loads(line)
            # 스텁 제외 (parser == "lawgokr_html_stub")
            if chunk.get("parser") != "lawgokr_html_stub":
                existing_chunks.append(chunk)

    print(f"  ✅ 기존 청크 로드: {len(existing_chunks)}개 (스텁 3개 제거)")

    # 8단계: 통합 및 저장
    print(f"  → 청크 통합 중...")
    merged_chunks = existing_chunks + all_chunks

    output_path = Path(__file__).parent.parent / "_workspace" / "regulations_chunks_v2.jsonl"
    with open(output_path, 'w', encoding='utf-8') as f:
        for chunk in merged_chunks:
            f.write(json.dumps(chunk, ensure_ascii=False) + '\n')

    print(f"  ✅ 저장 완료: {output_path.name}")
    print(f"\n[요약]")
    print(f"  - 기존 청크: {len(existing_chunks)}개")
    print(f"  - 신규 청크: {len(all_chunks)}개")
    print(f"  - 총합: {len(merged_chunks)}개")
    print(f"\n✅ Phase 1.5 Step 2 완료!")


if __name__ == "__main__":
    main()
