package org.kopiaKt.snapshot.upload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirectorySummary
import org.kopiaKt.snapshot.model.EntryType
import java.time.Instant

class DirManifestBuilderTest {

    @Test
    fun `build empty directory manifest`() {
        val builder = DirManifestBuilder()
        val now = Instant.now()

        val manifest = builder.build(now)

        assertEquals("kopia:directory", manifest.streamType)
        assertTrue(manifest.entries.isEmpty())
        assertNotNull(manifest.summary)
        assertEquals(1, manifest.summary!!.totalDirCount) // Counts itself
        assertEquals(0L, manifest.summary!!.totalFileCount)
        assertEquals(now, manifest.summary!!.maxModTime)
    }

    @Test
    fun `addEntry with file updates summary`() {
        val builder = DirManifestBuilder()
        val fileModTime = Instant.parse("2024-01-15T10:30:00Z")

        builder.addEntry(
            DirEntry(
                name = "test.txt",
                type = EntryType.FILE,
                fileSize = 1000,
                modTime = fileModTime,
            ),
        )

        val manifest = builder.build(Instant.now())

        assertEquals(1, manifest.entries.size)
        assertEquals(1L, manifest.summary!!.totalFileCount)
        assertEquals(1000L, manifest.summary!!.totalFileSize)
        assertEquals(fileModTime, manifest.summary!!.maxModTime)
    }

    @Test
    fun `addEntry with symlink updates summary`() {
        val builder = DirManifestBuilder()

        builder.addEntry(
            DirEntry(
                name = "link",
                type = EntryType.SYMLINK,
                modTime = Instant.now(),
            ),
        )

        val manifest = builder.build(Instant.now())

        assertEquals(1, manifest.entries.size)
        assertEquals(1L, manifest.summary!!.totalSymlinkCount)
    }

    @Test
    fun `addEntry with directory aggregates child summary`() {
        val builder = DirManifestBuilder()
        val childSummary = DirectorySummary(
            totalFileCount = 5,
            totalFileSize = 10000,
            totalDirCount = 2,
            totalSymlinkCount = 1,
            maxModTime = Instant.parse("2024-01-20T12:00:00Z"),
            fatalErrorCount = 1,
            ignoredErrorCount = 2,
        )

        builder.addEntry(
            DirEntry(
                name = "subdir",
                type = EntryType.DIRECTORY,
                modTime = Instant.parse("2024-01-15T10:00:00Z"),
                dirSummary = childSummary,
            ),
        )

        val manifest = builder.build(Instant.parse("2024-01-01T00:00:00Z"))

        assertEquals(1, manifest.entries.size)
        assertEquals(5L, manifest.summary!!.totalFileCount)
        assertEquals(10000L, manifest.summary!!.totalFileSize)
        assertEquals(3, manifest.summary!!.totalDirCount) // 2 from child + 1 for self
        assertEquals(1L, manifest.summary!!.totalSymlinkCount)
        assertEquals(1, manifest.summary!!.fatalErrorCount)
        assertEquals(2, manifest.summary!!.ignoredErrorCount)
        assertEquals(Instant.parse("2024-01-20T12:00:00Z"), manifest.summary!!.maxModTime)
    }

    @Test
    fun `addFailedEntry increments error count`() {
        val builder = DirManifestBuilder()

        builder.addFailedEntry("/path/to/file", isIgnoredError = false, RuntimeException("fatal error"))
        builder.addFailedEntry("/path/to/other", isIgnoredError = true, RuntimeException("ignored error"))

        val manifest = builder.build(Instant.now())

        assertEquals(1, manifest.summary!!.fatalErrorCount)
        assertEquals(1, manifest.summary!!.ignoredErrorCount)
        assertNotNull(manifest.summary!!.failedEntries)
        assertEquals(2, manifest.summary!!.failedEntries!!.size)
    }

    @Test
    fun `failed entries are sorted and limited`() {
        val builder = DirManifestBuilder()

        // Add more than MAX_FAILED_ENTRIES_PER_DIRECTORY (10) entries
        for (i in 20 downTo 1) {
            builder.addFailedEntry("/path/file$i", isIgnoredError = false, RuntimeException("error $i"))
        }

        val manifest = builder.build(Instant.now())

        // Should be limited to 10 and sorted
        assertEquals(10, manifest.summary!!.failedEntries!!.size)
        assertEquals("/path/file1", manifest.summary!!.failedEntries!![0].entryPath)
        assertEquals("/path/file10", manifest.summary!!.failedEntries!![1].entryPath)
    }

