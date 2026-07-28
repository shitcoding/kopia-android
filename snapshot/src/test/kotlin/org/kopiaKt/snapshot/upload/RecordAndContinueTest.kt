package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.policy.ErrorHandlingPolicy
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * One unreadable file used to kill an entire backup: `TreeWalker` threw on the first non-ignored
 * error, so on a phone — where a single entry can fail for reasons that have nothing to do with the
 * other ten thousand — the whole run produced nothing.
 *
 * Go records the failed entry, carries on, and saves a COMPLETE manifest carrying the error counts;
 * the command then reports failure afterwards if any were fatal. `failFast` is what stops early, and
 * it is opt-in.
 */
class RecordAndContinueTest {

    @BeforeEach
    fun requireUnprivileged(@TempDir tempDir: Path) {
        // chmod 000 does not stop root, which would silently turn these into tests of nothing.
        val probe = tempDir.resolve("probe").apply {
            writeText("x")
            setPosixFilePermissions(emptySet())
        }
        val stillReadable = runCatching { probe.toFile().readBytes() }.isSuccess
        assumeFalse(stillReadable, "running as root: file permissions cannot be used to force an error")
    }

    @Test
    fun `an unreadable file does not abort the rest of the backup`(@TempDir tempDir: Path) = runBlocking {
        val processor = CountingProcessor()
        val walker = TreeWalker(processor, NullUploadProgress())

        walker.walk(LocalFilesystem.directory(oneGoodOneUnreadable(tempDir)))

        assertThat(processor.readableFilesProcessed).isEqualTo(1)
        assertThat(processor.lastManifest).isNotNull()
    }

    @Test
    fun `the failed entry is recorded against the snapshot`(@TempDir tempDir: Path) = runBlocking {
        val processor = CountingProcessor()
        val walker = TreeWalker(processor, NullUploadProgress())

        walker.walk(LocalFilesystem.directory(oneGoodOneUnreadable(tempDir)))

        // A silently-dropped file would be worse than a failed backup.
        val failed = processor.lastManifest?.summary?.failedEntries.orEmpty()
        assertThat(failed.map { it.entryPath }).containsExactly("unreadable.txt")
        assertThat(processor.lastManifest?.summary?.fatalErrorCount).isEqualTo(1)
        assertThat(processor.lastManifest?.summary?.ignoredErrorCount).isEqualTo(0)
    }

    @Test
    fun `failFast still stops on the first error`(@TempDir tempDir: Path) {
        val walker = TreeWalker(CountingProcessor(), NullUploadProgress(), failFast = true)

        assertThrows<TreeWalker.FatalErrorException> {
            runBlocking { walker.walk(LocalFilesystem.directory(oneGoodOneUnreadable(tempDir))) }
        }
    }

    @Test
    fun `an ignored error counts as ignored, not fatal`(@TempDir tempDir: Path) = runBlocking {
        val processor = CountingProcessor()
        val walker = TreeWalker(
            processor,
            NullUploadProgress(),
            errorPolicy = ErrorHandlingPolicy(ignoreFileErrors = true),
        )

        walker.walk(LocalFilesystem.directory(oneGoodOneUnreadable(tempDir)))

        assertThat(processor.lastManifest?.summary?.ignoredErrorCount).isEqualTo(1)
        assertThat(processor.lastManifest?.summary?.fatalErrorCount).isEqualTo(0)
    }

    /**
     * Go: "always fail if the top level directory can't be read, otherwise a meaningless, empty
     * snapshot is created that can't be restored". Record-and-continue must not extend to the root.
     */
    @Test
    fun `an unreadable root aborts instead of producing an empty snapshot`(@TempDir tempDir: Path) {
        val root = tempDir.resolve("root").also { it.toFile().mkdirs() }
        root.resolve("a.txt").writeText("data")
        root.setPosixFilePermissions(emptySet())

        val processor = CountingProcessor()
        assertThrows<TreeWalker.DirectoryReadException> {
            runBlocking { TreeWalker(processor, NullUploadProgress()).walk(LocalFilesystem.directory(root)) }
        }

        // Nothing uploaded: an empty "complete" snapshot would look like a successful backup of a
        // folder the user had just lost access to.
        assertThat(processor.lastManifest).isNull()
        root.setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
    }

    /**
     * A subdirectory that cannot be listed is the parent's failed entry, and contributes no entry of
     * its own — a phantom empty directory would restore as data loss dressed up as data.
     */
    @Test
    fun `an unreadable subdirectory is recorded by its parent and adds no entry`(@TempDir tempDir: Path) = runBlocking {
        tempDir.resolve("keep.txt").writeText("data")
        val locked = tempDir.resolve("locked").also { it.toFile().mkdirs() }
        locked.resolve("hidden.txt").writeText("data")
        locked.setPosixFilePermissions(emptySet())

        val processor = CountingProcessor()
        TreeWalker(processor, NullUploadProgress()).walk(LocalFilesystem.directory(tempDir))

        val root = processor.lastManifest
        assertThat(root?.entries?.map { it.name }).containsExactly("keep.txt")
        assertThat(root?.summary?.failedEntries?.map { it.entryPath }).containsExactly("locked")
        locked.setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
    }

    private fun oneGoodOneUnreadable(tempDir: Path): Path {
        tempDir.resolve("readable.txt").writeText("fine")
        tempDir.resolve("unreadable.txt").apply {
            writeText("secret")
            setPosixFilePermissions(emptySet())
        }
        return tempDir
    }

    /** Reads each file so an unreadable one really throws, and keeps the last manifest built. */
    private class CountingProcessor : EntryProcessor {
        var readableFilesProcessed = 0
        var lastManifest: DirManifest? = null

        override suspend fun loadDirManifest(objectId: String): DirManifest? = null

        override suspend fun processFile(
            file: org.kopiaKt.snapshot.fs.File,
            relativePath: String,
            previousEntry: DirEntry?,
        ): DirEntry {
            file.open().use { it.readBytes() }
            readableFilesProcessed++
            return DirEntry(
                name = file.name,
                type = EntryType.FILE,
                permissions = file.mode,
                fileSize = file.size,
                modTime = Instant.EPOCH,
                objectId = "k$readableFilesProcessed",
            )
        }

        override suspend fun processSymlink(
            symlink: org.kopiaKt.snapshot.fs.Symlink,
            relativePath: String,
            previousEntry: DirEntry?,
        ): DirEntry = DirEntry(
            name = symlink.name,
            type = EntryType.SYMLINK,
            permissions = symlink.mode,
            fileSize = 0,
            modTime = Instant.EPOCH,
            objectId = "",
        )

        override suspend fun uploadDirectoryManifest(manifest: DirManifest): String {
            lastManifest = manifest
            return "kd1"
        }
    }
}
