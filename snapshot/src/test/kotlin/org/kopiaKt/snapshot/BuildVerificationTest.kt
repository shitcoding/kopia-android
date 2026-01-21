package org.kopiaKt.snapshot

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SnapshotStats
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.CompressionPolicy
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.policy.RetentionPolicy
import java.time.Instant

/**
 * Build verification tests for the snapshot module.
 */
class BuildVerificationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    @Test
    fun `SourceInfo can be created and converted to string`() {
        val source = SourceInfo(
            host = "laptop",
            userName = "alice",
            path = "/home/alice/Documents"
        )

        assertThat(source.toString()).isEqualTo("alice@laptop:/home/alice/Documents")
    }

    @Test
    fun `SourceInfo can be parsed from string`() {
        val source = SourceInfo.parse("alice@laptop:/home/alice/Documents")

        assertThat(source).isNotNull()
        assertThat(source!!.userName).isEqualTo("alice")
        assertThat(source.host).isEqualTo("laptop")
        assertThat(source.path).isEqualTo("/home/alice/Documents")
    }

    @Test
    fun `SourceInfo parse returns null for invalid input`() {
        assertThat(SourceInfo.parse("invalid")).isNull()
        assertThat(SourceInfo.parse("no-at-sign:path")).isNull()
    }

    @Test
    fun `SnapshotManifest can be serialized and deserialized`() {
        val manifest = SnapshotManifest(
            id = "test-id-123",
            source = SourceInfo("host", "user", "/path"),
            description = "Test backup",
            startTime = Instant.parse("2025-01-20T12:00:00Z"),
            endTime = Instant.parse("2025-01-20T12:05:00Z"),
            stats = SnapshotStats(
                totalFileCount = 100,
                totalFileSize = 1024L * 1024 * 50
            )
        )

        val jsonString = json.encodeToString(manifest)
        val decoded = json.decodeFromString<SnapshotManifest>(jsonString)

        assertThat(decoded.id).isEqualTo(manifest.id)
        assertThat(decoded.source).isEqualTo(manifest.source)
        assertThat(decoded.stats?.totalFileCount).isEqualTo(100)
    }

    @Test
    fun `DirEntry types serialize correctly`() {
        val file = DirEntry(
            name = "test.txt",
            type = EntryType.FILE,
            fileSize = 1024L
        )

        val dir = DirEntry(
            name = "subdir",
            type = EntryType.DIRECTORY
        )

        val symlink = DirEntry(
            name = "link",
            type = EntryType.SYMLINK
            // Note: symlink target is stored in the object the ObjectID points to, not in DirEntry
        )

        val fileJson = json.encodeToString(file)
        val dirJson = json.encodeToString(dir)
        val symlinkJson = json.encodeToString(symlink)

        assertThat(fileJson).contains("\"f\"")
        assertThat(dirJson).contains("\"d\"")
        assertThat(symlinkJson).contains("\"s\"")
    }

    @Test
    fun `Policy defaults are sensible`() {
        val policy = Policy()

        assertThat(policy.compression.compressorName).isEqualTo("zstd")
        assertThat(policy.splitter.algorithm).isEqualTo("DYNAMIC-4M-BUZHASH")
        assertThat(policy.noParent).isFalse()
    }

    @Test
    fun `RetentionPolicy can be configured`() {
        val retention = RetentionPolicy(
            keepLatest = 10,
            keepDaily = 7,
            keepWeekly = 4,
            keepMonthly = 12
        )

        assertThat(retention.keepLatest).isEqualTo(10)
        assertThat(retention.keepDaily).isEqualTo(7)
        assertThat(retention.keepWeekly).isEqualTo(4)
        assertThat(retention.keepMonthly).isEqualTo(12)
        assertThat(retention.keepHourly).isNull()
        assertThat(retention.keepAnnual).isNull()
    }

    @Test
    fun `CompressionPolicy can be configured`() {
        val compression = CompressionPolicy(
            compressorName = "lz4",
            neverCompress = listOf("*.jpg", "*.mp4", "*.zip"),
            minSize = 1024
        )

        assertThat(compression.compressorName).isEqualTo("lz4")
        assertThat(compression.neverCompress).containsExactly("*.jpg", "*.mp4", "*.zip")
        assertThat(compression.minSize).isEqualTo(1024)
    }
}
