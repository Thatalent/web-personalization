plugins {
    id("org.springframework.boot") version "3.3.3"
    id("io.spring.dependency-management") version "1.1.5"
    java
}

group = "com.resonate"
version = "0.1.0"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(platform("org.springframework.ai:spring-ai-bom:1.0.0-M5"))
    implementation("org.springframework.ai:spring-ai-ollama-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.pgvector:pgvector:0.1.6")
    implementation("org.springframework.boot:spring-boot-starter-mustache")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.networknt:json-schema-validator:1.4.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.ai:spring-ai-pgvector-store-spring-boot-starter")
}

tasks.withType<Test> { useJUnitPlatform() }
