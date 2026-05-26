// :infra — DB 접근, Flyway 마이그레이션 리소스, 외부 API 클라이언트.
dependencies {
    implementation(project(":common"))

    // Phase 0에서는 Flyway 리소스만 보유. Spring Boot 의존성은 :api에서 주입 시 활용.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
}
