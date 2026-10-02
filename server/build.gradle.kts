plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("cloud.trotter.census.server.MainKt")
}

dependencies {
    implementation("cloud.trotter.census:contract:0.0.0-local")
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.html.jvm)
    implementation(libs.jul.to.slf4j)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Docker Engine 29 refuses API clients below 1.40; Testcontainers' docker-java still negotiates from
    // 1.32, so pin the API version it announces (harmless on CI runners' Docker 28; override via env).
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.44")
    systemProperty("census.mainSource", layout.projectDirectory.dir("src/main").asFile.absolutePath)
    // Gradle's worker java.class.path contains only its bootstrap, not the test runtime.
    systemProperty("census.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}
