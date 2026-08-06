package org.kopiaKt.app

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app declares `android:allowBackup="true"`, so every shared-preferences file it writes is
 * uploaded to Android Auto Backup and copied by device-to-device transfer unless it is excluded
 * by name. Two of them must never travel:
 *
 * - `kopia_source_identity` -- the `host` half of every source key this device writes. A
 *   transplanted copy lets a replacement phone write into the original device's source.
 * - `kopia_credentials` -- repository passwords. The values are encrypted, but the keys live in
 *   the Android Keystore, which is NOT part of a backup: a restored device gets a credentials
 *   file it cannot decrypt. So the exclusion buys both privacy and not shipping an
 *   undecryptable file to a new install.
 *
 * These rules are XML resources with no compile-time checking and no runtime failure if they are
 * wrong, so this test is the only thing standing between an edit and a silent regression.
 */
class BackupRulesTest {

    private fun excludedPaths(file: File, section: String?): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val scope = if (section == null) {
            doc.documentElement
        } else {
            val nodes = doc.getElementsByTagName(section)
            require(nodes.length == 1) { "expected exactly one <$section> in ${file.name}" }
            nodes.item(0) as Element
        }
        val excludes = scope.getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }
    }

    private fun resource(name: String) = File("src/main/res/xml/$name").also {
        require(it.exists()) { "missing ${it.absolutePath}" }
    }

    @Test
    @DisplayName("Auto Backup (API <= 30) excludes the credentials and the source identity")
    fun `full backup content excludes both sensitive preference files`() {
        val excluded = excludedPaths(resource("backup_rules.xml"), section = null)
        assertThat(excluded).containsAtLeast("kopia_credentials.xml", "kopia_source_identity.xml")
    }

    @Test
    @DisplayName("Android 12+ cloud backup excludes the credentials and the source identity")
    fun `cloud backup excludes both sensitive preference files`() {
        val excluded = excludedPaths(resource("data_extraction_rules.xml"), section = "cloud-backup")
        assertThat(excluded).containsAtLeast("kopia_credentials.xml", "kopia_source_identity.xml")
    }

    @Test
    @DisplayName("Android 12+ device transfer excludes the credentials and the source identity")
    fun `device transfer excludes both sensitive preference files`() {
        val excluded = excludedPaths(resource("data_extraction_rules.xml"), section = "device-transfer")
        assertThat(excluded).containsAtLeast("kopia_credentials.xml", "kopia_source_identity.xml")
    }
}
