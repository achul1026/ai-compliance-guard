// :api — Spring Boot 실행 모듈. REST 엔드포인트, 설정 진입점.
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":infra"))
    implementation(project(":rag"))
    implementation(project(":agent"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // P1-8: Lombok (로깅, 데이터 클래스)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

tasks.named<Jar>("jar") {
    enabled = false
}
