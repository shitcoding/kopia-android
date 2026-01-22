package org.kopiaKt.snapshot.restore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.DirectoryIterator
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.kopiaKt.snapshot.fs.Symlink
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readBytes
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.writeText

class SnapshotRestorerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var output: FilesystemOutput
    private lateinit var progress: CountingRestoreProgress

    @BeforeEach
    fun setup() {
        output = FilesystemOutput(
            tempDir,
            FilesystemOutputOptions(
                overwriteDirectories = true,
                overwriteFiles = true,
                overwriteSymlinks = true
            )
        )
        progress = CountingRestoreProgress()
    }

    @AfterEach
    fun teardown() {
        output.close()
    }

    @Test
    fun `restore empty directory`() = runBlocking {
        val rootDir = MockDirectory(
            name = "",
            entries = emptyList()
        )

        val restorer = SnapshotRestorer(output, progress = progress)
        val stats = restorer.restore(rootDir)

        assertThat(tempDir.exists()).isTrue()
        assertThat(stats.restoredDirCount).isEqualTo(1) // root dir
        assertThat(stats.restoredFileCount).isEqualTo(0)
    }

    @Test
    fun `restore single file`() = runBlocking {
        val fileContent = "Hello, World!"
        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                MockFile("test.txt", fileContent.toByteArray())
            )
        )

        val restorer = SnapshotRestorer(output, progress = progress)
        val stats = restorer.restore(rootDir)

        val restoredFile = tempDir.resolve("test.txt")
        assertThat(restoredFile.exists()).isTrue()
        assertThat(restoredFile.isRegularFile()).isTrue()
        assertThat(restoredFile.readBytes().toString(Charsets.UTF_8)).isEqualTo(fileContent)
        assertThat(stats.restoredFileCount).isEqualTo(1)
    }

    @Test
    fun `restore nested directories`() = runBlocking {
        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                MockDirectory(
                    name = "level1",
                    entries = listOf(
                        MockDirectory(
                            name = "level2",
                            entries = listOf(
                                MockFile("deep.txt", "deep content".toByteArray())
                            )
                        )
                    )
                )
            )
        )

        val restorer = SnapshotRestorer(output, progress = progress)
        val stats = restorer.restore(rootDir)

        assertThat(tempDir.resolve("level1").isDirectory()).isTrue()
        assertThat(tempDir.resolve("level1/level2").isDirectory()).isTrue()
        assertThat(tempDir.resolve("level1/level2/deep.txt").isRegularFile()).isTrue()
        assertThat(tempDir.resolve("level1/level2/deep.txt").readBytes().toString(Charsets.UTF_8)).isEqualTo("deep content")
        assertThat(stats.restoredDirCount).isEqualTo(3) // root + level1 + level2
        assertThat(stats.restoredFileCount).isEqualTo(1)
    }

    @Test
    fun `restore symlinks`() = runBlocking {
        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                MockFile("target.txt", "target content".toByteArray()),
                MockSymlink("link.txt", "target.txt")
            )
        )

        val restorer = SnapshotRestorer(output, progress = progress)
        val stats = restorer.restore(rootDir)

        val link = tempDir.resolve("link.txt")
        assertThat(link.isSymbolicLink()).isTrue()
        assertThat(link.readSymbolicLink().toString()).isEqualTo("target.txt")
        assertThat(stats.restoredFileCount).isEqualTo(1)
        assertThat(stats.restoredSymlinkCount).isEqualTo(1)
    }

    @Test
    fun `restore with parallel workers`() = runBlocking {
        val files = (1..10).map { i ->
            MockFile("file$i.txt", "content $i".toByteArray())
        }
        val rootDir = MockDirectory(name = "", entries = files)

        val restorer = SnapshotRestorer(
            output,
            options = RestoreOptions(parallel = 4),
            progress = progress
        )
        val stats = restorer.restore(rootDir)

        assertThat(stats.restoredFileCount).isEqualTo(10)
        (1..10).forEach { i ->
            val file = tempDir.resolve("file$i.txt")
            assertThat(file.exists()).isTrue()
            assertThat(file.readBytes().toString(Charsets.UTF_8)).isEqualTo("content $i")
        }
    }

    @Test
    fun `restore incremental skips existing files`() = runBlocking {
        // Pre-create a file
        val existingFile = tempDir.resolve("existing.txt")
        existingFile.writeText("existing content")

        val fileTime = existingFile.toFile().lastModified()

        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                MockFile(
                    name = "existing.txt",
                    content = "existing content".toByteArray(),
                    modTime = Instant.ofEpochMilli(fileTime)
                ),
                MockFile("new.txt", "new content".toByteArray())
            )
        )

        val restorer = SnapshotRestorer(
            output,
            options = RestoreOptions(incremental = true),
            progress = progress
        )
        val stats = restorer.restore(rootDir)

        assertThat(stats.skippedCount).isEqualTo(1) // existing.txt skipped
        assertThat(stats.restoredFileCount).isEqualTo(1) // new.txt restored
    }

    @Test
    fun `restore with deleteExtra removes extra files`() = runBlocking {
        // Pre-create an extra file
        val extraFile = tempDir.resolve("extra.txt")
        extraFile.writeText("should be deleted")

        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                MockFile("keep.txt", "keep this".toByteArray())
            )
        )

        val restorer = SnapshotRestorer(
            output,
            options = RestoreOptions(deleteExtra = true),
            progress = progress
        )
        val stats = restorer.restore(rootDir)

        assertThat(tempDir.resolve("keep.txt").exists()).isTrue()
        assertThat(extraFile.exists()).isFalse()
        assertThat(stats.deletedFilesCount).isEqualTo(1)
    }

    @Test
    fun `restore with deleteExtra removes extra directories`() = runBlocking {
        // Pre-create an extra directory
        Files.createDirectories(tempDir.resolve("extradir"))
        tempDir.resolve("extradir/file.txt").writeText("in extra dir")

        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                MockDirectory("keepdir", listOf(
                    MockFile("file.txt", "in keep dir".toByteArray())
                ))
            )
        )

        val restorer = SnapshotRestorer(
            output,
            options = RestoreOptions(deleteExtra = true),
            progress = progress
        )
        val stats = restorer.restore(rootDir)

        assertThat(tempDir.resolve("keepdir").isDirectory()).isTrue()
        assertThat(tempDir.resolve("extradir").exists()).isFalse()
        assertThat(stats.deletedDirCount).isEqualTo(1)
    }

    @Test
    fun `restore can be cancelled`() = runBlocking {
        // Use slow files to ensure we have time to cancel
        val files = (1..100).map { i ->
            SlowMockFile("file$i.txt", ByteArray(1000) { it.toByte() }, delayMs = 10)
        }
        val rootDir = MockDirectory(name = "", entries = files)

        val restorer = SnapshotRestorer(
            output,
            options = RestoreOptions(parallel = 1),
            progress = progress
        )

        // Launch restore in background and cancel after a short delay
        val job = async {
            restorer.restore(rootDir)
        }

        // Give it time to start, then cancel
        delay(50)
        restorer.cancel()

        val stats = job.await()

        // Should have stopped early (some files may have been processed)
        assertThat(stats.restoredFileCount).isAtMost(99)
    }

    @Test
    fun `restore ignores errors when configured`() = runBlocking {
        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                FailingFile("bad.txt", RuntimeException("Simulated failure")),
                MockFile("good.txt", "good content".toByteArray())
            )
        )

        val restorer = SnapshotRestorer(
            output,
            options = RestoreOptions(ignoreErrors = true),
            progress = progress
        )
        val stats = restorer.restore(rootDir)

        assertThat(tempDir.resolve("good.txt").exists()).isTrue()
        assertThat(stats.ignoredErrorCount).isEqualTo(1)
    }

    @Test
    fun `progress reports file sizes correctly`() = runBlocking {
        val file1Content = ByteArray(1000) { 1 }
        val file2Content = ByteArray(2000) { 2 }

        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                MockFile("file1.bin", file1Content),
                MockFile("file2.bin", file2Content)
            )
        )

        val restorer = SnapshotRestorer(output, progress = progress)
        val stats = restorer.restore(rootDir)

        assertThat(stats.enqueuedTotalFileSize).isEqualTo(3000)
        assertThat(stats.restoredTotalFileSize).isEqualTo(3000)
    }

    // --- Mock Implementations ---

    private open class MockEntry(
        override val name: String,
        override val type: EntryType,
        override val size: Long = 0,
        override val modTime: Instant = Instant.now(),
        override val mode: Int = 420, // 0o644
        override val owner: OwnerInfo = OwnerInfo.EMPTY,
        override val device: DeviceInfo = DeviceInfo.EMPTY,
        override val localFilesystemPath: String = ""
    ) : Entry

    private class MockFile(
        name: String,
        private val content: ByteArray,
        modTime: Instant = Instant.now(),
        mode: Int = 420
    ) : MockEntry(name, EntryType.FILE, content.size.toLong(), modTime, mode), File {
        override suspend fun open(): InputStream = content.inputStream()
    }

    private class SlowMockFile(
        name: String,
        private val content: ByteArray,
        private val delayMs: Long,
        modTime: Instant = Instant.now(),
        mode: Int = 420
    ) : MockEntry(name, EntryType.FILE, content.size.toLong(), modTime, mode), File {
        override suspend fun open(): InputStream {
            kotlinx.coroutines.delay(delayMs)
            return content.inputStream()
        }
    }

    private class MockDirectory(
        name: String,
        private val entries: List<Entry>,
        modTime: Instant = Instant.now(),
        mode: Int = 493 // 0o755
    ) : MockEntry(name, EntryType.DIRECTORY, 0, modTime, mode), Directory {
        override suspend fun child(name: String): Entry? = entries.find { it.name == name }
        override suspend fun iterate(): DirectoryIterator = MockIterator(entries)
        override fun supportsMultipleIterations(): Boolean = true
    }

    private class MockSymlink(
        name: String,
        private val target: String,
        modTime: Instant = Instant.now()
    ) : MockEntry(name, EntryType.SYMLINK, 0, modTime), Symlink {
        override suspend fun readlink(): String = target
        override suspend fun resolve(): Entry? = null
    }

    private class FailingFile(
        name: String,
        private val error: Throwable
    ) : MockEntry(name, EntryType.FILE, 100), File {
        override suspend fun open(): InputStream = throw error
    }

    private class MockIterator(entries: List<Entry>) : DirectoryIterator {
        private val iterator = entries.iterator()
        override suspend fun next(): Entry? = if (iterator.hasNext()) iterator.next() else null
        override fun close() {}
    }
}
