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

    // AWS S3
    implementation(libs.aws.s3)
    implementation(libs.aws.netty)

    // WebDAV
    implementation(libs.sardine)

    // SFTP
    implementation(libs.sshj)

    // Testing
    testImplementation(libs.bundles.testing.unit)
    testImplementation(project(":core", "testArchive"))
    testRuntimeOnly(libs.junit5.engine)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
