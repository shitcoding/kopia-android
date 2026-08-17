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
    testImplementation(project(":core", "testArchive"))

    // Testcontainers (Docker-based integration tests for remote backends)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit5)

    // Storage backend runtime dependencies (needed for remote backend tests)
    testImplementation(libs.aws.s3)
    testImplementation(libs.aws.url.connection)
    testImplementation(libs.stax.api)
    testImplementation(libs.woodstox.core)
    testImplementation(libs.okhttp)
    testImplementation(libs.sshj)
}

tasks.test {
    useJUnitPlatform()

    // Cross-compat tests resolve the Go `kopia` binary via KOPIA_BINARY (inherited from the
    // environment) or PATH (see KopiaCliRunner.defaultKopiaBinary). Do NOT hard-code a path — the
    // old ../kopia-go/kopia vendor dir was purged in the 2026-06-14 restructure. Install the binary
    // with `brew install kopia`, or export KOPIA_BINARY=/path/to/kopia.
    System.getenv("KOPIA_BINARY")?.let { environment("KOPIA_BINARY", it) }
    // Make `-Pe2e` reach the test JVM: isE2EEnabled() reads the `e2e` system property.
    if (project.hasProperty("e2e")) systemProperty("e2e", "true")

    // E2E tests can take longer
    systemProperty("junit.jupiter.execution.timeout.default", "10m")

    maxHeapSize = "1536m"

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

/**
 * The Go-interop tests only — the ones that need the `kopia` binary and nothing else.
 *
 * This exists so CI can watch the oracle. `:e2e:test` pulls Testcontainers for the S3/SFTP/WebDAV
 * cases, which is why `ci.yml` excluded the whole module — and that quietly took the non-Docker
 * cross-compat tests down with it, so the suite sat entirely red for an unknown length of time
 * without anyone finding out (task-73). Splitting the Docker-free subset out means the byte-level
 * compatibility promise this project is built on gets checked on every push.
 *
 * `remote-backend` is excluded because it needs Docker; `benchmark` because timings on a shared
 * runner are noise, and it has its own task above.
 */
tasks.register<Test>("goInteropTest") {
    useJUnitPlatform {
        includeTags("cross-compat")
        excludeTags("remote-backend", "benchmark")
    }

    description = "Runs the Go cross-compatibility tests (needs the kopia binary, not Docker)"
    group = "verification"

    System.getenv("KOPIA_BINARY")?.let { environment("KOPIA_BINARY", it) }
    systemProperty("e2e", "true")

    // These are integration tests over real files and a real subprocess. They run in seconds now
    // that they no longer deadlock; a generous cap is a backstop, not a budget.
    systemProperty("junit.jupiter.execution.timeout.default", "10m")
    maxHeapSize = "1536m"

    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

// Dedicated task for running only benchmark tests
tasks.register<Test>("benchmark") {
    useJUnitPlatform {
        includeTags("benchmark")
    }

    description = "Runs performance benchmark tests"
    group = "verification"

    // Resolve the Go `kopia` binary via KOPIA_BINARY (env) or PATH; never the purged vendor path.
    System.getenv("KOPIA_BINARY")?.let { environment("KOPIA_BINARY", it) }
    if (project.hasProperty("e2e")) systemProperty("e2e", "true")

    // Benchmark tests need more time and memory
    systemProperty("junit.jupiter.execution.timeout.default", "30m")
    maxHeapSize = "2g"

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
