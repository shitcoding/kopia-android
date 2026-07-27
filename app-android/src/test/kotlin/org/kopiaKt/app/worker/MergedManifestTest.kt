package org.kopiaKt.app.worker

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
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
        const val TEST_CONFIG = "com/android/tools/test_config.properties"
    }
}
