package org.kopiaKt.android.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for backup error handling across BackupSession, TaskManager, and BackupWorker.
 *
 * Covers:
 * - Source path validation (nonexistent, empty directory, file-instead-of-dir)
 * - Exception handling and checkpoint persistence on failure
 * - BackupSessionResult sealed class coverage (Failed, Cancelled, Success)
 * - TaskManager status integration for failed/cancelled tasks
 * - BackupWorker retry vs failure decisions
 * - Callback invocation on error and success
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("Backup Error Handling")
class BackupErrorHandlingTest {

    private lateinit var context: Context
    private val tempDirs = mutableListOf<Path>()

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @AfterEach
    fun cleanupTempDirs() {
        tempDirs.forEach { dir ->
            dir.toFile().deleteRecursively()
        }
        tempDirs.clear()
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private fun createTempDir(prefix: String = "backup-test"): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }

    /**
     * Creates a mock DirectRepository whose newWriter() returns a mock
     * RepositoryWriter. Both are relaxed so tests only set up needed stubs.
     */
    private fun mockRepository(): Pair<DirectRepository, RepositoryWriter> {
        val writer = mockk<RepositoryWriter>(relaxed = true)
        val repo = mockk<DirectRepository>(relaxed = true)
        coEvery { repo.newWriter(any()) } returns writer
        return repo to writer
    }

    /**
     * Creates a MockK-based CheckpointStore that stores checkpoints in memory.
     * Uses a real map underneath, captured via coEvery answer blocks.
     */
    private fun mockCheckpointStore(): Pair<CheckpointStore, MutableMap<String, BackupCheckpoint>> {
        val store = mutableMapOf<String, BackupCheckpoint>()
        val mock = mockk<CheckpointStore>(relaxed = true)

        coEvery { mock.saveCheckpoint(any()) } answers {
            val cp = firstArg<BackupCheckpoint>()
            store[cp.sourceId] = cp
        }

        coEvery { mock.getCheckpoint(any()) } answers {
            val sourceId = firstArg<String>()
            val cp = store[sourceId]
            if (cp != null) CheckpointResult.Found(cp) else CheckpointResult.NotFound
        }

        coEvery { mock.clearCheckpoint(any()) } answers {
            store.remove(firstArg<String>())
            Unit
        }

        return mock to store
    }

    // ======================================================================
    // BackupSession: Source Path Errors
    // ======================================================================

    @Nested
    @DisplayName("Source Path Errors")
    inner class SourcePathErrors {

        @Test
        fun `backup with nonexistent source path fails with clear error`(): Unit = runBlocking {
            val (repo, _) = mockRepository()
            val (checkpointStore, _) = mockCheckpointStore()

            val config = BackupSessionConfig(
                sourcePath = "/nonexistent/path/does/not/exist",
                sourceId = "test-source-1",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                context = context,
            )

            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Failed::class.java)
            val failed = result as BackupSessionResult.Failed
            assertThat(failed.error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failed.error.message).contains("does not exist")
            assertThat(failed.error.message).contains("/nonexistent/path/does/not/exist")
        }

