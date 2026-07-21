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
        fun `walks empty directory`(@TempDir tempDir: Path) = runBlocking {
            val dir = LocalFilesystem.directory(tempDir)
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())

            val result = walker.walk(dir)

            assertEquals(EntryType.DIRECTORY, result.type)
            assertNotNull(result.objectId)
            assertEquals(1, processor.dirManifestCount.get())
        }

        @Test
        fun `walks directory with files`(@TempDir tempDir: Path) = runBlocking {
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
        fun `walks nested directories`(@TempDir tempDir: Path) = runBlocking {
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
        fun `walks deeply nested directories`(@TempDir tempDir: Path) = runBlocking {
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
        fun `processes symlinks`(@TempDir tempDir: Path) = runBlocking {
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
        fun `ignores file errors when policy allows`(@TempDir tempDir: Path) = runBlocking {
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
        fun `reports progress events`(@TempDir tempDir: Path) = runBlocking {
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
        fun `respects parallelism setting`(@TempDir tempDir: Path) = runBlocking {
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

    /**
     * Test implementation of EntryProcessor that tracks calls.
     */
    private class TestEntryProcessor : EntryProcessor {
        val fileCount = AtomicInteger(0)
        val symlinkCount = AtomicInteger(0)
        val dirManifestCount = AtomicInteger(0)

        private var nextObjectId = AtomicInteger(1)

        override suspend fun processFile(
            file: File,
            relativePath: String,
            previousEntry: DirEntry?,
        ): DirEntry {
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
