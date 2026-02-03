plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.kopiaKt.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.kopiaKt.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/INDEX.LIST"
            // Merge Netty version files
            pickFirsts += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // Fix for Hilt JavaPoet compatibility
    ksp("com.squareup:javapoet:1.13.0")

    // KopiaKt modules (exclude zstd-jni JAR, android module provides AAR)
    implementation(project(":core")) {
        exclude(group = "com.github.luben", module = "zstd-jni")
    }
    implementation(project(":snapshot")) {
        exclude(group = "com.github.luben", module = "zstd-jni")
    }
    implementation(project(":storage")) {
        exclude(group = "com.github.luben", module = "zstd-jni")
    }
    implementation(project(":android"))

    // Flutter UI module (AAR built via `flutter build aar` in flutter_ui/)
    debugImplementation("org.kopiaKt.flutter_ui:flutter_debug:1.0")
    releaseImplementation("org.kopiaKt.flutter_ui:flutter_release:1.0")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.bundles.testing.unit)
    androidTestImplementation(libs.bundles.testing.android)
}
