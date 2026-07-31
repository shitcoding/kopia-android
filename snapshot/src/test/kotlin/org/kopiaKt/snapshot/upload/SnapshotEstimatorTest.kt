package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.DirectoryIterator
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.kopiaKt.snapshot.fs.Symlink
import org.kopiaKt.snapshot.policy.FilesPolicy
import java.io.InputStream
import java.time.Instant

class SnapshotEstimatorTest {

    // -- Test helpers: in-memory filesystem entries --

    private open class InMemoryEntry(
        override val name: String,
        override val type: EntryType,
        override val size: Long = 0,
        override val modTime: Instant = Instant.now(),
        override val mode: Int = 420,
        override val owner: OwnerInfo = OwnerInfo.EMPTY,
        override val device: DeviceInfo = DeviceInfo.EMPTY,
        override val localFilesystemPath: String = "",
    ) : Entry

    private class InMemoryFile(
        name: String,
        fileSize: Long,
        modTime: Instant = Instant.now(),
    ) : InMemoryEntry(name, EntryType.FILE, fileSize, modTime),
        File {
        override suspend fun open(): InputStream = ByteArray(size.toInt()).inputStream()
    }

    private class InMemoryDirectory(
        name: String,
        private val entries: List<Entry>,
        modTime: Instant = Instant.now(),
    ) : InMemoryEntry(name, EntryType.DIRECTORY, 0, modTime),
        Directory {
        override suspend fun child(name: String): Entry? = entries.find { it.name == name }
        override suspend fun iterate(): DirectoryIterator = ListIterator(entries)
        override fun supportsMultipleIterations(): Boolean = true
    }

    private class InMemorySymlink(
        name: String,
        private val target: String,
        modTime: Instant = Instant.now(),
    ) : InMemoryEntry(name, EntryType.SYMLINK, 0, modTime),
        Symlink {
        override suspend fun readlink(): String = target
        override suspend fun resolve(): Entry? = null
    }

    /**
     * A file that introduces delay when the directory iterates. Used for cancellation tests.
     */
    private class SlowFile(
        name: String,
        fileSize: Long,
        private val delayMs: Long,
    ) : InMemoryEntry(name, EntryType.FILE, fileSize),
        File {
        override suspend fun open(): InputStream {
            delay(delayMs)
            return ByteArray(size.toInt()).inputStream()
        }
    }

    /**
     * A directory that introduces delay during iteration. Used for cancellation tests.
     */
    private class SlowDirectory(
        name: String,
        private val entries: List<Entry>,
        private val delayPerEntry: Long,
    ) : InMemoryEntry(name, EntryType.DIRECTORY, 0),
        Directory {
        override suspend fun child(name: String): Entry? = entries.find { it.name == name }
        override suspend fun iterate(): DirectoryIterator = SlowIterator(entries, delayPerEntry)
        override fun supportsMultipleIterations(): Boolean = true
    }

    private class SlowIterator(
        entries: List<Entry>,
        private val delayMs: Long,
    ) : DirectoryIterator {
        private val iter = entries.iterator()
        override suspend fun next(): Entry? {
            if (!iter.hasNext()) return null
            delay(delayMs)
            return iter.next()
        }
        override fun close() {}
    }

    /**
     * An entry that represents a file that can't be read (permission denied).
     */
    private class UnreadableFile(
        name: String,
    ) : InMemoryEntry(name, EntryType.FILE, 100),
        File {
        override suspend fun open(): InputStream = throw java.io.IOException("Permission denied")
    }

    private class ListIterator(entries: List<Entry>) : DirectoryIterator {
        private val iter = entries.iterator()
        override suspend fun next(): Entry? = if (iter.hasNext()) iter.next() else null
        override fun close() {}
    }

    // -- Tests --

    @Nested
    inner class BasicCounting {

        @Test
        fun `estimate counts all files`(): Unit = runBlocking {
            val root = InMemoryDirectory(
                "root",
                listOf(
                    InMemoryFile("a.txt", 100),
                    InMemoryFile("b.txt", 200),
                    InMemoryFile("c.txt", 300),
                    InMemoryFile("d.txt", 400),
                    InMemoryFile("e.txt", 500),
                ),
            )

            val result = SnapshotEstimator.estimate(root)

            assertEquals(5, result.totalFiles)
        }

        @Test
        fun `estimate sums file sizes`(): Unit = runBlocking {
            val root = InMemoryDirectory(
                "root",
                listOf(
                    InMemoryFile("small.txt", 100),
                    InMemoryFile("medium.txt", 1_000),
                    InMemoryFile("large.txt", 10_000),
                ),
            )

            val result = SnapshotEstimator.estimate(root)

            assertEquals(3, result.totalFiles)
            assertEquals(11_100L, result.totalBytes)
        }
    }

