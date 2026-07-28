package org.kopiaKt.android.worker

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Runs a backup the user just asked for, and does not return until it is over.
 *
 * The point of awaiting is that the caller has a handle worth having: a task the UI can watch and
 * cancel. Enqueue-and-forget would return an id that means nothing.
 *
 * Throws [BackupFailedException] if the run ends in any state other than success, so the surrounding
 * task is not reported as SUCCESS when nothing was written.
 */
suspend fun runInteractiveBackup(
    context: Context,
    sourceId: String,
    sourcePath: String,
    config: BackupWorkerConfig = BackupWorkerConfig(),
) {
    // Await the enqueue itself before watching for a result: observing "no rows for this name yet"
    // would otherwise look exactly like "the work is already gone", and the task would report a
    // finished backup while the real one was still to come.
    BackupWorker.scheduleOneTime(
        context = context,
        sourceId = sourceId,
        sourcePath = sourcePath,
        config = config,
        constraints = BackupConstraints.interactive(),
    ).await()

    val state = try {
        awaitTerminalState(context, sourceId)
    } catch (e: CancellationException) {
        withContext(NonCancellable) { stopBackup(context, sourceId) }
        throw e
    }

    if (state != WorkInfo.State.SUCCEEDED) {
        throw BackupFailedException("Backup ${state.name.lowercase()}")
    }
}

/**
 * Stops a running backup, preferring the cooperative path.
 *
 * [BackupSessionRegistry.cancel] lets the session stop at the next entry boundary and write a clean
 * incomplete manifest on the way out; WorkManager's own cancellation is an abrupt coroutine teardown
 * that races that wind-down away. So the hard cancel is only for when there is no live session to
 * ask -- the work is still queued, or the process running it is gone.
 */
private fun stopBackup(context: Context, sourceId: String) {
    if (!BackupSessionRegistry.cancel(sourceId)) {
        BackupWorker.cancelOneTime(context, sourceId)
    }
}

/** A backup that reached a terminal state without succeeding. */
class BackupFailedException(message: String) : Exception(message)

private suspend fun awaitTerminalState(context: Context, sourceId: String): WorkInfo.State {
    val name = BackupWorker.uniqueWorkName(sourceId)
    val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name)

    // An empty list means the work is gone (pruned), which is terminal too -- waiting for a state
    // that will never arrive would hang the task and its foreground notification forever.
    val finished = infos.first { list -> list.isEmpty() || list.all { it.state.isFinished } }
    return finished.lastOrNull()?.state ?: WorkInfo.State.CANCELLED
}
