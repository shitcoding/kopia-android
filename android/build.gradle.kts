plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.robolectric.junit5)
}

android {
    namespace = "org.kopiaKt.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }

        // Configure instrumented tests
        animationsDisabled = true

        // Manage device test options
        managedDevices {
            localDevices {
                // For local testing with managed devices
                // These can be uncommented when running with managed devices
                // create("pixel2api30") {
                //     device = "Pixel 2"
                //     apiLevel = 30
                //     systemImageSource = "aosp"
                // }
            }
        }
    }

    // Configure test execution timeout
    @Suppress("UnstableApiUsage")
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

dependencies {
    // Module dependencies
    implementation(project(":core"))
    implementation(project(":snapshot"))
    implementation(project(":storage"))

    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.coroutines)
    implementation(libs.kotlinx.serialization.json)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    // Cryptography (Android)
    implementation(libs.bundles.bouncycastle)

    // Compression
    implementation(libs.bundles.compression)

    // Unit Testing
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.robolectric)
    testImplementation(libs.robolectric.junit5)
    testImplementation(libs.androidx.work.testing)
    testRuntimeOnly(libs.junit5.engine)

    // Android Instrumented Testing
    androidTestImplementation(libs.bundles.testing.android)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestUtil("androidx.test:orchestrator:1.5.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
