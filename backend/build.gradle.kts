plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "org.jansathi"
version = "0.0.1-SNAPSHOT"
description = "JanSathi API"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.2.0")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.h2database:h2")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The published rule bundle (written by tools/build-bundle.mjs) is packaged into the jar, so the
// API serves exactly the files the PWA uses for on-device matching.
tasks.processResources {
	from(rootDir.parentFile.resolve("frontend/public/data")) {
		into("bundle")
	}
	// UI strings, so the AI guide asks questions with the same wording as the app.
	from(rootDir.parentFile.resolve("frontend/public/locales")) {
		into("locales")
		exclude("review-status.json")
	}
	// Same banned-phrase lists as the copy lint, for validating assistant answers.
	from(rootDir.parentFile.resolve("tools/banned-phrases.json")) {
		into("copy")
	}
	// What the daily source re-check found, for the reviewers' read-only status page.
	from(rootDir.parentFile.resolve("content/source-status.json")) {
		into("content")
	}
	// The built PWA, when present (npm run build:web:api), so one jar serves app and API.
	from(rootDir.parentFile.resolve("frontend/dist")) {
		into("static")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Shared fixtures and content live outside the Gradle project.
	systemProperty("jansathi.repoRoot", rootDir.parentFile.absolutePath)
	// Tests run on every scheme, published or not, so publishing never changes test results.
	systemProperty("jansathi.include-drafts", "true")
	// Tests send thousands of requests from one address; RateLimitTest turns the limits back on.
	systemProperty("jansathi.web.rate-limit-enabled", "false")
	// PostgresTest runs when CI or a developer points it at a database (docs/OPERATIONS.md §10).
	System.getenv("JANSATHI_TEST_PG_URL")?.let { systemProperty("jansathi.test.pg-url", it) }
	// Tests never touch the local file database.
	systemProperty("spring.datasource.url", "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
}
