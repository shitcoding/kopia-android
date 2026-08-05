package org.kopiaKt.android.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.snapshot.upload.UploadCounters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Tests for BackupWorker doWork() execution paths.
 *
 * Exercises input validation, repository provider handling, and
 * error result generation using TestListenableWorkerBuilder.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("BackupWorker Execution")
class BackupWorkerExecutionTest {

    private lateinit var context: Context

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        BackupWorker.repositoryProvider = null
    }

    @AfterEach
    fun tearDown() {
        BackupWorker.repositoryProvider = null
    }

    @Test
    fun `should return failure when source ID is missing`(): Unit = runBlocking {
        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_PATH to "/test/path",
                ),
            )
            .build()

        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result::class.java)
        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(BackupWorker.KEY_ERROR to "Missing source ID"),
            ),
        )
    }

    @Test
    fun `should return failure when source path is missing`(): Unit = runBlocking {
        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source",
                ),
            )
            .build()

        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result::class.java)
        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(BackupWorker.KEY_ERROR to "Missing source path"),
            ),
        )
    }

    /**
     * WorkManager re-runs a retried worker in a FRESH process, where nothing has reconnected the
     * repository — opening it needs a password the app deliberately does not keep. So every retry
     * hits the same wall, and the worker burned its whole backoff schedule discovering that, then
     * gave up with "Repository not configured": a message about a call the user has never heard of,
     * arriving after minutes of silence.
     *
     * Both of these tests asserted `retry()` before task-30.17. That is the behaviour being fixed.
     */
    @Test
    fun `a missing repository fails at once instead of retry-looping`(): Unit = runBlocking {
        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source",
                    BackupWorker.KEY_SOURCE_PATH to "/test/path",
                ),
            )
            .build()

        val result = worker.doWork()

        // On the FIRST attempt, where the old code still had two retries left to waste.
        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(errorOf(result)).isEqualTo(BackupWorker.NEEDS_REPOSITORY_MESSAGE)
    }

    @Test
    fun `a repository provider that answers null is the same terminal case`(): Unit = runBlocking {
        BackupWorker.repositoryProvider = { _ -> null }

        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source",
                    BackupWorker.KEY_SOURCE_PATH to "/test/path",
                ),
            )
            .build()

        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(errorOf(result)).isEqualTo(BackupWorker.NEEDS_REPOSITORY_MESSAGE)
    }

    /**
     * The system refusing the foreground service is the second terminal case, and until this test
     * existed nothing executed it: `TestForegroundUpdater` always succeeds, so the whole wrap could
     * have been deleted with the suite still green.
     *
     * Really happens from API 31 (a foreground service started from the background) and after
     * Android 15's six-hour `dataSync` cap has fired, which bars further `dataSync` starts for the
     * rest of that 24-hour window.
     */
    @Test
    fun `a refused foreground service is terminal, and says so in words`(): Unit = runBlocking {
        BackupWorker.repositoryProvider = { _ -> error("must not get as far as the repository") }

        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source",
                    BackupWorker.KEY_SOURCE_PATH to "/test/path",
                ),
            )
            .setForegroundUpdater { _, _, _ ->
                SettableFuture.create<Void>().apply {
                    setException(IllegalStateException("startForegroundService() not allowed"))
                }
            }
            .build()

        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        assertThat(errorOf(result)).isEqualTo(BackupWorker.FOREGROUND_DENIED_MESSAGE)
    }

    /**
     * Android 15 stops a `dataSync` foreground service after six hours, and another one cannot start
     * in the same 24-hour window until the user brings the app forward. A retry is not merely
     * useless there, it is harmful: it spends the backoff schedule on starts the system will refuse.
     * The run is already checkpointed, so the only useful thing left is to say so.
     */
    @Test
    fun `the six-hour foreground cap is reported, not treated as a plain cancel`() {
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT))
            .isEqualTo(BackupWorker.FOREGROUND_TIMEOUT_MESSAGE)
    }

    @Test
    fun `a user cancel stays silent`() {
        // Whoever tapped Cancel knows. A notification here would be the app arguing with them.
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_CANCELLED_BY_APP)).isNull()
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_USER)).isNull()
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_NOT_STOPPED)).isNull()
    }

    /**
     * The constraint and device-state stops are the genuinely transient ones — WorkManager re-runs
     * the work itself when the constraint comes back, so they must not be dressed up as something
     * the user has to act on.
     */
    @Test
    fun `a constraint loss is not something to bother the user about`() {
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)).isNull()
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_DEVICE_STATE)).isNull()
        // WorkManager re-runs these itself once the device lets it.
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_QUOTA)).isNull()
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_APP_STANDBY)).isNull()
    }

    /**
     * The system's background restriction — what aggressive OEM battery managers apply — blocks not
     * just this run but every future one, and only the user can lift it. Staying silent there means
     * backups quietly stop happening on exactly the devices where that is most likely.
     */
    @Test
    fun `a background restriction is reported, because only the user can lift it`() {
        assertThat(BackupWorker.cancellationMessage(WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION))
            .isEqualTo(BackupWorker.BACKGROUND_RESTRICTED_MESSAGE)
    }

    private fun errorOf(result: ListenableWorker.Result): String? {
        val failure = result as? ListenableWorker.Result.Failure ?: return null
        return failure.outputData.getString(BackupWorker.KEY_ERROR)
    }

    @Test
    fun `should invoke repository provider with application context`(): Unit = runBlocking {
        var capturedContext: Context? = null
        BackupWorker.repositoryProvider = { ctx ->
            capturedContext = ctx
            null // Return null to trigger known failure path
        }

        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source",
                    BackupWorker.KEY_SOURCE_PATH to "/test/path",
                ),
            )
            .build()

        worker.doWork()

        // The provider should have been invoked with the application context
        // when the worker reached the runBackup stage.
        assertThat(capturedContext).isNotNull()
        assertThat(capturedContext).isEqualTo(context)
    }

    private fun buildWorker(): BackupWorker = TestListenableWorkerBuilder<BackupWorker>(context)
        .setInputData(
            workDataOf(
                BackupWorker.KEY_SOURCE_ID to "s",
                BackupWorker.KEY_SOURCE_PATH to "/p",
            ),
        )
        .build()

    @Test
    fun `computeProgressPercent is null when no estimate is available`() {
        assertThat(
            buildWorker().computeProgressPercent(
                UploadCounters(totalHashedBytes = 500, estimatedBytes = 0),
            ),
        ).isNull()
    }

    @Test
    fun `computeProgressPercent computes the partial percentage from cached plus hashed bytes`() {
        val worker = buildWorker()
        assertThat(
            worker.computeProgressPercent(UploadCounters(totalHashedBytes = 25, estimatedBytes = 100)),
        ).isEqualTo(25)
        // Both cached and hashed bytes count toward progress: 30 + 20 of 200 == 25%.
        assertThat(
            worker.computeProgressPercent(
                UploadCounters(totalCachedBytes = 30, totalHashedBytes = 20, estimatedBytes = 200),
            ),
        ).isEqualTo(25)
    }

    @Test
    fun `computeProgressPercent caps at 99 until completion`() {
        val worker = buildWorker()
        // Exactly at the estimate must not show 100% before the backup actually finishes.
        assertThat(
            worker.computeProgressPercent(UploadCounters(totalHashedBytes = 100, estimatedBytes = 100)),
        ).isEqualTo(99)
        // Overshooting the estimate stays clamped at 99.
        assertThat(
            worker.computeProgressPercent(UploadCounters(totalHashedBytes = 150, estimatedBytes = 100)),
        ).isEqualTo(99)
    }
}
