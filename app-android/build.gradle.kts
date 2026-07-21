plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.robolectric.junit5)
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

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("int", "SCRYPT_N", "1024")
            buildConfigField("int", "SCRYPT_R", "8")
            buildConfigField("int", "SCRYPT_P", "1")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("int", "SCRYPT_N", "65536")
            buildConfigField("int", "SCRYPT_R", "8")
            buildConfigField("int", "SCRYPT_P", "1")
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
            pickFirsts += "META-INF/services/javax.xml.stream.*"
            pickFirsts += "META-INF/services/com.fasterxml.woodstox.*"
            excludes += "META-INF/io.netty.versions.properties"
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

    // WebView
    implementation("androidx.webkit:webkit:1.12.1")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // BouncyCastle (full provider to replace Android's stripped-down built-in)
    implementation(libs.bouncycastle.provider)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.robolectric)
    testImplementation(libs.robolectric.junit5)
    testRuntimeOnly(libs.junit5.engine)
    androidTestImplementation(libs.bundles.testing.android)
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxParallelForks = 1
    maxHeapSize = "1024m"
}

// Task to build React assets and copy to Android assets folder
tasks.register<Exec>("buildReactAssets") {
    workingDir = file("${rootProject.projectDir}/react-ui")
    commandLine("npm", "install")
    doLast {
        exec {
            workingDir = file("${rootProject.projectDir}/react-ui")
            commandLine("npm", "run", "build")
        }
    }
}

tasks.register<Copy>("copyReactAssets") {
    dependsOn("buildReactAssets")
    from("${rootProject.projectDir}/react-ui/dist")
    into("$projectDir/src/main/assets/react")
}

// Only run React build if react-ui exists and has package.json
tasks.named("preBuild") {
    val reactUiDir = file("${rootProject.projectDir}/react-ui")
    if (reactUiDir.exists() && file("$reactUiDir/package.json").exists()) {
        dependsOn("copyReactAssets")
    }
}
