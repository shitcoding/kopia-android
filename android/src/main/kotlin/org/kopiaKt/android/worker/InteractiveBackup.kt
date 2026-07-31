package org.kopiaKt.android.worker

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.kopiaKt.snapshot.upload.UploadCounters

private const val TAG = "InteractiveBackup"

/**
 * Runs a backup the user just asked for, and does not return until it is over.
 *
 * The point of awaiting is that the caller has a handle worth having: a task the UI can watch and
 * cancel. Enqueue-and-forget would return an id that means nothing.
 *
 * Throws [BackupFailedException] if the run ends in any state other than success, so the surrounding
 * task is not reported as SUCCESS when nothing was written.
 *
 * @return the number of entries the run recorded as failed. Non-zero means a complete, saved
 *   snapshot that skipped entries it could not read — reported as "completed with errors", not as a
 *   plain success and not as a failure that would retry a valid snapshot.
 */
suspend fun runInteractiveBackup(
    context: Context,
    sourceId: String,
    sourcePath: String,
    config: BackupWorkerConfig = BackupWorkerConfig(),
    onProgress: (counters: UploadCounters, final: Boolean) -> Unit = { _, _ -> },
): Int {
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

    val info = try {
        awaitTerminalInfo(context, sourceId, onProgress)
    } catch (e: CancellationException) {
        withContext(NonCancellable) { stopBackup(context, sourceId) }
        throw e
    }

    val state = info?.state ?: WorkInfo.State.CANCELLED
    if (state != WorkInfo.State.SUCCEEDED) {
        throw BackupFailedException("Backup ${state.name.lowercase()}")
    }
    return info?.outputData?.getInt(BackupWorker.KEY_ERROR_COUNT, 0) ?: 0
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

private suspend fun awaitTerminalInfo(
    context: Context,
    sourceId: String,
    onProgress: (counters: UploadCounters, final: Boolean) -> Unit,
): WorkInfo? {
    val name = BackupWorker.uniqueWorkName(sourceId)
    val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name)

    // An empty list means the work is gone (pruned), which is terminal too -- waiting for a state
    // that will never arrive would hang the task and its foreground notification forever.
    val finished = infos
        .onEach { list ->
            // Every non-terminal emission carries whatever the worker last published. Reporting from
            // here rather than from the worker keeps the counters flowing to the task even though the
            // two may be in different processes.
            // Best-effort, like the worker's own progress loop: a callback that throws must not
            // fail a task whose backup is still running perfectly well.
            try {
                list.lastOrNull()?.progress?.toUploadCounters()?.let { onProgress(it, false) }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Reporting progress is not worth losing the run over.
                Log.w(TAG, "could not report backup progress", e)
            }
        }
        .first { list -> list.isEmpty() || list.all { it.state.isFinished } }
    val terminal = finished.lastOrNull()
    // The run's own final numbers, which the loop may never have published.
    terminal?.outputData?.toUploadCounters()?.let {
        try {
            onProgress(it, true)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "could not report the final backup counters", e)
        }
    }
    return terminal
}
