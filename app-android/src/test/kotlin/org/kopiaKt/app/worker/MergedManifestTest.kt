package org.kopiaKt.app.worker

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.kopiaKt.android.identity.SourceIdentityStore
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Locks two WorkManager facts that only exist in the MERGED manifest (the one that actually ships),
 * both of which fail silently at runtime rather than at build time. The merged manifest is located
 * through the AGP unit-test config that Robolectric also reads.
 */
class MergedManifestTest {

    /**
     * `work-runtime` declares `SystemForegroundService` without an `android:foregroundServiceType`.
     * Android 14+ refuses to start a foreground service whose type is not in the manifest, so
     * without our `tools:node="merge"` override every backup dies at `setForeground`.
     */
    @Test
    fun `merged manifest declares dataSync for WorkManager's foreground service`() {
        val service = mergedManifestServices().singleOrNull {
            it.getAttributeNS(ANDROID_NS, "name") == SYSTEM_FOREGROUND_SERVICE
        }

        assertThat(service).isNotNull()
        assertThat(service!!.getAttributeNS(ANDROID_NS, "foregroundServiceType")).isEqualTo("dataSync")
    }

    /**
     * WorkManager 2.10 self-initializes through `androidx.startup`, and a self-initialized
     * WorkManager silently ignores `KopiaApp`'s custom factory -- the worker then cannot reach the
     * repository and every backup fails with "Repository not configured".
     */
    @Test
    fun `merged manifest removes WorkManager's startup initializer`() {
        val initializers = mergedManifestElements("meta-data")
            .map { it.getAttributeNS(ANDROID_NS, "name") }

        assertThat(initializers).doesNotContain(WORK_MANAGER_INITIALIZER)
    }

    /**
     * The source identity is the `host` half of every source key this device writes. A transplanted
     * copy would let a replacement device — or a second device restored from the same cloud backup —
     * write into the original device's source, interleaving two devices' snapshots under one
     * retention policy.
     */
    @Test
    fun `the source identity is excluded from Android backup and device transfer`() {
        val application = mergedManifestElements("application").single()
        val prefsFile = "${SourceIdentityStore.PREFS_NAME}.xml"

        // Both attributes and both rule files: pre-31 devices read fullBackupContent, 31+ read
        // dataExtractionRules, and each has to cover every channel that copies data off the device.
        assertThat(application.getAttributeNS(ANDROID_NS, "fullBackupContent")).isEqualTo("@xml/backup_rules")
        assertThat(application.getAttributeNS(ANDROID_NS, "dataExtractionRules"))
            .isEqualTo("@xml/data_extraction_rules")

        assertThat(excludedSharedPrefs("backup_rules", "full-backup-content")).contains(prefsFile)
        assertThat(excludedSharedPrefs("data_extraction_rules", "cloud-backup")).contains(prefsFile)
        assertThat(excludedSharedPrefs("data_extraction_rules", "device-transfer")).contains(prefsFile)
    }

    /**
     * The whole UI is a React app inside one WebView. Letting Android recreate the activity on a
     * rotation destroys that WebView, the React app loads again from its entry point, and the user
     * lands back on the welcome screen — mid-connect, mid-restore, whatever they were doing. Nothing
     * in this activity depends on being recreated: it builds a FrameLayout with a WebView in it, and
     * `onConfigurationChanged` already handles the one thing that does need to react (the theme).
     */
    @Test
    fun `the main activity handles rotation itself instead of being recreated`() {
        val activity = mergedManifestElements("activity").single {
            it.getAttributeNS(ANDROID_NS, "name") == MAIN_ACTIVITY
        }

        val handled = activity.getAttributeNS(ANDROID_NS, "configChanges")
            .split("|")
            .map { it.trim() }
            .toSet()

        // orientation alone is not enough on any modern device: a rotation also changes screenSize,
        // smallestScreenSize and screenLayout, and any one of them left out recreates the activity.
        assertThat(handled).containsAtLeast(
            "orientation",
            "screenSize",
            "smallestScreenSize",
            "screenLayout",
            "uiMode",
        )
    }

    /**
     * Shared-preference files excluded under [section] of the named `res/xml` rules file.
     *
     * Both formats take bare attributes (AAPT rejects `android:domain` outright), but read the
     * namespaced form too so a future format change surfaces as a failed assertion rather than an
     * empty list that quietly matches nothing.
     */
    private fun excludedSharedPrefs(rulesFile: String, section: String): List<String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/res/xml/$rulesFile.xml"))

        val root = if (document.documentElement.tagName == section) {
            document.documentElement
        } else {
            document.getElementsByTagName(section).item(0) as? Element
                ?: error("no <$section> in $rulesFile.xml")
        }
        val excludes = root.getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .filter { it.attribute("domain") == "sharedpref" }
            .map { it.attribute("path") }
    }

    private fun Element.attribute(name: String): String {
        val namespaced = getAttributeNS(ANDROID_NS, name)
        return namespaced.ifEmpty { getAttribute(name) }
    }

    private fun mergedManifestServices(): List<Element> = mergedManifestElements("service")

    private fun mergedManifestElements(tag: String): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest())
            .getElementsByTagName(tag)

        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun mergedManifest(): File {
        val properties = Properties()
        val resource = checkNotNull(javaClass.classLoader.getResourceAsStream(TEST_CONFIG)) {
            "$TEST_CONFIG missing from the test classpath"
        }
        resource.use(properties::load)

        val path = checkNotNull(properties.getProperty("android_merged_manifest")) {
            "android_merged_manifest missing from $TEST_CONFIG"
        }
        return File(path).also { check(it.isFile) { "merged manifest not found at ${it.absolutePath}" } }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SYSTEM_FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
        const val WORK_MANAGER_INITIALIZER = "androidx.work.WorkManagerInitializer"
        const val MAIN_ACTIVITY = "org.kopiaKt.app.MainActivity"
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
    }
}
