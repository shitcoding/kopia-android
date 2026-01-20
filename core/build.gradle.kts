plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Cryptography
    implementation(libs.bundles.bouncycastle)

    // Compression
    implementation(libs.bundles.compression)

    // Testing
    testImplementation(libs.bundles.testing.unit)
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
