package org.kopiaKt.snapshot.restore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.snapshot.testutil.MockDirectory
import org.kopiaKt.snapshot.testutil.MockFile
import org.kopiaKt.snapshot.testutil.SlowMockFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

@DisplayName("Interrupted Restore Recovery")
class InterruptedRestoreTest {

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
                overwriteSymlinks = true,
            ),
        )
        progress = CountingRestoreProgress()
    }

    @AfterEach
    fun teardown() {
        output.close()
    }

    @Test
    fun `should complete restore after previous partial restore`() = runBlocking {
        val fileContent = ByteArray(1000) { it.toByte() }
        val files = (1..10).map { i ->
            SlowMockFile("file$i.txt", fileContent, delayMs = 20)
        }
        val rootDir = MockDirectory(name = "", entries = files)

        // First restore: start and cancel partway through
        val restorer1 = SnapshotRestorer(
            output,
            options = RestoreOptions(parallel = 1),
            progress = progress,
        )
        val job = async {
            restorer1.restore(rootDir)
        }
        delay(80)
        restorer1.cancel()
        job.await()

        // Second restore: create fresh output and progress, run to completion
        output.close()
        val output2 = FilesystemOutput(
            tempDir,
            FilesystemOutputOptions(
                overwriteDirectories = true,
                overwriteFiles = true,
                overwriteSymlinks = true,
            ),
        )
        val progress2 = CountingRestoreProgress()
        val restorer2 = SnapshotRestorer(
            output2,
            options = RestoreOptions(parallel = 1),
            progress = progress2,
        )
        restorer2.restore(rootDir)
        output2.close()

        // Verify all 10 files exist with correct content
        for (i in 1..10) {
            val file = tempDir.resolve("file$i.txt")
            assertThat(file.exists()).isTrue()
            assertThat(file.isRegularFile()).isTrue()
            assertThat(file.readBytes()).isEqualTo(fileContent)
        }
    }

    @Test
    fun `should not create duplicate files on retry`() = runBlocking {
        val contents = (1..5).map { i -> "content-$i".toByteArray() }
        val files = (1..5).map { i ->
            SlowMockFile("file$i.txt", contents[i - 1], delayMs = 20)
        }
        val rootDir = MockDirectory(name = "", entries = files)

        // First restore: start and cancel partway through
        val restorer1 = SnapshotRestorer(
            output,
            options = RestoreOptions(parallel = 1),
            progress = progress,
        )
        val job = async {
            restorer1.restore(rootDir)
        }
        delay(40)
        restorer1.cancel()
        job.await()

        // Second restore: retry with same tempDir and overwrite=true
        output.close()
        val output2 = FilesystemOutput(
            tempDir,
            FilesystemOutputOptions(
                overwriteDirectories = true,
                overwriteFiles = true,
                overwriteSymlinks = true,
            ),
        )
        val progress2 = CountingRestoreProgress()
        val restorer2 = SnapshotRestorer(
            output2,
            options = RestoreOptions(parallel = 1),
            progress = progress2,
        )
        restorer2.restore(rootDir)
        output2.close()

        // Count actual files in tempDir (should be exactly 5, no duplicates)
        val fileCount = Files.list(tempDir).use { stream ->
            stream.filter { it.isRegularFile() }.count()
        }
        assertThat(fileCount).isEqualTo(5)

        // Verify each file has correct content
        for (i in 1..5) {
            val file = tempDir.resolve("file$i.txt")
            assertThat(file.exists()).isTrue()
            assertThat(file.readBytes()).isEqualTo(contents[i - 1])
        }
    }

    @Test
    fun `should handle partially written file on retry`() = runBlocking {
        val correctContent = "correct-full-content-here".toByteArray()

        // Manually write a truncated/incorrect file to tempDir
        val truncatedFile = tempDir.resolve("important.txt")
        truncatedFile.writeBytes("trunc".toByteArray())

        val rootDir = MockDirectory(
            name = "",
            entries = listOf(
                MockFile("important.txt", correctContent),
            ),
        )

        // Restore with overwrite=true should replace the truncated file
        val restorer = SnapshotRestorer(output, progress = progress)
        restorer.restore(rootDir)

        // Verify the file now has the correct content
        assertThat(truncatedFile.exists()).isTrue()
        assertThat(truncatedFile.readBytes()).isEqualTo(correctContent)
    }

    @Test
    fun `should restore remaining files in incremental mode`() = runBlocking {
        val now = Instant.now()
        val fileContents = (1..5).map { i -> "file-content-$i".toByteArray() }

        // Pre-create 2 of the 5 files with matching content and mod time
        for (i in 1..2) {
            val filePath = tempDir.resolve("file$i.txt")
            filePath.writeBytes(fileContents[i - 1])
            Files.setLastModifiedTime(filePath, FileTime.from(now))
        }

        val mockFiles = (1..5).map { i ->
            MockFile("file$i.txt", fileContents[i - 1], modTime = now)
        }
        val rootDir = MockDirectory(name = "", entries = mockFiles)

        val restorer = SnapshotRestorer(
            output,
            options = RestoreOptions(incremental = true),
            progress = progress,
        )
        val stats = restorer.restore(rootDir)

        // The 2 pre-existing files should have been skipped
        assertThat(stats.skippedCount).isEqualTo(2)
        assertThat(stats.restoredFileCount).isLessThan(5)

        // All 5 files should exist with correct content
        for (i in 1..5) {
            val file = tempDir.resolve("file$i.txt")
            assertThat(file.exists()).isTrue()
            assertThat(file.readBytes()).isEqualTo(fileContents[i - 1])
        }
    }

    @Test
    fun `coroutine cancellation must not be swallowed as an ignored restore error`() = runBlocking {
        val fileContent = ByteArray(1000) { it.toByte() }
        val files = (1..20).map { i -> SlowMockFile("file$i.txt", fileContent, delayMs = 30) }
        val rootDir = MockDirectory(name = "", entries = files)

        val restorer = SnapshotRestorer(
            output,
            options = RestoreOptions(parallel = 1, ignoreErrors = true),
            progress = progress,
        )
        val job = async { restorer.restore(rootDir) }
        delay(100)
        job.cancel()
        job.join()

        // With ignoreErrors=true, a swallowed CancellationException makes every remaining entry
        // fail-and-be-ignored, so the restore burns through the whole tree and reports success.
        assertThat(progress.snapshot().ignoredErrorCount).isEqualTo(0)
        assertThat(progress.snapshot().restoredFileCount).isLessThan(20)
    }
}
