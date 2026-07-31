package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.fs.Symlink
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.policy.ErrorHandlingPolicy
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText

class TreeWalkerTest {

    @Nested
    inner class BasicWalking {

        @Test
        fun `walks empty directory`(@TempDir tempDir: Path): Unit = runBlocking {
            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())

            val result = walker.walk(dir)

            assertEquals(EntryType.DIRECTORY, result.type)
            assertNotNull(result.objectId)
            assertEquals(1, processor.dirManifestCount.get())
        }

        @Test
        fun `walks directory with files`(@TempDir tempDir: Path): Unit = runBlocking {
            // Create test files
            tempDir.resolve("file1.txt").writeText("content1")
            tempDir.resolve("file2.txt").writeText("content2")
            tempDir.resolve("file3.txt").writeText("content3")

            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())

            val result = walker.walk(dir)

            assertEquals(EntryType.DIRECTORY, result.type)
            assertEquals(3, processor.fileCount.get())
            assertEquals(1, processor.dirManifestCount.get())
        }

        @Test
        fun `walks nested directories`(@TempDir tempDir: Path): Unit = runBlocking {
            // Create nested structure
            val subDir = tempDir.resolve("subdir").createDirectories()
            tempDir.resolve("root.txt").writeText("root")
            subDir.resolve("nested.txt").writeText("nested")

            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())

            val result = walker.walk(dir)

            assertEquals(EntryType.DIRECTORY, result.type)
            assertEquals(2, processor.fileCount.get()) // root.txt and nested.txt
            assertEquals(2, processor.dirManifestCount.get()) // root and subdir
        }

        @Test
        fun `walks deeply nested directories`(@TempDir tempDir: Path): Unit = runBlocking {
            // Create deep nesting: a/b/c/d/file.txt
            val deepDir = tempDir.resolve("a/b/c/d").createDirectories()
            deepDir.resolve("file.txt").writeText("deep")

            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())

            val result = walker.walk(dir)

            assertEquals(EntryType.DIRECTORY, result.type)
            assertEquals(1, processor.fileCount.get())
            assertEquals(5, processor.dirManifestCount.get()) // root, a, b, c, d
        }
    }

    @Nested
    inner class SymlinkHandling {

        @Test
        fun `processes symlinks`(@TempDir tempDir: Path): Unit = runBlocking {
            val targetFile = tempDir.resolve("target.txt")
            targetFile.writeText("target content")
            tempDir.resolve("link.txt").createSymbolicLinkPointingTo(targetFile)

            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())

            val result = walker.walk(dir)

            assertEquals(EntryType.DIRECTORY, result.type)
            assertEquals(1, processor.fileCount.get()) // target.txt
            assertEquals(1, processor.symlinkCount.get()) // link.txt
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `ignores file errors when policy allows`(@TempDir tempDir: Path): Unit = runBlocking {
            tempDir.resolve("good.txt").writeText("good")
            // Note: We can't easily simulate a file read error in a cross-platform way
            // This test verifies the basic error policy behavior

            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val policy = ErrorHandlingPolicy(ignoreFileErrors = true)
            val walker = TreeWalker(processor, NullUploadProgress(), policy)

            val result = walker.walk(dir)

            assertEquals(EntryType.DIRECTORY, result.type)
        }
    }

    @Nested
    inner class Cancellation {

        @Test
        fun `can be cancelled`() {
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())

            assertFalse(walker.isCancelled())
            walker.cancel()
            assertTrue(walker.isCancelled())
        }
    }

    @Nested
    inner class ProgressReporting {

        @Test
        fun `reports progress events`(@TempDir tempDir: Path): Unit = runBlocking {
            tempDir.resolve("file1.txt").writeText("content1")
            tempDir.resolve("file2.txt").writeText("content2")

            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val progress = CountingUploadProgress()
            val walker = TreeWalker(processor, progress)

            walker.walk(dir)

            val counters = progress.snapshot()
            assertEquals(2, counters.totalHashedFiles)
        }
    }

    @Nested
    inner class Parallelism {

        @Test
        fun `respects parallelism setting`(@TempDir tempDir: Path): Unit = runBlocking {
            // Create multiple files
            repeat(10) { i ->
                tempDir.resolve("file$i.txt").writeText("content$i")
            }

            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress(), parallelism = 2)

            val result = walker.walk(dir)

            assertEquals(10, processor.fileCount.get())
        }
    }

    @Nested
    inner class IncrementalReuse {

        @Test
        fun `previous entries are reused below the snapshot root`(@TempDir tempDir: Path): Unit = runBlocking {
            val sub = tempDir.resolve("sub")
            sub.createDirectories()
            tempDir.resolve("top.txt").writeText("top")
            sub.resolve("nested.txt").writeText("nested")

            val dir = LocalFilesystem.directory(tempDir)
            val nested = (dir.child("sub") as org.kopiaKt.snapshot.fs.Directory).child("nested.txt") as File
            val top = dir.child("top.txt") as File

            val previousNested = DirEntry(
                name = "nested.txt",
                type = EntryType.FILE,
                permissions = nested.mode,
                fileSize = nested.size,
                modTime = nested.modTime,
                objectId = "previous-nested",
            )
            val previousRoot = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "top.txt",
                        type = EntryType.FILE,
                        permissions = top.mode,
                        fileSize = top.size,
                        modTime = top.modTime,
                        objectId = "previous-top",
                    ),
                    DirEntry(
                        name = "sub",
                        type = EntryType.DIRECTORY,
                        permissions = 493,
                        modTime = nested.modTime,
                        objectId = "previous-sub-manifest",
                    ),
                ),
            )

            val processor = TestEntryProcessor()
            processor.previousManifests["previous-sub-manifest"] = DirManifest(entries = listOf(previousNested))

            TreeWalker(processor, NullUploadProgress()).walk(dir, previousRoot)

            // Root-level reuse always worked; the nested one is the regression guard. Without the
            // subdirectory manifest lookup, every file below the root was re-read and re-hashed.
            assertEquals("previous-top", processor.previousEntrySeen["top.txt"])
            assertEquals("previous-nested", processor.previousEntrySeen["sub/nested.txt"])
        }

        @Test
        fun `an unreadable previous subdirectory manifest just disables reuse`(
            @TempDir tempDir: Path,
        ): Unit = runBlocking {
            val sub = tempDir.resolve("sub")
            sub.createDirectories()
            sub.resolve("nested.txt").writeText("nested")

            val previousRoot = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "sub",
                        type = EntryType.DIRECTORY,
                        permissions = 493,
                        modTime = java.time.Instant.now(),
                        objectId = "missing-manifest",
                    ),
                ),
            )

            val processor = TestEntryProcessor() // knows no manifests -> loadDirManifest returns null
            TreeWalker(processor, NullUploadProgress()).walk(LocalFilesystem.directory(tempDir), previousRoot)

            assertEquals(null, processor.previousEntrySeen["sub/nested.txt"])
            assertEquals(1, processor.fileCount.get())
        }
    }

    /**
     * Test implementation of EntryProcessor that tracks calls.
     */
    private class TestEntryProcessor : EntryProcessor {
        val fileCount = AtomicInteger(0)
        val symlinkCount = AtomicInteger(0)
        val dirManifestCount = AtomicInteger(0)

        /** Previous manifests this processor will hand back, keyed by directory objectId. */
        val previousManifests = mutableMapOf<String, DirManifest>()

        /** Relative path -> the previousEntry the walker supplied for it. Written from parallel workers. */
        val previousEntrySeen = java.util.concurrent.ConcurrentHashMap<String, String>()

        private var nextObjectId = AtomicInteger(1)

        override suspend fun loadDirManifest(objectId: String): DirManifest? = previousManifests[objectId]

        override suspend fun processFile(
            file: File,
            relativePath: String,
            previousEntry: DirEntry?,
        ): DirEntry {
            previousEntry?.objectId?.let { previousEntrySeen[relativePath] = it }
            fileCount.incrementAndGet()
            return DirEntry(
                name = file.name,
                type = EntryType.FILE,
                permissions = file.mode,
                fileSize = file.size,
                modTime = file.modTime,
                objectId = "obj${nextObjectId.getAndIncrement()}",
            )
        }

        override suspend fun processSymlink(
            symlink: Symlink,
            relativePath: String,
            previousEntry: DirEntry?,
        ): DirEntry {
            symlinkCount.incrementAndGet()
            return DirEntry(
                name = symlink.name,
                type = EntryType.SYMLINK,
                permissions = symlink.mode,
                modTime = symlink.modTime,
                objectId = "obj${nextObjectId.getAndIncrement()}",
            )
        }

        override suspend fun uploadDirectoryManifest(manifest: DirManifest): String {
            dirManifestCount.incrementAndGet()
            return "dir${nextObjectId.getAndIncrement()}"
        }
    }
}