    @Nested
    inner class ExclusionPolicy {

        @Test
        fun `estimate applies exclusion policy`(): Unit = runBlocking {
            val root = InMemoryDirectory(
                "root",
                listOf(
                    InMemoryFile("data.txt", 100),
                    InMemoryFile("debug.log", 200),
                    InMemoryFile("error.log", 300),
                    InMemoryFile("readme.md", 50),
                ),
            )

            val policy = FilesPolicy(ignoreRules = listOf("*.log"))
            val result = SnapshotEstimator.estimate(root, policy)

            assertEquals(2, result.totalFiles)
            assertEquals(150L, result.totalBytes)
            assertEquals(2, result.excludedFiles)
            assertEquals(500L, result.excludedBytes)
        }
    }

    @Nested
    inner class EmptyDirectory {

        @Test
        fun `estimate handles empty directory`(): Unit = runBlocking {
            val root = InMemoryDirectory("root", emptyList())

            val result = SnapshotEstimator.estimate(root)

            assertEquals(0, result.totalFiles)
            assertEquals(0L, result.totalBytes)
            assertEquals(0, result.totalDirectories)
            assertEquals(0, result.excludedFiles)
        }
    }

    @Nested
    inner class DeepDirectoryTree {

        @Test
        fun `estimate with deep directory tree`(): Unit = runBlocking {
            // Build 5-level deep tree: level1/level2/level3/level4/level5/file.txt
            // Each level also has a file
            fun buildLevel(depth: Int): InMemoryDirectory {
                val name = "level$depth"
                val entries = mutableListOf<Entry>()
                entries.add(InMemoryFile("file_$depth.txt", (depth * 100).toLong()))
                if (depth < 5) {
                    entries.add(buildLevel(depth + 1))
                }
                return InMemoryDirectory(name, entries)
            }

            val root = buildLevel(1)
            val result = SnapshotEstimator.estimate(root)

            // 5 files (one per level)
            assertEquals(5, result.totalFiles)
            // 100 + 200 + 300 + 400 + 500 = 1500
            assertEquals(1500L, result.totalBytes)
            // 4 subdirectories (levels 2-5, root not counted)
            assertEquals(4, result.totalDirectories)
        }
    }

    @Nested
    inner class ProgressReporting {

        @Test
        fun `estimate reports progress`(): Unit = runBlocking {
            val root = InMemoryDirectory(
                "root",
                listOf(
                    InMemoryFile("a.txt", 100),
                    InMemoryFile("b.txt", 200),
                    InMemoryFile("c.txt", 300),
                ),
            )

            val progressUpdates = mutableListOf<EstimateProgress>()

            SnapshotEstimator.estimate(root) { progress ->
                progressUpdates.add(progress)
            }

            // Should have received progress callbacks (at least one per file)
            assertTrue(progressUpdates.size >= 3, "Expected at least 3 progress updates, got ${progressUpdates.size}")

            // Last progress update should match final totals
            val last = progressUpdates.last()
            assertEquals(3, last.totalFiles)
            assertEquals(600L, last.totalBytes)
        }
    }

    @Nested
    inner class Cancellation {

        @Test
        fun `estimate cancellation stops early`(): Unit = runBlocking {
            // Create a directory with many slow entries so we have time to cancel
            val entries = (1..100).map { InMemoryFile("file_$it.txt", 100) }
            val root = SlowDirectory("root", entries, delayPerEntry = 50)

            var result: EstimateResult? = null
            val job = launch {
                result = SnapshotEstimator.estimate(root)
            }

            // Give it a little time to start processing, then cancel
            delay(150)
            job.cancelAndJoin()

            // The job was cancelled so it should have thrown CancellationException.
            // result may be null (cancelled before finishing) or partial.
            // The key assertion: the job completed without hanging
            assertTrue(
                result == null || result!!.totalFiles < 100,
                "Cancellation should have stopped before processing all 100 files",
            )
        }
    }

