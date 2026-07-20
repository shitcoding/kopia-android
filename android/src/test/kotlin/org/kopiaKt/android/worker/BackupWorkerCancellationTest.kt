package org.kopiaKt.android.worker

import android.content.Context
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.core.repository.DirectRepository
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Tests for cooperative backup cancellation.
 *
 * WorkManager makes `CoroutineWorker.onStopped` final and stops work by cancelling the `doWork` coroutine,
 * so a Cancel tap cannot be routed through the worker instance. Instead a running [BackupSession] is
 * published in [BackupSessionRegistry], and [BackupCancelReceiver] (same process) looks it up by source id
 * and invokes its cooperative [BackupSession.cancel] so the uploader stops at a clean boundary and writes a
 * resumable checkpoint -- rather than relying solely on coroutine cancellation tearing the upload down
 * mid-write. (task-14)
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("BackupWorker Cooperative Cancellation")
class BackupWorkerCancellationTest {

    private lateinit var context: Context

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @AfterEach
    fun tearDown() {
        // Leave no registered sessions behind (the registry is a process-wide singleton).
        BackupSessionRegistry.unregisterAllForTest()
    }

    private fun newSession(sourceId: String): BackupSession =
        BackupSession(
            repository = mockk<DirectRepository>(relaxed = true),
            config = BackupSessionConfig(sourcePath = "/test/path", sourceId = sourceId),
            checkpointStore = mockk(relaxed = true)
        )

    @Test
    fun `registry cancel cooperatively cancels the registered session`() {
        val session = newSession("src-a")
        BackupSessionRegistry.register("src-a", session)

        assertThat(session.isCancelled()).isFalse()

        val cancelled = BackupSessionRegistry.cancel("src-a")

        assertThat(cancelled).isTrue()
        assertThat(session.isCancelled()).isTrue()
    }

    @Test
    fun `registry cancel returns false for an unknown source`() {
        assertThat(BackupSessionRegistry.cancel("no-such-source")).isFalse()
    }

    @Test
    fun `unregister only removes the matching session instance`() {
        // Under ExistingWorkPolicy.REPLACE a restarted worker registers a NEWER session for the same id;
        // the old worker finishing (unregister with the old instance) must not evict the new one.
        val oldSession = newSession("src-b")
        val newSessionForSameId = newSession("src-b")
        BackupSessionRegistry.register("src-b", oldSession)
        BackupSessionRegistry.register("src-b", newSessionForSameId)

        BackupSessionRegistry.unregister("src-b", oldSession)

        // The new session is still registered and still cancellable.
        assertThat(BackupSessionRegistry.cancel("src-b")).isTrue()
        assertThat(newSessionForSameId.isCancelled()).isTrue()
        assertThat(oldSession.isCancelled()).isFalse()
    }

    @Test
    fun `BackupCancelReceiver routes a cancel broadcast through the running session`() {
        val session = newSession("src-c")
        BackupSessionRegistry.register("src-c", session)

        val intent = Intent(BackupWorker.ACTION_CANCEL_BACKUP).apply {
            putExtra(BackupWorker.KEY_SOURCE_ID, "src-c")
        }
        BackupCancelReceiver().onReceive(context, intent)

        assertThat(session.isCancelled()).isTrue()
    }

    @Test
    fun `BackupCancelReceiver with no registered session does not throw`() {
        val intent = Intent(BackupWorker.ACTION_CANCEL_BACKUP).apply {
            putExtra(BackupWorker.KEY_SOURCE_ID, "src-unregistered")
        }
        assertDoesNotThrow { BackupCancelReceiver().onReceive(context, intent) }
    }

    @Test
    fun `receiver does not abruptly cancel WorkManager work when a session is winding down cooperatively`() {
        // With a running session, the cooperative cancel handles wind-down; abruptly cancelling the
        // WorkManager job too would race the cooperative path and discard the clean checkpoint.
        BackupWorker.scheduleOneTime(context, sourceId = "coop-work", sourcePath = "/test/path")
        val session = newSession("coop-work")
        BackupSessionRegistry.register("coop-work", session)

        BackupCancelReceiver().onReceive(
            context,
            Intent(BackupWorker.ACTION_CANCEL_BACKUP).putExtra(BackupWorker.KEY_SOURCE_ID, "coop-work")
        )

        assertThat(session.isCancelled()).isTrue()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("backup_coop-work").get()
        assertThat(infos.none { it.state == WorkInfo.State.CANCELLED }).isTrue()
    }

    @Test
    fun `registry cancel returns false on a repeat cancel so the caller can escalate`() {
        val session = newSession("escalate")
        BackupSessionRegistry.register("escalate", session)

        assertThat(BackupSessionRegistry.cancel("escalate")).isTrue() // first tap: cooperative
        assertThat(BackupSessionRegistry.cancel("escalate")).isFalse() // repeat: already cancelled -> escalate
    }

    @Test
    fun `receiver escalates a repeat cancel to a hard WorkManager cancel`() {
        BackupWorker.scheduleOneTime(context, sourceId = "escalate-work", sourcePath = "/test/path")
        val session = newSession("escalate-work")
        BackupSessionRegistry.register("escalate-work", session)
        val wm = WorkManager.getInstance(context)
        fun intent() =
            Intent(BackupWorker.ACTION_CANCEL_BACKUP).putExtra(BackupWorker.KEY_SOURCE_ID, "escalate-work")

        // First tap: cooperative only -- WorkManager work is NOT abruptly cancelled.
        BackupCancelReceiver().onReceive(context, intent())
        assertThat(session.isCancelled()).isTrue()
        assertThat(
            wm.getWorkInfosForUniqueWork("backup_escalate-work").get().none {
                it.state == WorkInfo.State.CANCELLED
            }
        ).isTrue()

        // Repeat tap on the wedged/winding-down session: escalate to a hard WorkManager cancel.
        BackupCancelReceiver().onReceive(context, intent())
        assertThat(
            wm.getWorkInfosForUniqueWork("backup_escalate-work").get().all {
                it.state == WorkInfo.State.CANCELLED || it.state.isFinished
            }
        ).isTrue()
    }

    @Test
    fun `receiver falls back to WorkManager cancel when no session is running`() {
        // No session in this process (queued/not-yet-started work): the abrupt WorkManager cancel is the
        // only way to stop it.
        BackupWorker.scheduleOneTime(context, sourceId = "queued-work", sourcePath = "/test/path")

        BackupCancelReceiver().onReceive(
            context,
            Intent(BackupWorker.ACTION_CANCEL_BACKUP).putExtra(BackupWorker.KEY_SOURCE_ID, "queued-work")
        )

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("backup_queued-work").get()
        assertThat(infos.all { it.state == WorkInfo.State.CANCELLED || it.state.isFinished }).isTrue()
    }
}
