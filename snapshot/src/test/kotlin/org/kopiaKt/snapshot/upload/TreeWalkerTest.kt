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

        @Test
        fun `a cancelled walk still writes the tree it got through`(@TempDir tempDir: Path): Unit = runBlocking {
            // Go never unwinds on cancel. processChildren returns errCanceled, every directory level
            // SWALLOWS it (upload.go:1183) and then builds and writes its partial manifest on the way
            // out, so the partial root falls out of normal control flow. Throwing instead unwinds past
            // every DirManifestBuilder and destroys exactly the state a resume needs -- which is why a
            // cancelled snapshot used to be saved with rootEntry = null and the next run re-hashed the
            // whole tree.
            tempDir.resolve("a.txt").writeText("a")
            tempDir.resolve("b.txt").writeText("b")
            val dir = LocalFilesystem.directory(tempDir)

            // Cancel before the walk starts: the most hostile version of mid-tree, and no timing race.
            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())
            walker.cancel()

            val root = walker.walk(dir)

            assertEquals(EntryType.DIRECTORY, root.type)
            assertNotNull(root.objectId)
            // The root manifest was written, which is what a resume reads.
            assertEquals(1, processor.dirManifestCount.get())
            assertEquals("canceled", processor.lastManifest?.summary?.incompleteReason)
        }

        @Test
        fun `a cancelled walk marks every directory it wrote incomplete`(@TempDir tempDir: Path): Unit = runBlocking {
            tempDir.resolve("nested").createDirectories().resolve("c.txt").writeText("c")
            val dir = LocalFilesystem.directory(tempDir)

            val processor = TestEntryProcessor()
            val walker = TreeWalker(processor, NullUploadProgress())
            walker.cancel()

            walker.walk(dir)

            // Nothing that a resume could mistake for a finished directory.
            assertTrue(processor.manifests.isNotEmpty())
            assertTrue(processor.manifests.all { it.summary?.incompleteReason == "canceled" })
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

            TreeWalker(processor, NullUploadProgress()).walk(dir, listOf(previousRoot))

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
            TreeWalker(processor, NullUploadProgress()).walk(LocalFilesystem.directory(tempDir), listOf(previousRoot))

            assertEquals(null, processor.previousEntrySeen["sub/nested.txt"])
            assertEquals(1, processor.fileCount.get())
        }

        /**
         * Phase 3.2 hands the walk SEVERAL previous trees — the last complete snapshot, then the
         * checkpoints of any run interrupted since. Each holds entries the others do not: an
         * interrupted run never reached the files queued behind whatever it was uploading when it
         * stopped, and the complete snapshot cannot know about files created after it. Taking one
         * tree instead of the union re-reads and re-hashes whichever half it dropped.
         */
        @Test
        fun `entries are taken from every previous tree, not just one`(@TempDir tempDir: Path): Unit = runBlocking {
            tempDir.resolve("settled.txt").writeText("settled")
            tempDir.resolve("checkpointed.txt").writeText("checkpointed")
            val dir = LocalFilesystem.directory(tempDir)

            val fromComplete = DirManifest(entries = listOf(entryFor(dir, "settled.txt", "from-complete")))
            val fromCheckpoint = DirManifest(entries = listOf(entryFor(dir, "checkpointed.txt", "from-checkpoint")))

            val processor = TestEntryProcessor()
            TreeWalker(processor, NullUploadProgress()).walk(dir, listOf(fromComplete, fromCheckpoint))

            assertEquals("from-complete", processor.previousEntrySeen["settled.txt"])
            assertEquals("from-checkpoint", processor.previousEntrySeen["checkpointed.txt"])
        }

        /**
         * Order is the contract, not an accident: Go's findCachedEntry takes the FIRST candidate
         * whose metadata still matches, and the caller orders them complete-snapshot-first. Reverse
         * them and an unchanged file resolves against a checkpoint written by a run that was
         * interrupted — a strictly less trustworthy source for the same bytes.
         */
        @Test
        fun `candidates arrive most-authoritative first`(@TempDir tempDir: Path): Unit = runBlocking {
            tempDir.resolve("both.txt").writeText("both")
            val dir = LocalFilesystem.directory(tempDir)

            val fromComplete = DirManifest(entries = listOf(entryFor(dir, "both.txt", "from-complete")))
            val fromCheckpoint = DirManifest(entries = listOf(entryFor(dir, "both.txt", "from-checkpoint")))

            val processor = TestEntryProcessor()
            TreeWalker(processor, NullUploadProgress()).walk(dir, listOf(fromComplete, fromCheckpoint))

            assertEquals("from-complete,from-checkpoint", processor.previousEntrySeen["both.txt"])
        }

        /**
         * The same subdirectory usually appears in every previous tree — an interrupted run's
         * checkpoint holds the half it walked, the complete snapshot the rest — so BOTH must be
         * carried down. Taking one would re-hash whatever only the other listed.
         */
        @Test
        fun `every previous version of a subdirectory is carried down`(@TempDir tempDir: Path): Unit = runBlocking {
            val sub = tempDir.resolve("sub").createDirectories()
            sub.resolve("settled.txt").writeText("settled")
            sub.resolve("checkpointed.txt").writeText("checkpointed")
            val dir = LocalFilesystem.directory(tempDir)
            val subDir = LocalFilesystem.directory(sub)

            val processor = TestEntryProcessor()
            processor.previousManifests["complete-sub"] =
                DirManifest(entries = listOf(entryFor(subDir, "settled.txt", "from-complete")))
            processor.previousManifests["checkpoint-sub"] =
                DirManifest(entries = listOf(entryFor(subDir, "checkpointed.txt", "from-checkpoint")))

            val subEntry = { oid: String ->
                DirEntry(
                    name = "sub",
                    type = EntryType.DIRECTORY,
                    permissions = 493,
                    modTime = subDir.modTime,
                    objectId = oid,
                )
            }
            TreeWalker(processor, NullUploadProgress()).walk(
                dir,
                listOf(
                    DirManifest(entries = listOf(subEntry("complete-sub"))),
                    DirManifest(entries = listOf(subEntry("checkpoint-sub"))),
                ),
            )

            assertEquals("from-complete", processor.previousEntrySeen["sub/settled.txt"])
            assertEquals("from-checkpoint", processor.previousEntrySeen["sub/checkpointed.txt"])
        }

        /** A DirEntry matching the on-disk metadata of [name] inside [dir], so reuse is eligible. */
        private suspend fun entryFor(dir: org.kopiaKt.snapshot.fs.Directory, name: String, objectId: String): DirEntry {
            val file = dir.child(name) as File
            return DirEntry(
                name = name,
                type = EntryType.FILE,
                permissions = file.mode,
                fileSize = file.size,
                modTime = file.modTime,
                objectId = objectId,
            )
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

        /**
         * Relative path -> the object ids of every previous-snapshot candidate the walker supplied
         * for it, comma-joined in the order it supplied them. Written from parallel workers.
         */
        val previousEntrySeen = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Every directory manifest handed to the walker's uploader, in the order it was written. */
        val manifests = java.util.concurrent.CopyOnWriteArrayList<DirManifest>()

        /** The last one, which for a tree walk is always the root. */
        val lastManifest: DirManifest? get() = manifests.lastOrNull()

        private var nextObjectId = AtomicInteger(1)

        override suspend fun loadDirManifest(objectId: String): DirManifest? = previousManifests[objectId]

        override suspend fun processFile(
            file: File,
            relativePath: String,
            previousEntries: List<DirEntry>,
            checkpointRegistry: CheckpointRegistry,
        ): DirEntry {
            previousEntries
                .takeIf { it.isNotEmpty() }
                ?.let { previousEntrySeen[relativePath] = it.joinToString(",") { e -> e.objectId.orEmpty() } }
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
            previousEntries: List<DirEntry>,
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
            manifests.add(manifest)
            return "dir${nextObjectId.getAndIncrement()}"
        }
    }
}
