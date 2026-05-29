#!/usr/bin/env python3
"""
regulations_chunks.jsonl을 PostgreSQL에 적재
"""

import json
import psycopg2
from pathlib import Path

JSONL_PATH = Path(__file__).parent.parent / "_workspace" / "regulations_chunks.jsonl"

# PostgreSQL 연결 설정
DB_CONFIG = {
    "host": "localhost",
    "port": 5432,
    "user": "postgres",
    "password": "postgres",
    "database": "compliance_db"
}


def load_chunks():
    """JSONL 파일을 PostgreSQL에 적재"""
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        cur = conn.cursor()

        # 기존 데이터 제거 (선택)
        # cur.execute("DELETE FROM regulations;")

        loaded = 0
        with open(JSONL_PATH, 'r', encoding='utf-8') as f:
            for line in f:
                chunk = json.loads(line)

                # RegulationEntity 스키마에 맞게 매핑
                law_name = chunk.get("law_name")
                article = chunk.get("article")
                section = chunk.get("section")
                text = chunk.get("text")

                # article_number: 정수를 "제X조" 형식으로 변환
                article_number = f"제{article}조" if article else "N/A"

                # paragraph_number: 정수를 "제X항" 형식으로 변환
                paragraph_number = f"제{section}항" if section else None

                # regulations 테이블에 INSERT (RegulationEntity 스키마)
                cur.execute("""
                    INSERT INTO regulations (
                        source, law_name, article_number, paragraph_number,
                        chunk_text, metadata, version
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                """, (
                    "pdf_article_extractor",  # source
                    law_name,
                    article_number,
                    paragraph_number,
                    text,  # chunk_text
                    json.dumps({  # metadata
                        "id": chunk.get("id"),
                        "tokens": chunk.get("tokens"),
                        "parser": chunk.get("parser"),
                        "notes": chunk.get("notes")
                    }),
                    "2026-05-29"  # version
                ))

                loaded += 1
                if loaded % 100 == 0:
                    print(f"  진행: {loaded} 청크 적재...")

        conn.commit()
        cur.close()
        conn.close()

        print(f"✅ 적재 완료: {loaded}개 청크")

    except psycopg2.Error as e:
        print(f"❌ 데이터베이스 오류: {e}")
        return False

    return True


if __name__ == "__main__":
    print("=== Regulations 적재 시작 ===")
    if load_chunks():
        print("\n✅ Phase 1.5 Step 3 준비 완료")
        print("다음: 임베딩 파이프라인 실행")
    else:
        print("\n❌ 적재 실패")