    @Test
    fun `entries are sorted directories first then by name`() {
        val builder = DirManifestBuilder()

        builder.addEntry(DirEntry(name = "zebra.txt", type = EntryType.FILE))
        builder.addEntry(DirEntry(name = "alpha.txt", type = EntryType.FILE))
        builder.addEntry(DirEntry(name = "zdir", type = EntryType.DIRECTORY))
        builder.addEntry(DirEntry(name = "adir", type = EntryType.DIRECTORY))
        builder.addEntry(DirEntry(name = "link", type = EntryType.SYMLINK))

        val manifest = builder.build(Instant.now())

        val names = manifest.entries.map { it.name }
        assertEquals(listOf("adir", "zdir", "alpha.txt", "link", "zebra.txt"), names)
    }

    @Test
    fun `clone creates independent copy`() {
        val original = DirManifestBuilder()
        original.addEntry(
            DirEntry(
                name = "file1.txt",
                type = EntryType.FILE,
                fileSize = 100,
            ),
        )

        val clone = original.clone()

        // Add more entries to original
        original.addEntry(
            DirEntry(
                name = "file2.txt",
                type = EntryType.FILE,
                fileSize = 200,
            ),
        )

        // Clone should not have the new entry
        val cloneManifest = clone.build(Instant.now())
        val originalManifest = original.build(Instant.now())

        assertEquals(1, cloneManifest.entries.size)
        assertEquals(2, originalManifest.entries.size)
    }

    @Test
    fun `build with incomplete reason sets it in summary`() {
        val builder = DirManifestBuilder()
        builder.addEntry(DirEntry(name = "file.txt", type = EntryType.FILE))

        val manifest = builder.build(Instant.now(), incompleteReason = "checkpoint")

        assertEquals("checkpoint", manifest.summary!!.incompleteReason)
    }

    @Test
    fun `isEmpty returns true for empty builder`() {
        val builder = DirManifestBuilder()
        assertTrue(builder.isEmpty())
    }

    @Test
    fun `isEmpty returns false after adding entry`() {
        val builder = DirManifestBuilder()
        builder.addEntry(DirEntry(name = "file.txt", type = EntryType.FILE))
        assertTrue(!builder.isEmpty())
    }

    @Test
    fun `entryCount returns correct count`() {
        val builder = DirManifestBuilder()
        assertEquals(0, builder.entryCount())

        builder.addEntry(DirEntry(name = "file1.txt", type = EntryType.FILE))
        assertEquals(1, builder.entryCount())

        builder.addEntry(DirEntry(name = "file2.txt", type = EntryType.FILE))
        assertEquals(2, builder.entryCount())
    }

    @Test
    fun `maxModTime is updated from entries`() {
        val builder = DirManifestBuilder()
        val oldTime = Instant.parse("2024-01-01T00:00:00Z")
        val newTime = Instant.parse("2024-06-01T00:00:00Z")

        builder.addEntry(
            DirEntry(
                name = "old.txt",
                type = EntryType.FILE,
                modTime = oldTime,
            ),
        )
        builder.addEntry(
            DirEntry(
                name = "new.txt",
                type = EntryType.FILE,
                modTime = newTime,
            ),
        )

        val manifest = builder.build(Instant.parse("2024-03-01T00:00:00Z"))

        assertEquals(newTime, manifest.summary!!.maxModTime)
    }

    @Test
    fun `buildSummary returns correct summary`() {
        val builder = DirManifestBuilder()
        builder.addEntry(
            DirEntry(
                name = "file.txt",
                type = EntryType.FILE,
                fileSize = 500,
            ),
        )

        val summary = builder.buildSummary(Instant.now(), "test-reason")

        assertEquals(1L, summary.totalFileCount)
        assertEquals(500L, summary.totalFileSize)
        assertEquals("test-reason", summary.incompleteReason)
    }
}
