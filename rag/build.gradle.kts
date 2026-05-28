// :rag — Hybrid 검색(BM25 + Vector), Re-ranking, 임베딩 호출.
dependencies {
    implementation(project(":common"))
    implementation(project(":infra"))

    // P1-5: Upstage REST 클라이언트
    implementation("org.springframework.boot:spring-boot-starter-web")

    // P1-6 & P1-7: Database 접근 (JDBC, JdbcTemplate)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // P1-7: Logging & Lombok
    implementation("org.springframework.boot:spring-boot-starter-logging")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
