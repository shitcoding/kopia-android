plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // KopiaKt modules
    implementation(project(":core"))
    implementation(project(":snapshot"))
    implementation(project(":storage"))

    // Kotlin
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Compression (needed for repository creation)
    implementation(libs.zstd.jni)
    implementation(libs.lz4.java)

    // Cryptography
    implementation(libs.bundles.bouncycastle)

    // Testing
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()

    // Set environment for E2E tests
    environment("KOPIA_BINARY", rootProject.projectDir.resolve("../kopia-go/kopia").absolutePath)

    // E2E tests can take longer
    systemProperty("junit.jupiter.execution.timeout.default", "10m")

    // Increase heap for benchmark tests
    maxHeapSize = "2g"

    // Run E2E tests only when explicitly requested or in CI
    val runE2E = System.getenv("RUN_E2E_TESTS")?.toBoolean() ?: false
    val isCI = System.getenv("CI")?.toBoolean() ?: false

    onlyIf {
        runE2E || isCI || project.hasProperty("e2e")
    }

    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true
    }
}

// Dedicated task for running only benchmark tests
tasks.register<Test>("benchmark") {
    useJUnitPlatform {
        includeTags("benchmark")
    }

    description = "Runs performance benchmark tests"
    group = "verification"

    environment("KOPIA_BINARY", rootProject.projectDir.resolve("../kopia-go/kopia").absolutePath)

    // Benchmark tests need more time and memory
    systemProperty("junit.jupiter.execution.timeout.default", "30m")
    maxHeapSize = "4g"

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
