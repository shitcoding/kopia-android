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
 * released binary carries no dependency notices of its own and the generated report is the only
 * attribution vehicle. Maintaining that list by hand guarantees it drifts from the dependency graph,
 * so it is generated from the release runtime classpath instead -- the exact set that goes into the
 * APK -- and copied into the bundle for the in-app licences screen, beside the root LICENSE and
 * NOTICE.
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
    // Lets a bridge test drive a real backup task end to end, with a stand-in worker in place of a
    // real upload -- the only way to exercise the source -> running-task join the dashboard reads.
    testImplementation(libs.androidx.work.testing)
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

/**
 * One dependency as the generated licence report describes it.
 *
 * @property licenceFiles the licence/notice files the plugin extracted from inside the artifact.
 *   Whether one of these is a LICENCE, not what the POM declares, is what decides whether a notice
 *   has to be supplied by hand.
 */
data class ReportedArtifact(
    val group: String,
    val name: String,
    val licences: List<String>,
    val licenceFiles: List<String>,
) {
    val coordinates get() = "$group:$name"

    /**
     * True when one of the extracted files is the artifact's licence rather than something beside
     * it. okhttp, for one, extracts only `okhttp3/internal/publicsuffix/NOTICE` -- a data file's
     * notice, not okhttp's terms -- so "something was extracted" would let a non-Apache artifact
     * satisfy this gate while shipping no licence text at all.
     */
    val hasLicenceFile get() = licenceFiles.any { path ->
        val file = path.substringAfterLast('/').uppercase()
        file.startsWith("LICEN") || file.startsWith("COPYING")
    }
}

/**
 * Licences that need nothing reproduced per artifact.
 *
 * Apache-2.0 is the bulk of the classpath. Its text is identical for every artifact and travels in
 * the APK as the repository's own root `LICENSE`, which `copyLegalNotices` puts in the bundle and
 * the licences screen shows as its own tab; §4(d) then asks for a NOTICE file to be reproduced only
 * where the artifact supplies one, and every artifact on this classpath that supplies one had it
 * extracted and inlined. MIT-0 is MIT with the attribution clause removed, which is the whole point
 * of that licence.
 *
 * Names are matched exactly, against what `LicenseBundleNormalizer` leaves in the report. That is
 * deliberately brittle: a plugin upgrade that renames "Apache License, Version 2.0" to "Apache-2.0"
 * turns 165 artifacts into gaps and stops the build, which is the safe direction to fail in.
 *
 * Anything not here carries a copyright holder's name, so its own text has to travel with the APK.
 * Adding a licence is a legal judgement about that licence, not a way to quieten a red build.
 */
val attributionFreeLicences = setOf(
    "Apache License, Version 2.0",
    "MIT-0", // MIT No Attribution (org.reactivestreams:reactive-streams)
)

/**
 * Artifacts exempted by coordinate rather than by licence.
 *
 * `net.i2p.crypto:eddsa` is dedicated to the public domain under CC0 1.0, which waives attribution.
 * It is listed here rather than by licence name because the report calls it "Creative Commons Legal
 * Code" -- the heading of EVERY Creative Commons legal text, CC-BY included, which very much does
 * require attribution. Exempting that string would quietly exempt the next CC-BY dependency too.
 */
val noticeFreeArtifacts = setOf(
    "net.i2p.crypto:eddsa",
)

/**
 * The override file names an artifact may be covered by, in the order the error message offers
 * them: its own, then one for the whole group where a single notice covers every artifact in it
 * (`org.bouncycastle.txt` covers bcprov, bcpkix and bcutil, whose licence text is one text).
 *
 * The group form is a real ceiling: a new artifact in a group that already has an override inherits
 * it without anyone looking at the new one. `copyLegalNotices` prints what each override covered so
 * that inheritance is at least visible.
 */
fun overrideNamesFor(artifact: ReportedArtifact) = listOf(
    "${artifact.group}-${artifact.name}.txt",
    "${artifact.group}.txt",
)

