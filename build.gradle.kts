plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.gatekeeper"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "com.gatekeeper.ApplicationKt"
}

tasks.register<JavaExec>("runNginxBackfill") {
    group = "application"
    description = "Run the manual nginx Site backfill (use -PbackfillArgs=--dry-run)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.gatekeeper.nginx.NginxBackfillMainKt")
    args = providers.gradleProperty("backfillArgs")
        .orNull
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        .orEmpty()
}

tasks.register<JavaExec>("runMigrations") {
    group = "database"
    description = "Apply pending Flyway database migrations"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.gatekeeper.plugins.DatabaseMigrationMainKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Ktor Server
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.defaultHeaders)
    implementation(ktorLibs.server.autoHeadResponse)
    implementation(ktorLibs.server.requestValidation)
    implementation(ktorLibs.server.di)

    // Ktor Client (for Paystack API calls)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)

    // Docker
    implementation(libs.docker.java)
    implementation(libs.docker.java.transport.httpclient5)

    // Database - Exposed
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    // Redis
    implementation(libs.jedis)

    // Auth
    implementation(libs.jbcrypt)

    // Dotenv
    implementation(libs.dotenv)

    // Logging
    implementation(libs.logback.classic)

    // Test
    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
