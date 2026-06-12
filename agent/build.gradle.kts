// :agent — Multi-Agent 파이프라인(Auditor/Critique/Remediator), 오케스트레이션.
// ADR-007: LangChain4j는 LLM 호출 계층만 담당, 오케스트레이션은 자체 상태머신.
dependencies {
    implementation(project(":common"))
    implementation(project(":rag"))

    // Spring DI + 로깅
    implementation("org.springframework.boot:spring-boot-starter")

    // P2-2: 에이전트 JSON 계약 파싱
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // ADR-006: Gemini Flash (무료 티어) LLM 클라이언트
    implementation("dev.langchain4j:langchain4j:1.16.2")
    implementation("dev.langchain4j:langchain4j-google-ai-gemini:1.16.2")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
