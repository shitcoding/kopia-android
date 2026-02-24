plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Module dependencies
    implementation(project(":core"))

    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Networking (for remote storage backends)
    implementation(libs.okhttp)
    implementation(libs.bundles.ktor)

    // AWS S3 (using UrlConnectionHttpClient for Android compatibility)
    implementation(libs.aws.s3)
    implementation(libs.aws.url.connection)
    implementation(libs.stax.api)
    implementation(libs.woodstox.core)

    // WebDAV (uses OkHttp, already declared above)

    // SFTP
    implementation(libs.sshj)

    // Testing
    testImplementation(libs.bundles.testing.unit)
    testImplementation(project(":core", "testArchive"))
    testRuntimeOnly(libs.junit5.engine)

    // Testcontainers (Docker-based integration tests)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit5)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    maxParallelForks = 2
    maxHeapSize = "1024m"
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests requiring Docker"
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    maxParallelForks = 1
    maxHeapSize = "1536m"
    setForkEvery(1)
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
