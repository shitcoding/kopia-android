package org.kopiaKt.android.worker

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.policy.FilesPolicy
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.policy.PolicyManager
import org.kopiaKt.snapshot.upload.UploadCounters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Integration tests exercising the full backup session lifecycle pipeline.
 *
 * Unlike the individual component tests (TaskManagerTest, BackupSourceManagerTest,
 * BackupErrorHandlingTest), these tests wire multiple
 * components together:
 *
 *  - BackupSourceManager (real, in-memory)
 *  - TaskManager (real, coroutine-based)
 *  - BackupSession (real, with mocked repository)
 *
 * The repository layer (DirectRepository, RepositoryWriter) is mocked because
 * actual blob storage is out of scope for these lifecycle tests.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Backup Session Lifecycle")
class BackupSessionLifecycleTest {

    private lateinit var context: Context
    private val tempDirs = mutableListOf<Path>()

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @AfterEach
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun createTempDir(prefix: String = "lifecycle-test"): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }

    private fun mockRepository(): Pair<DirectRepository, RepositoryWriter> {
        val writer = mockk<RepositoryWriter>(relaxed = true)
        val repo = mockk<DirectRepository>(relaxed = true)
        coEvery { repo.newWriter(any()) } returns writer
        return repo to writer
    }

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

    /**
     * Creates a BackupSession wired to a real source manager and mock repository.
     * Returns a triple of (session, sourceManager, writer) for assertions.
     */
    private fun createSessionForSource(
        repo: DirectRepository,
        writer: RepositoryWriter,
        checkpointStore: CheckpointStore,
        sourcePath: String,
        sourceId: String,
        tags: Map<String, String> = emptyMap(),
        callback: BackupSessionCallback = NullBackupSessionCallback(),
    ): BackupSession {
        val config = BackupSessionConfig(
            sourcePath = sourcePath,
            sourceId = sourceId,
            tags = tags,
        )
        return BackupSession(
            repository = repo,
            config = config,
            checkpointStore = checkpointStore,
            callback = callback,
            context = context,
        )
    }

    private fun TestScope.createTaskManager(): TaskManager {
        val childScope = CoroutineScope(coroutineContext + SupervisorJob())
        return TaskManager(childScope)
    }

    /**
     * Stubs the writer so that findManifests returns empty (no prior snapshot)
     * and putManifest succeeds with a generated ID.
     */
    private fun stubWriterForSuccess(writer: RepositoryWriter): ManifestId {
        val manifestId = ManifestId.generate()
        coEvery { writer.findManifests(any()) } returns emptyList()
        coEvery {
            writer.putManifest(any(), any<SnapshotManifest>(), any())
        } returns manifestId
        return manifestId
    }

    // ==================================================================
    // Full Lifecycle Pipeline
    // ==================================================================

    @Nested
    @DisplayName("Effective policy")
    inner class EffectivePolicy {

        /**
         * Nothing used to resolve the source's policy, so the ignore rules a user configured in the
         * add-source wizard were silently inert and the files they excluded were backed up anyway.
         */
        @Test
        @DisplayName("the source's ignore rules exclude matching files")
        fun `the source's ignore rules exclude matching files`(): Unit = runBlocking {
            val tempDir = createTempDir("policy-applied")
            Files.write(tempDir.resolve("keep.txt"), "keep".toByteArray())
            Files.write(tempDir.resolve("drop.tmp"), "drop".toByteArray())

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()
            stubWriterForSuccess(writer)
            // Capture what is actually written: the directory manifest names the entries that
            // survived the ignore rules, which is the only real proof the policy was applied.
            val written = mutableListOf<ByteArray>()
            val fakeObjectId = ObjectId.parse("kaabbccddeeff00112233445566778899")
            coEvery { writer.writeObject(capture(written), any()) } returns fakeObjectId

            mockkObject(PolicyManager)
            try {
                coEvery { PolicyManager.getEffectivePolicy(any(), any()) } returns Policy(
                    filesPolicy = FilesPolicy(ignoreRules = listOf("*.tmp")),
                )

                val session = createSessionForSource(
                    repo = repo,
                    writer = writer,
                    checkpointStore = cpStore,
                    sourcePath = tempDir.toAbsolutePath().toString(),
                    sourceId = "local@phone:${'$'}{tempDir.toAbsolutePath()}",
                )
                session.run()
            } finally {
                unmockkObject(PolicyManager)
            }

            // Exactly one excluded: the .tmp matched the rule and the .txt did not.
            val manifests = written.map { it.toString(Charsets.UTF_8) }
            assertThat(manifests.any { it.contains("keep.txt") }).isTrue()
            assertThat(manifests.none { it.contains("drop.tmp") }).isTrue()
        }
    }

    @Nested
    @DisplayName("Full Lifecycle Pipeline")
    inner class FullLifecyclePipeline {

        @Test
        @DisplayName("create source then backup produces snapshot")
        fun `create source then backup produces snapshot`(): Unit = runBlocking {
            // Arrange: create a real source via BackupSourceManager
            val sourceManager = BackupSourceManager()
            val tempDir = createTempDir("source-backup")
            Files.write(tempDir.resolve("readme.txt"), "hello".toByteArray())

            val source = sourceManager.createSource(
                tempDir.toAbsolutePath().toString(),
                tempDir.toAbsolutePath().toString(),
                "Documents",
            )

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()
            val manifestId = stubWriterForSuccess(writer)

            // Act: run backup session using the source's path
            val session = createSessionForSource(
                repo = repo,
                writer = writer,
                checkpointStore = cpStore,
                sourcePath = source.path,
                sourceId = source.id,
            )
            val result = session.run()

            // Assert: result is Success and manifest was written
            assertThat(result).isInstanceOf(BackupSessionResult.Success::class.java)
            val success = result as BackupSessionResult.Success
            assertThat(success.manifestId).isEqualTo(manifestId)

            coVerify { writer.putManifest(any(), any<SnapshotManifest>(), any()) }
            coVerify { writer.flush() }
        }

        @Test
        @DisplayName("backup multiple sources independently")
        fun `backup multiple sources independently`(): Unit = runBlocking {
            val sourceManager = BackupSourceManager()
            val dir1 = createTempDir("source-1")
            val dir2 = createTempDir("source-2")
            Files.write(dir1.resolve("a.txt"), "aaa".toByteArray())
            Files.write(dir2.resolve("b.txt"), "bbb".toByteArray())

            val src1 = sourceManager.createSource(
                dir1.toAbsolutePath().toString(),
                dir1.toAbsolutePath().toString(),
                "Source 1",
            )
            val src2 = sourceManager.createSource(
                dir2.toAbsolutePath().toString(),
                dir2.toAbsolutePath().toString(),
                "Source 2",
            )

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()

            val manifestId1 = ManifestId.generate()
            val manifestId2 = ManifestId.generate()
            coEvery { writer.findManifests(any()) } returns emptyList()

            // Capture the two putManifest calls
            val capturedManifests = mutableListOf<SnapshotManifest>()
            coEvery {
                writer.putManifest(any(), capture(capturedManifests), any())
            } returnsMany listOf(manifestId1, manifestId2)

            // Act: backup each source independently
            val session1 = createSessionForSource(repo, writer, cpStore, src1.path, src1.id)
            val result1 = session1.run()

            val session2 = createSessionForSource(repo, writer, cpStore, src2.path, src2.id)
            val result2 = session2.run()

            // Assert: both succeed with different manifest IDs
            assertThat(result1).isInstanceOf(BackupSessionResult.Success::class.java)
            assertThat(result2).isInstanceOf(BackupSessionResult.Success::class.java)
            assertThat((result1 as BackupSessionResult.Success).manifestId).isEqualTo(manifestId1)
            assertThat((result2 as BackupSessionResult.Success).manifestId).isEqualTo(manifestId2)

            // Two different snapshot manifests were stored
            assertThat(capturedManifests).hasSize(2)
            assertThat(capturedManifests[0].source.path).isEqualTo(src1.path)
            assertThat(capturedManifests[1].source.path).isEqualTo(src2.path)
        }

        @Test
        @DisplayName("backup same source twice creates two snapshots")
        fun `backup same source twice creates two snapshots`(): Unit = runBlocking {
            val sourceManager = BackupSourceManager()
            val dir = createTempDir("incremental")
            Files.write(dir.resolve("file1.txt"), "original".toByteArray())

            val source = sourceManager.createSource(
                dir.toAbsolutePath().toString(),
                dir.toAbsolutePath().toString(),
                "Incremental",
            )

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()

            val manifestId1 = ManifestId.generate()
            val manifestId2 = ManifestId.generate()
            coEvery { writer.findManifests(any()) } returns emptyList()
            coEvery {
                writer.putManifest(any(), any<SnapshotManifest>(), any())
            } returnsMany listOf(manifestId1, manifestId2)

            // First backup
            val session1 = createSessionForSource(repo, writer, cpStore, source.path, source.id)
            val result1 = session1.run()

            // Modify a file
            Files.write(dir.resolve("file2.txt"), "new content".toByteArray())

            // Second backup
            val session2 = createSessionForSource(repo, writer, cpStore, source.path, source.id)
            val result2 = session2.run()

            // Assert: two separate successful backups
            assertThat(result1).isInstanceOf(BackupSessionResult.Success::class.java)
            assertThat(result2).isInstanceOf(BackupSessionResult.Success::class.java)

            val success1 = result1 as BackupSessionResult.Success
            val success2 = result2 as BackupSessionResult.Success
            assertThat(success1.manifestId).isNotEqualTo(success2.manifestId)

            // putManifest called twice
            coVerify(exactly = 2) { writer.putManifest(any(), any<SnapshotManifest>(), any()) }
        }

        @Test
        @DisplayName("backup with tags stores tags in snapshot manifest")
        fun `backup with tags stores tags in snapshot manifest`(): Unit = runBlocking {
            val sourceManager = BackupSourceManager()
            val dir = createTempDir("tagged")
            Files.write(dir.resolve("data.bin"), byteArrayOf(1, 2, 3))

            val source = sourceManager.createSource(
                dir.toAbsolutePath().toString(),
                dir.toAbsolutePath().toString(),
                "Tagged",
            )

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()
            stubWriterForSuccess(writer)

            val capturedManifest = slot<SnapshotManifest>()
            coEvery {
                writer.putManifest(any(), capture(capturedManifest), any())
            } returns ManifestId.generate()

            val tags = mapOf("env" to "production", "team" to "platform")
            val session = createSessionForSource(
                repo,
                writer,
                cpStore,
                sourcePath = source.path,
                sourceId = source.id,
                tags = tags,
            )
            val result = session.run()

            // Assert
            assertThat(result).isInstanceOf(BackupSessionResult.Success::class.java)
            assertThat(capturedManifest.captured.tags).containsEntry("env", "production")
            assertThat(capturedManifest.captured.tags).containsEntry("team", "platform")
        }
    }

    // ==================================================================
    // Progress & Events
    // ==================================================================

    @Nested
    @DisplayName("Progress and Events")
    inner class ProgressAndEvents {

        @Test
        @DisplayName("backup emits progress events during upload")
        fun `backup emits progress events during upload`(): Unit = runBlocking {
            val dir = createTempDir("progress-events")
            Files.write(dir.resolve("file.txt"), "content for hashing".toByteArray())

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()
            stubWriterForSuccess(writer)

            val progressUpdates = mutableListOf<UploadCounters>()
            val callback = object : NullBackupSessionCallback() {
                override fun onProgress(counters: UploadCounters) {
                    progressUpdates.add(counters)
                }
            }

            val session = createSessionForSource(
                repo,
                writer,
                cpStore,
                sourcePath = dir.toAbsolutePath().toString(),
                sourceId = "progress-src",
                callback = callback,
            )
            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Success::class.java)
            // At least some progress events should have been emitted
            // (file hashing / completion events trigger progress callbacks)
            assertThat(progressUpdates).isNotEmpty()
        }

        @Test
        @DisplayName("progress includes file count and byte count")
        fun `progress includes file count and byte count`(): Unit = runBlocking {
            val dir = createTempDir("progress-counters")
            val content = "this is test content for byte counting"
            Files.write(dir.resolve("test.txt"), content.toByteArray())

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()
            stubWriterForSuccess(writer)

            val progressUpdates = mutableListOf<UploadCounters>()
            val callback = object : NullBackupSessionCallback() {
                override fun onProgress(counters: UploadCounters) {
                    progressUpdates.add(counters)
                }
            }

            val session = createSessionForSource(
                repo,
                writer,
                cpStore,
                sourcePath = dir.toAbsolutePath().toString(),
                sourceId = "counter-src",
                callback = callback,
            )
            session.run()

            // Check the final counters from the session
            val finalCounters = session.currentProgress()
            val totalFiles = finalCounters.totalHashedFiles + finalCounters.totalCachedFiles
            val totalBytes = finalCounters.totalHashedBytes + finalCounters.totalCachedBytes

            assertThat(totalFiles).isGreaterThan(0)
            assertThat(totalBytes).isGreaterThan(0)
        }

        @Test
        @DisplayName("task manager tracks backup task through completion")
        fun `task manager tracks backup task through completion`(): Unit = runTest {
            val tm = createTaskManager()
            val dir = createTempDir("task-tracking")
            Files.write(dir.resolve("f.txt"), "data".toByteArray())

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()
            stubWriterForSuccess(writer)

            val taskStarted = CompletableDeferred<Unit>()
            var capturedResult: BackupSessionResult? = null

            val taskId = tm.startTask(TaskKind.BACKUP, "lifecycle backup") { controller ->
                taskStarted.complete(Unit)

                val session = BackupSession(
                    repository = repo,
                    config = BackupSessionConfig(
                        sourcePath = dir.toAbsolutePath().toString(),
                        sourceId = "task-src",
                    ),
                    checkpointStore = cpStore,
                    context = context,
                )
                capturedResult = session.run()
            }

            advanceUntilIdle()
            taskStarted.await()
            advanceUntilIdle()

            // Task should complete successfully
            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.SUCCESS)
            assertThat(info.endTime).isNotNull()

            assertThat(capturedResult).isInstanceOf(BackupSessionResult.Success::class.java)

            tm.shutdown()
        }
    }

    // ==================================================================
    // Failure & Recovery
    // ==================================================================

    @Nested
    @DisplayName("Foreground protection (task-60)")
    inner class ForegroundProtection {

        @Test
        @DisplayName("losing the foreground service makes the run checkpoint more often")
        fun `losing the foreground service makes the run checkpoint more often`() {
            // The worker's progress loop is the only thing that can notice -- WorkManager swallows
            // the promotion-stage refusal -- and this is what it does about it. Not stopping the
            // run: a kill already ends in a recorded failure, so what is left worth doing is
            // bounding what a kill costs, which is everything since the last checkpoint.
            val (repo, _) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()
            val session = BackupSession(
                repository = repo,
                config = BackupSessionConfig(
                    sourcePath = createTempDir("fgs-protection").toAbsolutePath().toString(),
                    sourceId = "fgs-protection",
                    checkpointOptions = CheckpointOptions(intervalMillis = 5L * 60 * 1000),
                ),
                checkpointStore = cpStore,
                context = context,
            )

            assertThat(session.checkpointIntervalMillis()).isEqualTo(5L * 60 * 1000)

            session.reportForegroundProtectionLost()

            assertThat(session.checkpointIntervalMillis()).isEqualTo(75L * 1000)
        }
    }

    @Nested
    @DisplayName("Failure and Recovery")
    inner class FailureAndRecovery {

        @Test
        @DisplayName("backup fails gracefully when source path does not exist")
        fun `backup fails gracefully when source path does not exist`(): Unit = runTest {
            val tm = createTaskManager()
            val sourceManager = BackupSourceManager()
            val source = sourceManager.createSource(
                "/nonexistent/lifecycle/path",
                "/nonexistent/lifecycle/path",
                "Missing",
            )

            val (repo, _) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()

            var capturedResult: BackupSessionResult? = null
            val taskId = tm.startTask(TaskKind.BACKUP, "failing backup") {
                val session = BackupSession(
                    repository = repo,
                    config = BackupSessionConfig(
                        sourcePath = source.path,
                        sourceId = source.id,
                    ),
                    checkpointStore = cpStore,
                    context = context,
                )
                capturedResult = session.run()
                // Propagate the failure so TaskManager records FAILED
                val r = capturedResult
                if (r is BackupSessionResult.Failed) {
                    throw r.error
                }
            }

            advanceUntilIdle()

            // Task should be marked FAILED
            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.FAILED)
            // The user-facing sentence, which is what the dashboard shows (task-39/task-59).
            assertThat(info.errorMessage).contains("Could not open this folder")

            // Source manager still has the source
            assertThat(sourceManager.getSource(source.id)).isNotNull()

            tm.shutdown()
        }

        @Test
        @DisplayName("backup fails when repository is disconnected")
        fun `backup fails when repository is disconnected`(): Unit = runBlocking {
            val dir = createTempDir("disconnected")
            Files.write(dir.resolve("x.txt"), "x".toByteArray())

            val repo = mockk<DirectRepository>(relaxed = true)
            coEvery { repo.newWriter(any()) } throws IOException("Repository disconnected")

            val (cpStore, _) = mockCheckpointStore()

            val session = BackupSession(
                repository = repo,
                config = BackupSessionConfig(
                    sourcePath = dir.toAbsolutePath().toString(),
                    sourceId = "disconnected-src",
                ),
                checkpointStore = cpStore,
                context = context,
            )
            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Failed::class.java)
            val failed = result as BackupSessionResult.Failed
            assertThat(failed.error).isInstanceOf(IOException::class.java)
            assertThat(failed.error.message).isEqualTo("Repository disconnected")
        }

        @Test
        @DisplayName("backup cancel during upload stops execution")
        fun `backup cancel during upload stops execution`(): Unit = runBlocking {
            // Use real dispatchers (not TestScope) so cancellation propagates naturally
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val tm = TaskManager(scope)
            val dir = createTempDir("cancel-mid")
            Files.write(dir.resolve("big.txt"), ByteArray(1024))

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()

            // Block findManifests with a real delay so cancellation can interrupt it
            coEvery { writer.findManifests(any()) } coAnswers {
                delay(10_000)
                emptyList()
            }

            val taskStarted = CompletableDeferred<Unit>()
            val taskDone = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "cancel-me") { controller ->
                taskStarted.complete(Unit)
                try {
                    val session = BackupSession(
                        repository = repo,
                        config = BackupSessionConfig(
                            sourcePath = dir.toAbsolutePath().toString(),
                            sourceId = "cancel-src",
                        ),
                        checkpointStore = cpStore,
                        context = context,
                    )
                    session.run()
                } finally {
                    taskDone.complete(Unit)
                }
            }

            // Wait for the task to actually start and block on findManifests
            taskStarted.await()
            delay(50) // Give the session time to reach the findManifests suspension

            // Cancel while upload is blocked
            tm.cancelTask(taskId)
            taskDone.await()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.CANCELED)

            tm.shutdown()
        }

        @Test
        @DisplayName("checkpoint is saved on the cancellation path despite coroutine cancellation")
        fun `checkpoint saved when cancelled mid-upload`(): Unit = runBlocking {
            // Regression lock for the NonCancellable checkpoint save. On a WorkManager-initiated cancel the
            // coroutine is cancelled while upload() is suspended; run()'s CancellationException handler must
            // still persist a resumable checkpoint. Without withContext(NonCancellable) the save suspends in
            // an already-cancelled coroutine, throws, is swallowed, and NO checkpoint is written. (task-14)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val dir = createTempDir("cancel-checkpoint")
            Files.write(dir.resolve("f.txt"), ByteArray(1024))

            val (repo, writer) = mockRepository()
            // Block inside upload so the cancel lands at a suspension point in run()'s main try.
            coEvery { writer.findManifests(any()) } coAnswers {
                delay(10_000)
                emptyList()
            }

            // Checkpoint store with a REAL suspension point (yield): saveCheckpoint throws on a cancelled
            // coroutine unless it runs under NonCancellable, so a mutant that drops NonCancellable records
            // only the pre-upload initial checkpoint.
            val saved = java.util.Collections.synchronizedList(mutableListOf<BackupCheckpoint>())
            val store = mockk<CheckpointStore>(relaxed = true)
            coEvery { store.saveCheckpoint(any()) } coAnswers {
                yield()
                saved.add(firstArg())
                Unit
            }

            val session = BackupSession(
                repository = repo,
                config = BackupSessionConfig(
                    sourcePath = dir.toAbsolutePath().toString(),
                    sourceId = "cp-src",
                ),
                checkpointStore = store,
                context = context,
            )

            val job = scope.launch { session.run() }

            // Wait for the pre-upload initial checkpoint, then cancel mid-upload.
            var guard = 0
            while (saved.isEmpty() && guard++ < 500) delay(10)
            val afterInitial = saved.size
            assertThat(afterInitial).isAtLeast(1)

            job.cancelAndJoin()

            // The cancellation path wrote an additional checkpoint despite the coroutine being cancelled.
            assertThat(saved.size).isGreaterThan(afterInitial)
        }

        @Test
        @DisplayName("cancel before upload stops the walk before any file is processed")
        fun `early cancel short-circuits the upload`(): Unit = runBlocking {
            // Cancel arrives before run() -- the uploader does not exist yet, so cancel() only sets the
            // session flag. run()'s replay must forward it to the (sticky) uploader so the tree walk never
            // processes a file. Without the sticky flag + replay, the entire source is uploaded and only
            // then reported as cancelled (wasted bandwidth/battery). (task-14)
            val dir = createTempDir("early-cancel")
            Files.write(dir.resolve("a.txt"), ByteArray(4096))
            Files.write(dir.resolve("b.txt"), ByteArray(4096))

            val (repo, writer) = mockRepository()
            stubWriterForSuccess(writer)
            val (cpStore, _) = mockCheckpointStore()

            val session = BackupSession(
                repository = repo,
                config = BackupSessionConfig(
                    sourcePath = dir.toAbsolutePath().toString(),
                    sourceId = "early-src",
                ),
                checkpointStore = cpStore,
                context = context,
            )

            session.cancel()
            val result = session.run()

            assertThat(result).isInstanceOf(BackupSessionResult.Cancelled::class.java)
            val cancelled = result as BackupSessionResult.Cancelled
            assertThat(cancelled.counters.totalHashedFiles + cancelled.counters.totalCachedFiles)
                .isEqualTo(0)
        }

        @Test
        @DisplayName("backup retry after failure works")
        fun `backup retry after failure works`(): Unit = runBlocking {
            val dir = createTempDir("retry")
            Files.write(dir.resolve("r.txt"), "retry-content".toByteArray())

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()

            // First call: findManifests throws -> backup fails
            // Second call: findManifests succeeds -> backup succeeds
            var callCount = 0
            coEvery { writer.findManifests(any()) } answers {
                callCount++
                if (callCount == 1) {
                    throw IOException("Transient network error")
                }
                emptyList()
            }
            coEvery {
                writer.putManifest(any(), any<SnapshotManifest>(), any())
            } returns ManifestId.generate()

            // First attempt fails
            val session1 = BackupSession(
                repository = repo,
                config = BackupSessionConfig(
                    sourcePath = dir.toAbsolutePath().toString(),
                    sourceId = "retry-src",
                ),
                checkpointStore = cpStore,
                context = context,
            )
            val result1 = session1.run()
            assertThat(result1).isInstanceOf(BackupSessionResult.Failed::class.java)

            // Second attempt succeeds (transient error resolved)
            val session2 = BackupSession(
                repository = repo,
                config = BackupSessionConfig(
                    sourcePath = dir.toAbsolutePath().toString(),
                    sourceId = "retry-src",
                ),
                checkpointStore = cpStore,
                context = context,
            )
            val result2 = session2.run()
            assertThat(result2).isInstanceOf(BackupSessionResult.Success::class.java)
        }

        @Test
        @DisplayName("source state updates during backup lifecycle")
        fun `source state updates during backup lifecycle`(): Unit = runBlocking {
            val sourceManager = BackupSourceManager()
            val dir = createTempDir("state-tracking")
            Files.write(dir.resolve("s.txt"), "state".toByteArray())

            val source = sourceManager.createSource(
                dir.toAbsolutePath().toString(),
                dir.toAbsolutePath().toString(),
                "Stateful",
            )

            // Initially IDLE
            assertThat(sourceManager.getSource(source.id)!!.status).isEqualTo(SourceStatus.IDLE)

            val (repo, writer) = mockRepository()
            val (cpStore, _) = mockCheckpointStore()
            stubWriterForSuccess(writer)

            // Transition to UPLOADING before backup
            sourceManager.setSourceStatus(source.id, SourceStatus.UPLOADING)
            assertThat(sourceManager.getSource(source.id)!!.status).isEqualTo(SourceStatus.UPLOADING)

            // Run backup
            val session = createSessionForSource(
                repo,
                writer,
                cpStore,
                sourcePath = source.path,
                sourceId = source.id,
            )
            val result = session.run()
            assertThat(result).isInstanceOf(BackupSessionResult.Success::class.java)

            // Transition back to IDLE after backup completes
            sourceManager.setSourceStatus(source.id, SourceStatus.IDLE)
            assertThat(sourceManager.getSource(source.id)!!.status).isEqualTo(SourceStatus.IDLE)

            // Record last snapshot time
            sourceManager.updateLastSnapshotTime(source.id, Instant.now())
            assertThat(sourceManager.getSource(source.id)!!.lastSnapshotTime).isNotNull()
        }
    }
}
