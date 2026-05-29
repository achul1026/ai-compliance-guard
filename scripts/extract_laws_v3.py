#!/usr/bin/env python3
"""
Phase 1.5 Step 2: 법령 3종 PDF에서 조항 단위로 추출 및 청킹
- 건강기능식품법, 식품표시광고법, 화장품법
- 조항 단위 청킹 (300~500 토큰)
- regulations_chunks.jsonl에 추가
"""

import json
import re
from pathlib import Path
from dataclasses import dataclass, asdict
from typing import List, Optional
import pdfplumber
from datetime import datetime

@dataclass
class RegulationChunk:
    id: str
    law_name: str
    article: Optional[str]
    section: Optional[str]
    subsection: Optional[str]
    item: Optional[str]
    text: str
    source_file: str
    enactment_date: str
    last_revised: str
    tokens: int
    order: int
    parser: str
    notes: Optional[str] = None

class LawExtractor:
    def __init__(self, pdf_path: str, law_name: str):
        self.pdf_path = pdf_path
        self.law_name = law_name
        self.chunks: List[RegulationChunk] = []
        self.enactment_date = "2024-01-01"  # 기본값
        self.last_revised = datetime.now().strftime("%Y-%m-%d")

    def extract_text_from_pdf(self) -> str:
        """PDF에서 전체 텍스트 추출"""
        full_text = []
        with pdfplumber.open(self.pdf_path) as pdf:
            for page in pdf.pages:
                text = page.extract_text()
                if text:
                    full_text.append(text)
        return "\n".join(full_text)

    def tokenize(self, text: str) -> int:
        """간단한 토큰 카운트 (약 4자 = 1토큰)"""
        return len(text) // 4

    def parse_chapters_and_articles(self, full_text: str):
        """장(Chapter)과 조(Article) 파싱"""

        # 제N장 형식 찾기
        chapter_pattern = r'제(\d+)장\s+([^\n]+)\n'
        # 제N조 형식 찾기
        article_pattern = r'제(\d+)조\(([^)]+)\)(.*?)(?=제\d+조|$)'

        current_order = 1

        # 각 조항 추출
        for match in re.finditer(article_pattern, full_text, re.DOTALL):
            article_num = match.group(1)
            article_title = match.group(2).strip()
            article_content = match.group(3).strip()

            if not article_content:
                continue

            # 조항 내용 정제
            article_content = self._clean_text(article_content)

            # 너무 길면 항(項)별로 나누기
            paragraphs = self._split_paragraphs(article_content)

            for i, para in enumerate(paragraphs):
                if not para.strip():
                    continue

                tokens = self.tokenize(para)

                # ID 생성: 법령명_조항_순서
                chunk_id = f"{self.law_name}_{article_num}_{i+1:03d}"

                chunk = RegulationChunk(
                    id=chunk_id,
                    law_name=self.law_name,
                    article=f"제{article_num}조",
                    section=None,
                    subsection=None,
                    item=None,
                    text=f"[{self.law_name} 제{article_num}조({article_title})]\n{para}",
                    source_file=Path(self.pdf_path).name,
                    enactment_date=self.enactment_date,
                    last_revised=self.last_revised,
                    tokens=tokens,
                    order=current_order,
                    parser="pdf_articles",
                    notes=None
                )

                self.chunks.append(chunk)
                current_order += 1

    def _clean_text(self, text: str) -> str:
        """텍스트 정제"""
        # 여러 줄바꿈을 하나로
        text = re.sub(r'\n\s*\n+', '\n\n', text)
        # 불필요한 공백 제거
        text = re.sub(r' +', ' ', text)
        return text.strip()

    def _split_paragraphs(self, text: str, target_tokens: int = 400) -> List[str]:
        """텍스트를 단락 단위로 분할 (300~500 토큰)"""
        paragraphs = text.split('\n\n')

        # 단락이 너무 크면 다시 분할
        result = []
        current = []
        current_tokens = 0

        for para in paragraphs:
            para_tokens = self.tokenize(para)

            if current_tokens + para_tokens > target_tokens and current:
                # 현재 청크 저장
                result.append('\n\n'.join(current))
                current = [para]
                current_tokens = para_tokens
            else:
                current.append(para)
                current_tokens += para_tokens

        if current:
            result.append('\n\n'.join(current))

        return result

    def process(self):
        """전체 처리"""
        print(f"\n{'='*60}")
        print(f"처리 중: {self.law_name}")
        print(f"{'='*60}")

        full_text = self.extract_text_from_pdf()
        print(f"✓ PDF 파싱 완료 ({len(full_text)} 글자)")

        self.parse_chapters_and_articles(full_text)
        print(f"✓ {len(self.chunks)}개 청크 생성")

        return self.chunks

def main():
    laws_dir = Path("/Users/chul/Library/Mobile Documents/iCloud~md~obsidian/Documents/aiden/raw/laws")
    output_path = Path("/Users/chul/projects/ai-compliance-guard/_workspace/regulations_chunks_v3.jsonl")

    law_files = [
        (laws_dir / "건강기능식품법.pdf", "건강기능식품에 관한 법률"),
        (laws_dir / "식품표시광고법.pdf", "식품 등의 표시·광고에 관한 법률"),
        (laws_dir / "화장품법.pdf", "화장품법"),
    ]

    all_chunks = []

    for pdf_path, law_name in law_files:
        if not pdf_path.exists():
            print(f"⚠ 파일 없음: {pdf_path}")
            continue

        extractor = LawExtractor(str(pdf_path), law_name)
        chunks = extractor.process()
        all_chunks.extend(chunks)

    # 기존 청크 로드
    existing_chunks_path = Path("/Users/chul/projects/ai-compliance-guard/_workspace/regulations_chunks.jsonl")
    existing_chunks = []

    if existing_chunks_path.exists():
        with open(existing_chunks_path) as f:
            existing_chunks = [json.loads(line) for line in f if line.strip()]

    # 스텁 청크 제거 (법령 3종)
    stub_law_names = ["건강기능식품에 관한 법률", "식품 등의 표시·광고에 관한 법률", "화장품법"]
    filtered_chunks = [
        chunk for chunk in existing_chunks
        if chunk.get('law_name') not in stub_law_names or not chunk.get('text', '').startswith('[스텁]')
    ]

    print(f"\n✓ 기존 청크: {len(existing_chunks)}개 → 스텁 제거 후: {len(filtered_chunks)}개")

    # 통합
    final_chunks = filtered_chunks + [asdict(c) for c in all_chunks]

    # 저장
    with open(output_path, 'w', encoding='utf-8') as f:
        for i, chunk in enumerate(final_chunks, 1):
            chunk['order'] = i  # 순서 재설정
            f.write(json.dumps(chunk, ensure_ascii=False) + '\n')

    print(f"✓ 최종 청크: {len(final_chunks)}개")
    print(f"✓ 저장 위치: {output_path}")

    # 요약 통계
    print(f"\n{'='*60}")
    print("요약 통계")
    print(f"{'='*60}")
    law_stats = {}
    for chunk in final_chunks:
        law = chunk.get('law_name', 'unknown')
        law_stats[law] = law_stats.get(law, 0) + 1

    for law, count in sorted(law_stats.items()):
        print(f"  {law}: {count}개")

    print(f"\n  총합: {len(final_chunks)}개")

if __name__ == "__main__":
    main()
