// :rag — Hybrid 검색(BM25 + Vector), Re-ranking, 임베딩 호출.
dependencies {
    implementation(project(":common"))
    implementation(project(":infra"))
    // Phase 1에서 임베딩/검색 라이브러리 추가 예정 (D2~D4 확정 후).
}