        @Test
        fun `backup with empty source directory succeeds with zero files`(): Unit = runBlocking {
            val tempDir = createTempDir("empty-dir")
            val (repo, writer) = mockRepository()
            val (checkpointStore, _) = mockCheckpointStore()

            val manifestId = ManifestId.generate()
            coEvery {
                writer.putManifest(any(), any<SnapshotManifest>(), any())
            } returns manifestId
            coEvery { writer.findManifests(any()) } returns emptyList()

            val config = BackupSessionConfig(
                sourcePath = tempDir.toAbsolutePath().toString(),
                sourceId = "test-source-empty",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                context = context,
            )

            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Success::class.java)
            val success = result as BackupSessionResult.Success
            assertThat(success.counters.totalHashedFiles).isEqualTo(0)
            assertThat(success.counters.totalCachedFiles).isEqualTo(0)
        }

        @Test
        fun `backup with file path instead of directory fails`(): Unit = runBlocking {
            val tempDir = createTempDir("file-test")
            val filePath = tempDir.resolve("regular-file.txt")
            Files.write(filePath, "content".toByteArray())

            val (repo, _) = mockRepository()
            val (checkpointStore, _) = mockCheckpointStore()

            val config = BackupSessionConfig(
                sourcePath = filePath.toAbsolutePath().toString(),
                sourceId = "test-source-file",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                context = context,
            )

            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Failed::class.java)
            val failed = result as BackupSessionResult.Failed
            assertThat(failed.error).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failed.error.message).contains("not a directory")
        }
    }

    // ======================================================================
    // BackupSession: Exception Handling & Checkpoint Persistence
    // ======================================================================

    @Nested
    @DisplayName("Exception Handling and Checkpoints")
    inner class ExceptionHandlingAndCheckpoints {

        @Test
        fun `BackupSession result is Failed on exception from writer creation`(): Unit = runBlocking {
            val repo = mockk<DirectRepository>(relaxed = true)
            coEvery { repo.newWriter(any()) } throws IOException("Storage unavailable")

            val (checkpointStore, _) = mockCheckpointStore()

            val config = BackupSessionConfig(
                sourcePath = "/tmp/test-dir",
                sourceId = "test-source-ex",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                context = context,
            )

            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Failed::class.java)
            val failed = result as BackupSessionResult.Failed
            assertThat(failed.error).isInstanceOf(IOException::class.java)
            assertThat(failed.error.message).isEqualTo("Storage unavailable")
        }

        @Test
        fun `backup saves checkpoint on failure with error info`(): Unit = runBlocking {
            val tempDir = createTempDir("checkpoint-test")
            val (repo, writer) = mockRepository()
            val (checkpointStore, cpStore) = mockCheckpointStore()

            coEvery { writer.findManifests(any()) } throws RuntimeException("Index corrupted")

            val config = BackupSessionConfig(
                sourcePath = tempDir.toAbsolutePath().toString(),
                sourceId = "test-source-cp",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                context = context,
            )

            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Failed::class.java)
            val failed = result as BackupSessionResult.Failed
            assertThat(failed.checkpointSaved).isTrue()

            // Verify checkpoint was stored with error info
            val checkpoint = cpStore["test-source-cp"]
            assertThat(checkpoint).isNotNull()
            assertThat(checkpoint!!.sourceId).isEqualTo("test-source-cp")
            assertThat(checkpoint.lastError).isEqualTo("Index corrupted")
        }

        @Test
        fun `BackupSession result includes checkpointSaved true on failure`(): Unit = runBlocking {
            val tempDir = createTempDir("cp-flag-test")
            val (repo, writer) = mockRepository()
            val (checkpointStore, _) = mockCheckpointStore()

            coEvery { writer.findManifests(any()) } throws IOException("Disk full")

            val config = BackupSessionConfig(
                sourcePath = tempDir.toAbsolutePath().toString(),
                sourceId = "test-cp-flag",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                context = context,
            )

            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Failed::class.java)
            val failed = result as BackupSessionResult.Failed
            assertThat(failed.checkpointSaved).isTrue()
        }
    }

    // ======================================================================
    // BackupSession: Cancellation
    // ======================================================================

    @Nested
    @DisplayName("Cancellation Handling")
    inner class CancellationHandling {

        @Test
        fun `cancelled backup sets isCancelled flag`(): Unit = runBlocking {
            val tempDir = createTempDir("cancel-test")
            Files.write(tempDir.resolve("file1.txt"), "content-1".toByteArray())
            Files.write(tempDir.resolve("file2.txt"), "content-2".toByteArray())

            val (repo, writer) = mockRepository()
            val (checkpointStore, _) = mockCheckpointStore()

            // Slow down findManifests so cancel has time to fire
            coEvery { writer.findManifests(any()) } coAnswers {
                delay(500)
                emptyList()
            }

            val config = BackupSessionConfig(
                sourcePath = tempDir.toAbsolutePath().toString(),
                sourceId = "test-cancel",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                context = context,
            )

            launch {
                delay(50)
                session.cancel()
            }

            session.run()

            assertThat(session.isCancelled()).isTrue()
        }
    }

    // ======================================================================
    // TaskManager: Status Integration
    // ======================================================================

    @Nested
    @DisplayName("Task Status Integration")
    inner class TaskStatusIntegration {

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun TestScope.createTaskManager(): TaskManager {
            val childScope = CoroutineScope(coroutineContext + SupervisorJob())
            return TaskManager(childScope)
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `failed backup sets task status to FAILED`(): Unit = runTest {
            val tm = createTaskManager()

            val taskId = tm.startTask(TaskKind.BACKUP, "failing backup") {
                throw RuntimeException("Storage backend unreachable")
            }

            advanceUntilIdle()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.FAILED)

            tm.shutdown()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `failed backup records error message in task info`(): Unit = runTest {
            val tm = createTaskManager()

            val taskId = tm.startTask(TaskKind.BACKUP, "error backup") {
                throw IOException("Permission denied: /restricted/path")
            }

            advanceUntilIdle()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.FAILED)
            assertThat(info.errorMessage).isEqualTo("Permission denied: /restricted/path")

            tm.shutdown()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `cancelled backup sets task status to CANCELED`(): Unit = runTest {
            val tm = createTaskManager()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "cancellable backup") { controller ->
                started.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started.await()

            tm.cancelTask(taskId)
            advanceUntilIdle()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.CANCELED)

            tm.shutdown()
        }
    }

    // ======================================================================
    // BackupSession: Callback Invocation
    // ======================================================================

    @Nested
    @DisplayName("Callback Invocation on Error")
    inner class CallbackInvocation {

        @Test
        fun `onComplete callback receives Failed result on exception`(): Unit = runBlocking {
            val tempDir = createTempDir("callback-fail")
            val (repo, writer) = mockRepository()
            val (checkpointStore, _) = mockCheckpointStore()

            coEvery { writer.findManifests(any()) } throws RuntimeException("Manifest store error")

            var capturedResult: BackupSessionResult? = null
            val callback = object : NullBackupSessionCallback() {
                override fun onComplete(result: BackupSessionResult) {
                    capturedResult = result
                }
            }

            val config = BackupSessionConfig(
                sourcePath = tempDir.toAbsolutePath().toString(),
                sourceId = "test-callback",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                callback = callback,
                context = context,
            )

            session.run()

            assertThat(capturedResult).isNotNull()
            assertThat(capturedResult).isInstanceOf(BackupSessionResult.Failed::class.java)
            val failed = capturedResult as BackupSessionResult.Failed
            assertThat(failed.error.message).isEqualTo("Manifest store error")
        }

        @Test
        fun `onComplete callback receives Success for valid empty dir`(): Unit = runBlocking {
            val tempDir = createTempDir("callback-success")
            val (repo, writer) = mockRepository()
            val (checkpointStore, _) = mockCheckpointStore()

            val manifestId = ManifestId.generate()
            coEvery {
                writer.putManifest(any(), any<SnapshotManifest>(), any())
            } returns manifestId
            coEvery { writer.findManifests(any()) } returns emptyList()

            var capturedResult: BackupSessionResult? = null
            val callback = object : NullBackupSessionCallback() {
                override fun onComplete(result: BackupSessionResult) {
                    capturedResult = result
                }
            }

            val config = BackupSessionConfig(
                sourcePath = tempDir.toAbsolutePath().toString(),
                sourceId = "test-callback-success",
            )

            val session = BackupSession(
                repository = repo,
                config = config,
                checkpointStore = checkpointStore,
                callback = callback,
                context = context,
            )

            session.run()

            assertThat(capturedResult).isNotNull()
            assertThat(capturedResult).isInstanceOf(BackupSessionResult.Success::class.java)
        }
    }

    // ======================================================================
    // BackupWorker: Retry vs Failure
    // ======================================================================

    @Nested
    @DisplayName("BackupWorker Retry Logic")
    inner class BackupWorkerRetryLogic {

        @BeforeEach
        fun workerSetup() {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            BackupWorker.repositoryProvider = null
        }

        @AfterEach
        fun workerTearDown() {
            BackupWorker.repositoryProvider = null
        }

        @Test
        fun `BackupWorker returns retry on transient failure at first attempt`(): Unit = runBlocking {
            BackupWorker.repositoryProvider = { _ ->
                throw IOException("Connection reset")
            }

            val worker = TestListenableWorkerBuilder<BackupWorker>(context)
                .setInputData(
                    workDataOf(
                        BackupWorker.KEY_SOURCE_ID to "retry-source",
                        BackupWorker.KEY_SOURCE_PATH to "/test/path",
                    ),
                )
                .build()

            val result = worker.doWork()

            // First attempt (runAttemptCount == 0 < MAX_RETRY_COUNT=3) -> retry
            assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        }

        @Test
        fun `BackupWorker returns failure after max retries`(): Unit = runBlocking {
            BackupWorker.repositoryProvider = { _ ->
                throw IOException("Persistent network failure")
            }

            val worker = TestListenableWorkerBuilder<BackupWorker>(context)
                .setInputData(
                    workDataOf(
                        BackupWorker.KEY_SOURCE_ID to "max-retry-source",
                        BackupWorker.KEY_SOURCE_PATH to "/test/path",
                    ),
                )
                .setRunAttemptCount(3) // At MAX_RETRY_COUNT
                .build()

            val result = worker.doWork()

            // runAttemptCount (3) >= MAX_RETRY_COUNT (3) -> failure, not retry
            assertThat(result).isNotEqualTo(ListenableWorker.Result.retry())
        }

        @Test
        fun `BackupWorker failure includes error message in output data`(): Unit = runBlocking {
            BackupWorker.repositoryProvider = { _ ->
                throw IOException("Disk quota exceeded")
            }

            val worker = TestListenableWorkerBuilder<BackupWorker>(context)
                .setInputData(
                    workDataOf(
                        BackupWorker.KEY_SOURCE_ID to "error-msg-source",
                        BackupWorker.KEY_SOURCE_PATH to "/test/path",
                    ),
                )
                .setRunAttemptCount(3)
                .build()

            val result = worker.doWork()

            assertThat(result).isEqualTo(
                ListenableWorker.Result.failure(
                    workDataOf(BackupWorker.KEY_ERROR to "Disk quota exceeded"),
                ),
            )
        }
    }
}