/**
 * Reads the dependency-license-report plugin's markdown back into artifacts.
 *
 * Parsing the plugin's own output rather than resolving the configuration again keeps this reading
 * exactly what the shipped document says, and needs no second view of the dependency graph.
 */
fun parseLicenceReport(report: File): List<ReportedArtifact> {
    if (!report.isFile) return emptyList()
    val header = Regex("""^\*\*\d+\*\* \*\*Group:\*\* `([^`]+)` \*\*Name:\*\* `([^`]+)`""")
    // Extracted files are rendered as markdown links to a path inside the artifact's jar. The first
    // sits on the "Embedded license files" line; any others follow as indented list items, which
    // carry no marker of their own -- only a header line ever switches artifact.
    val embedded = Regex("""\[([^\]]+\.(?:jar|aar)/[^\]]+)\]""")
    val artifacts = mutableListOf<ReportedArtifact>()
    var group = ""
    var name = ""
    var licences = mutableListOf<String>()
    var files = mutableListOf<String>()

    fun flush() {
        if (name.isNotEmpty()) {
            artifacts += ReportedArtifact(group, name, licences.distinct(), files.distinct())
        }
    }

    report.forEachLine { line ->
        val match = header.find(line)
        if (match != null) {
            flush()
            group = match.groupValues[1]
            name = match.groupValues[2]
            licences = mutableListOf()
            files = mutableListOf()
            return@forEachLine
        }
        embedded.findAll(line).forEach { files += it.groupValues[1] }
        for (marker in listOf("**POM License**: ", "**Manifest License**: ")) {
            if (marker in line) {
                licences += line.substringAfter(marker)
                    .substringBefore(" - [")
                    .removeSuffix(" (Not Packaged)")
                    .trim()
            }
        }
    }
    flush()
    return artifacts
}

/**
 * Fails the build when an artifact the APK ships has no notice anywhere.
 *
 * THE GAP THIS CLOSES: the JavaScript generator has failed the build on a missing notice since
 * task-47, and the Gradle half had no equivalent, so a new Java dependency whose jar embeds no
 * licence file regressed in silence -- which is exactly how zstd-jni and Bouncy Castle went
 * unnoticed until someone read the report by hand.
 *
 * Its own task, depending only on the report, so a red light costs seconds rather than the npm
 * install and vite build that `copyLegalNotices` drags behind it.
 *
 * **Known ceiling: the plugin extracts from jars only.** Five androidx AARs carry an Apache-2.0
 * `LICENSE.txt` at `META-INF/androidx/.../LICENSE.txt` that never appears in the report, and an AAR
 * shipping a genuine NOTICE would be missed the same way. Harmless while every such artifact is
 * Apache-2.0 (whose text the APK carries anyway), and structurally invisible to this gate.
 */
