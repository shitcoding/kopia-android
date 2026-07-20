package org.kopiaKt.android.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.kopiaKt.snapshot.upload.UploadCounters
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
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
    fun `should return failure when source ID is missing`() = runBlocking {
        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_PATH to "/test/path"
                )
            )
            .build()

        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result::class.java)
        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(BackupWorker.KEY_ERROR to "Missing source ID")
            )
        )
    }

    @Test
    fun `should return failure when source path is missing`() = runBlocking {
        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source"
                )
            )
            .build()

        val result = worker.doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result::class.java)
        assertThat(result).isEqualTo(
            ListenableWorker.Result.failure(
                workDataOf(BackupWorker.KEY_ERROR to "Missing source path")
            )
        )
    }

    @Test
    fun `should fail when repository provider is not set`() = runBlocking {
        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source",
                    BackupWorker.KEY_SOURCE_PATH to "/test/path"
                )
            )
            .build()

        // repositoryProvider is null, so getRepository() returns null,
        // throwing IllegalStateException in runBackup().
        // The outer catch (e: Exception) handles it.
        // Since runAttemptCount == 0 (< 3), result should be retry.
        val result = worker.doWork()

        // The worker may hit setForeground or runBackup exception first;
        // either way, the generic exception handler returns retry on first attempt.
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `should fail when repository provider returns null`() = runBlocking {
        BackupWorker.repositoryProvider = { _ -> null }

        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source",
                    BackupWorker.KEY_SOURCE_PATH to "/test/path"
                )
            )
            .build()

        val result = worker.doWork()

        // Provider returns null -> IllegalStateException in runBackup.
        // First attempt (runAttemptCount == 0 < 3) -> retry.
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `should invoke repository provider with application context`() = runBlocking {
        var capturedContext: Context? = null
        BackupWorker.repositoryProvider = { ctx ->
            capturedContext = ctx
            null // Return null to trigger known failure path
        }

        val worker = TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "test-source",
                    BackupWorker.KEY_SOURCE_PATH to "/test/path"
                )
            )
            .build()

        worker.doWork()

        // The provider should have been invoked with the application context
        // when the worker reached the runBackup stage.
        assertThat(capturedContext).isNotNull()
        assertThat(capturedContext).isEqualTo(context)
    }

    private fun buildWorker(): BackupWorker =
        TestListenableWorkerBuilder<BackupWorker>(context)
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_SOURCE_ID to "s",
                    BackupWorker.KEY_SOURCE_PATH to "/p"
                )
            )
            .build()

    @Test
    fun `computeProgressPercent is null when no estimate is available`() {
        assertThat(
            buildWorker().computeProgressPercent(
                UploadCounters(totalHashedBytes = 500, estimatedBytes = 0)
            )
        ).isNull()
    }

    @Test
    fun `computeProgressPercent computes the partial percentage from cached plus hashed bytes`() {
        val worker = buildWorker()
        assertThat(
            worker.computeProgressPercent(UploadCounters(totalHashedBytes = 25, estimatedBytes = 100))
        ).isEqualTo(25)
        // Both cached and hashed bytes count toward progress: 30 + 20 of 200 == 25%.
        assertThat(
            worker.computeProgressPercent(
                UploadCounters(totalCachedBytes = 30, totalHashedBytes = 20, estimatedBytes = 200)
            )
        ).isEqualTo(25)
    }

    @Test
    fun `computeProgressPercent caps at 99 until completion`() {
        val worker = buildWorker()
        // Exactly at the estimate must not show 100% before the backup actually finishes.
        assertThat(
            worker.computeProgressPercent(UploadCounters(totalHashedBytes = 100, estimatedBytes = 100))
        ).isEqualTo(99)
        // Overshooting the estimate stays clamped at 99.
        assertThat(
            worker.computeProgressPercent(UploadCounters(totalHashedBytes = 150, estimatedBytes = 100))
        ).isEqualTo(99)
    }
}
