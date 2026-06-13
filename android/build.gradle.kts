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

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/**",
                "META-INF/io.netty.versions.properties"
            )
            pickFirsts += listOf(
                "META-INF/services/javax.xml.stream.*",
                "META-INF/services/com.fasterxml.woodstox.*"
            )
        }
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
    // Note: Orchestrator disabled for simpler test execution
    // @Suppress("UnstableApiUsage")
    // testOptions {
    //     execution = "ANDROIDX_TEST_ORCHESTRATOR"
    // }
}

dependencies {
    // Module dependencies (exclude zstd JAR, use AAR instead for Android native support)
    implementation(project(":core")) {
        exclude(group = "com.github.luben", module = "zstd-jni")
    }
    implementation(project(":snapshot")) {
        exclude(group = "com.github.luben", module = "zstd-jni")
    }
    implementation(project(":storage")) {
        exclude(group = "com.github.luben", module = "zstd-jni")
    }

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

    // Compression - use AAR for Android native support
    implementation(libs.lz4.java)
    implementation("com.github.luben:zstd-jni:1.5.6-8@aar")

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
    maxParallelForks = 1
    maxHeapSize = "1024m"
}