tasks.register("verifyLegalNotices") {
    dependsOn("generateLicenseReport")
    doLast {
        val report = layout.buildDirectory.file("reports/licenses/DEPENDENCY_LICENSES.md").get().asFile
        val overrideDir = file("${rootProject.projectDir}/legal/notice-overrides")
        val shipped = parseLicenceReport(report)

        // A parse that finds nothing would silently approve everything -- this gate would pass an
        // APK with no notices at all. The plugin's markdown format is what it reads, so a plugin
        // upgrade that changes it has to be noticed here rather than in a licence complaint.
        check(shipped.isNotEmpty()) {
            "[licenses] could not read a single artifact out of ${report.name}; the report format " +
                "has changed and the missing-notice gate is no longer checking anything"
        }

        val gaps = shipped.filter { artifact ->
            if (artifact.hasLicenceFile || artifact.coordinates in noticeFreeArtifacts) return@filter false
            // EVERY declared licence must be attribution-free, not merely one of them: an artifact
            // whose POM says Apache-2.0 and whose manifest says BSD-2 needs the BSD copyright line
            // reproduced, and an artifact that declares nothing at all is worse than either.
            val exempt = artifact.licences.isNotEmpty() &&
                artifact.licences.all { it in attributionFreeLicences }
            !exempt && overrideNamesFor(artifact).none { File(overrideDir, it).isFile }
        }
        // The renderer repeats an artifact under each licence it declares, so the same coordinates
        // can arrive twice; naming it twice in the error would read as two problems.
        val distinctGaps = gaps.distinctBy { it.coordinates }
        if (distinctGaps.isNotEmpty()) {
            error(
                buildString {
                    appendLine(
                        "[licenses] ${distinctGaps.size} artifact(s) on the release runtime classpath ship " +
                            "no licence file and have no notice in legal/notice-overrides/:",
                    )
                    distinctGaps.forEach { a ->
                        val declared = a.licences.ifEmpty { listOf("no licence declared at all") }
                        appendLine("  ${a.coordinates}  (${declared.joinToString("; ")})")
                        appendLine("      -> add ${overrideNamesFor(a).joinToString(" or ")}")
                    }
                    append("Take each one's LICENSE from the project's own repository; see that ")
                    append("directory's README.")
                },
            )
        }
        logger.lifecycle("[licenses] ${shipped.size} artifacts checked, every one accounted for")
    }
}

tasks.register<Copy>("copyLegalNotices") {
    dependsOn("buildReactAssets", "generateJsLicenseReport", "generateLicenseReport", "verifyLegalNotices")
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
        // Some artifacts declare a licence and ship no licence file, so there is nothing to
        // extract. Their notices are kept verbatim under legal/notice-overrides/ -- see that
        // directory's README for why they cannot be reconstructed from a template.
        val overrideDir = file("${rootProject.projectDir}/legal/notice-overrides")
        val javaOverrides = overrideDir.listFiles { f: File -> f.isFile && f.name.endsWith(".txt") }
            ?.filterNot { it.name.startsWith("npm-") } // those belong to the JavaScript report
            ?.sortedBy { it.name }
            .orEmpty()

        // Already checked by verifyLegalNotices, which this task depends on; re-read here to decide
        // which overrides still belong to something shipped.
        val shipped = parseLicenceReport(File(extracted, "DEPENDENCY_LICENSES.md"))

        // Only the overrides that belong to something this APK actually ships. Appending every file
        // in the directory means a dropped dependency's notice ships forever, in a document whose
        // whole value is that it describes what is in the APK.
        val covers = javaOverrides.associateWith { f ->
            shipped.filter { a -> f.name in overrideNamesFor(a) }.map { it.coordinates }.distinct()
        }
        val overrides = javaOverrides.filter { covers.getValue(it).isNotEmpty() }
        covers.forEach { (f, covered) ->
            if (covered.isEmpty()) {
                logger.warn(
                    "[licenses] ${f.name} matches nothing on the release runtime classpath and was " +
                        "NOT included; delete it if that dependency is gone for good.",
                )
            } else {
                // Printed because a group-wide override silently adopts new members of its group;
                // this is where that shows up.
                logger.lifecycle("[licenses] ${f.name} covers ${covered.joinToString(", ")}")
            }
        }

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
                if (overrides.isNotEmpty()) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                    appendLine("# Notices for dependencies that ship no licence file")
                    appendLine()
                    appendLine(
                        "Taken verbatim from each project's own repository, because the published " +
                            "artifact contains none. ${overrides.size} notices.",
                    )
                    overrides.forEach { f ->
                        appendLine()
                        appendLine("## ${f.name.removeSuffix(".txt")}")
                        appendLine()
                        appendLine("```")
                        appendLine(f.readText().trim())
                        appendLine("```")
                    }
                }
            },
        )
        logger.lifecycle(
            "[licenses] inlined ${sidecars.size} extracted files and ${overrides.size} overrides",
        )
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
