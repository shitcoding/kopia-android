package org.kopiaKt.android.worker

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.core.repository.DirectRepository
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * The interactive path is what "Back Up Now" runs, and its whole reason to await the worker is so
 * the surrounding task reports what actually happened. Reporting SUCCESS for a run that failed would
 * tell a user their data is backed up when nothing was written — the worst possible bug in a backup
 * tool, and the one this file exists to prevent.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class InteractiveBackupTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @BeforeEach
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(TestWorkerFactory)
                .build(),
        )
        TestWorkerFactory.reset()
    }

    @Test
    fun `a successful run completes normally`(): Unit = runBlocking {
        BackupWorker.repositoryProvider = { TestWorkerFactory.repository }

        runInteractiveBackup(context, SOURCE_ID, "/does/not/matter")

        assertThat(workState()).isEqualTo(WorkInfo.State.SUCCEEDED)
    }

    @Test
    fun `a failed run is reported, not swallowed as success`() {
        TestWorkerFactory.failWith = IllegalStateException("no repository")

        // Reaching the caller as an exception is what makes TaskManager mark the task FAILED.
        assertThrows<BackupFailedException> {
            runBlocking { runInteractiveBackup(context, SOURCE_ID, "/does/not/matter") }
        }
    }

    @Test
    fun `cancelling the caller stops the run`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        TestWorkerFactory.blockOn = started

        val job = async { runInteractiveBackup(context, SOURCE_ID, "/does/not/matter") }
        withTimeout(TIMEOUT_MILLIS) { started.await() }
        // Join, not just cancel: stopping the work happens in the coroutine's NonCancellable
        // finalizer, so the assertion would otherwise race it.
        job.cancelAndJoin()

        // The work must not be left running behind a cancelled task.
        assertThat(workState()).isAnyOf(WorkInfo.State.CANCELLED, WorkInfo.State.FAILED)
    }

    private fun workState(): WorkInfo.State? = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(BackupWorker.uniqueWorkName(SOURCE_ID))
        .get()
        .lastOrNull()
        ?.state

    private companion object {
        const val SOURCE_ID = "local@android-test-a1b2c3:/sdcard/DCIM"
        const val TIMEOUT_MILLIS = 5_000L

        /** Stands in for the real worker so the test drives outcomes rather than real uploads. */
        object TestWorkerFactory : androidx.work.WorkerFactory() {
            val repository: DirectRepository = io.mockk.mockk(relaxed = true)
            var failWith: Exception? = null
            var blockOn: CompletableDeferred<Unit>? = null

            fun reset() {
                failWith = null
                blockOn = null
                BackupWorker.repositoryProvider = null
            }

            override fun createWorker(
                appContext: android.content.Context,
                workerClassName: String,
                workerParameters: androidx.work.WorkerParameters,
            ) = object : androidx.work.CoroutineWorker(appContext, workerParameters) {
                override suspend fun doWork(): Result {
                    blockOn?.complete(Unit)
                    blockOn?.let { kotlinx.coroutines.awaitCancellation() }
                    failWith?.let { return Result.failure() }
                    return Result.success()
                }
            }
        }
    }
}
