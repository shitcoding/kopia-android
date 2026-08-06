import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.robolectric.junit5)
    alias(libs.plugins.license.report)
}

/**
 * Attribution for the dependencies the APK actually ships (task-43).
 *
 * Packaging strips META-INF/LICENSE and META-INF/NOTICE (see the `resources` excludes below), so a
 * released binary carries no dependency notices of its own and THIRD_PARTY_NOTICES.md is the only
 * attribution vehicle. Maintaining that list by hand guarantees it drifts from the dependency graph,
 * so it is generated from the release runtime classpath instead -- the exact set that goes into the
 * APK -- and copied into the bundle for the in-app licences screen.
 */
licenseReport {
    configurations = arrayOf("releaseRuntimeClasspath")
    filters = arrayOf(LicenseBundleNormalizer())
    renderers = arrayOf(InventoryMarkdownReportRenderer("DEPENDENCY_LICENSES.md", "KopiaKt Android"))
    outputDir = layout.buildDirectory.dir("reports/licenses").get().asFile.absolutePath
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

// The Apache-2.0 §4(d) and BSD-2 clause-2 notices have to travel WITH the app, not just live in the
// repository: packaging strips dependency notices, so a released APK that only pointed at GitHub
// would be distributing this code without the attribution its licences require. Copied into the
// React bundle so the in-app Licences screen can fetch them from the same virtual origin.
// Attribution for the JavaScript compiled into the bundle. Runs after the vite build, which wipes
// dist/, and writes straight into the bundle's legal directory.
tasks.register<Exec>("generateJsLicenseReport") {
    dependsOn("buildReactAssets")
    workingDir = file("${rootProject.projectDir}/react-ui")
    commandLine("npm", "run", "licenses")
}

tasks.register<Copy>("copyLegalNotices") {
    dependsOn("buildReactAssets", "generateJsLicenseReport", "generateLicenseReport")
    from(rootProject.projectDir) {
        include("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")
    }
    // Generated from the release runtime classpath, i.e. exactly what the APK ships.
    from(layout.buildDirectory.dir("reports/licenses")) {
        include("DEPENDENCY_LICENSES.md")
    }
    into("${rootProject.projectDir}/react-ui/dist/legal")

    doLast {
        // The report links the LICENSE/NOTICE files it extracted from the artifacts, by relative
        // path. Those files are not copied, and the screen renders the document as plain text, so
        // every one of those links is dead: the notices would be named but never reproduced, which
        // is precisely what Apache-2.0 4(d) and the BSD/MIT notice clauses require. Inline them.
        val extracted = layout.buildDirectory.dir("reports/licenses").get().asFile
        val target = file("${rootProject.projectDir}/react-ui/dist/legal/DEPENDENCY_LICENSES.md")
        val sidecars = extracted.walkTopDown()
            .filter { it.isFile && it.name != "DEPENDENCY_LICENSES.md" }
            .sortedBy { it.relativeTo(extracted).path }
            .toList()
        target.appendText(
            buildString {
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("# Licence and notice files distributed with these dependencies")
                appendLine()
                appendLine(
                    "Reproduced verbatim from the artifacts themselves. ${sidecars.size} files.",
                )
                sidecars.forEach { f ->
                    appendLine()
                    appendLine("## ${f.relativeTo(extracted).path}")
                    appendLine()
                    appendLine("```")
                    appendLine(f.readText().trim())
                    appendLine("```")
                }
            },
        )
        logger.lifecycle("[licenses] inlined ${sidecars.size} dependency licence/notice files")
    }
}

tasks.register<Copy>("copyReactAssets") {
    dependsOn("buildReactAssets", "copyLegalNotices")
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
