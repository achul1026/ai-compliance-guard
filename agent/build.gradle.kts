// :agent — Multi-Agent 파이프라인(Auditor/Critique/Remediator), 오케스트레이션.
dependencies {
    implementation(project(":common"))
    implementation(project(":rag"))
    // Phase 2에서 LangChain4j 또는 LangGraph 의존성 추가 예정 (D6 확정 후).
}
