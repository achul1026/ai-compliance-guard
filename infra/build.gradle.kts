// :infra — DB 접근, Flyway 마이그레이션 리소스, 외부 API 클라이언트.
dependencies {
    implementation(project(":common"))

    // P1-4: Spring Data JPA + PostgreSQL
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Flyway 마이그레이션
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // P1-7: pgvector (JDBC 타입)
    implementation("org.postgresql:postgresql:42.7.0")

    // Logging
    implementation("org.springframework.boot:spring-boot-starter-logging")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