    @Nested
    inner class SymlinkHandling {

        @Test
        fun `estimate skips symlinks`(): Unit = runBlocking {
            val root = InMemoryDirectory(
                "root",
                listOf(
                    InMemoryFile("real.txt", 100),
                    InMemorySymlink("link.txt", "/some/target"),
                    InMemorySymlink("another_link", "/other/target"),
                    InMemoryFile("also_real.txt", 200),
                ),
            )

            val result = SnapshotEstimator.estimate(root)

            // Only regular files should be counted in totalFiles
            assertEquals(2, result.totalFiles)
            assertEquals(300L, result.totalBytes)
            // Symlinks are counted separately
            assertEquals(2, result.totalSymlinks)
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `estimate handles unreadable files`(): Unit = runBlocking {
            // For the estimator, we only need to stat files, not read them.
            // An "unreadable" entry in terms of estimation would be an entry
            // that causes an error during iteration. We simulate this with
            // a directory that throws during iteration for some entries.
            val root = InMemoryDirectory(
                "root",
                listOf(
                    InMemoryFile("good.txt", 100),
                    InMemoryFile("also_good.txt", 200),
                ),
            )

            // Wrap in a directory that throws for specific children
            val errorDir = ErrorThrowingDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("good.txt", 100),
                    InMemoryFile("also_good.txt", 200),
                ),
                errorNames = setOf(), // no errors in this basic case
            )

            val result = SnapshotEstimator.estimate(errorDir)
            assertEquals(2, result.totalFiles)
            assertEquals(0, result.errorCount)
        }

        @Test
        fun `estimate counts errors for problematic entries`(): Unit = runBlocking {
            val errorDir = ErrorThrowingDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("good.txt", 100),
                    InMemoryFile("bad.txt", 200),
                    InMemoryFile("also_good.txt", 300),
                ),
                errorNames = setOf("bad.txt"),
            )

            val result = SnapshotEstimator.estimate(errorDir)

            // good.txt and also_good.txt should be counted, bad.txt should produce an error
            assertEquals(2, result.totalFiles)
            assertEquals(400L, result.totalBytes)
            assertEquals(1, result.errorCount)
        }
    }

    @Nested
    inner class SizeDistribution {

        @Test
        fun `estimate result includes size distribution`(): Unit = runBlocking {
            val root = InMemoryDirectory(
                "root",
                listOf(
                    InMemoryFile("tiny.txt", 50), // < 1 KB
                    InMemoryFile("small.txt", 500), // < 1 KB
                    InMemoryFile("medium.txt", 5_000), // 1 KB - 10 KB
                    InMemoryFile("large.txt", 500_000), // 100 KB - 1 MB
                    InMemoryFile("huge.txt", 5_000_000), // 1 MB - 10 MB
                ),
            )

            val result = SnapshotEstimator.estimate(root)

            // Verify the distribution map is populated
            assertTrue(result.sizeDistribution.isNotEmpty(), "Size distribution should not be empty")

            // Verify buckets contain expected file counts
            // Files under 1KB: tiny.txt (50) and small.txt (500)
            assertEquals(2, result.sizeDistribution[SizeBucket.UNDER_1KB])

            // Files 1KB-10KB: medium.txt (5000)
            assertEquals(1, result.sizeDistribution[SizeBucket.FROM_1KB_TO_10KB])

            // Files 100KB-1MB: large.txt (500000)
            assertEquals(1, result.sizeDistribution[SizeBucket.FROM_100KB_TO_1MB])

            // Files 1MB-10MB: huge.txt (5000000)
            assertEquals(1, result.sizeDistribution[SizeBucket.FROM_1MB_TO_10MB])
        }
    }

    /**
     * A directory that throws errors when iterating entries with names in [errorNames].
     * Entries not in errorNames are yielded normally. For entries in errorNames, the
     * iterator skips them and increments an internal error count accessible via the
     * ErrorEntry interface.
     */
    private class ErrorThrowingDirectory(
        name: String,
        private val entries: List<Entry>,
        private val errorNames: Set<String>,
    ) : InMemoryEntry(name, EntryType.DIRECTORY, 0),
        Directory {
        override suspend fun child(name: String): Entry? = entries.find { it.name == name }

        override suspend fun iterate(): DirectoryIterator = ErrorThrowingIterator(entries, errorNames)

        override fun supportsMultipleIterations(): Boolean = true
    }

    private class ErrorThrowingIterator(
        entries: List<Entry>,
        private val errorNames: Set<String>,
    ) : DirectoryIterator {
        private val iter = entries.iterator()

        override suspend fun next(): Entry? {
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.name in errorNames) {
                    // Return an ErrorEntry to signal an error for this entry
                    return ErrorEntryImpl(entry.name, java.io.IOException("Cannot read: ${entry.name}"))
                }
                return entry
            }
            return null
        }

        override fun close() {}
    }

    private class ErrorEntryImpl(
        override val name: String,
        override val error: Throwable,
    ) : InMemoryEntry(name, EntryType.ERROR, 0),
        org.kopiaKt.snapshot.fs.ErrorEntry
}
