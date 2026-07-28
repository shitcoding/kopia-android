package org.kopiaKt.android.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.kopiaKt.android.storage.SafPermissionManager

private const val TAG = "BackupSourceDeleter"

/**
 * Removes a backup source and everything on the device that belonged to it.
 *
 * Deleting a source used to be a map removal, which was harmless while sources lived only in memory.
 * Now that they are durable it has to mean something specific, so the semantics are pinned here:
 *
 * - **cancel its pending work** — otherwise a queued or retrying backup wakes up later for a source
 *   that no longer exists;
 * - **drop its local checkpoint** — stale resume state keyed by an id nothing will use again;
 * - **release its SAF grant, but only if no remaining source needs it** — the grants are a capped
 *   per-app resource, and two sources can legitimately point at the same tree;
 * - **keep the repository's snapshots and the source's policy.** Deleting a source stops backing a
 *   folder up; it does not throw away what was already backed up, and re-adding the same folder
 *   should find its settings intact. Destructive cleanup is a separate, explicit action.
 */
class BackupSourceDeleter(
    private val context: Context,
    private val sources: BackupSourceManager,
    private val checkpoints: CheckpointStore = CheckpointStore(context),
    private val permissions: SafPermissionManager = SafPermissionManager(context),
) {

    suspend fun delete(id: String) {
        val source = sources.getSource(id) ?: return

        // Before forgetting the source, so a backup cannot start between the two.
        BackupSessionRegistry.cancel(id)
        runCatching { BackupWorker.cancel(context, id) }
            .rethrowCancellation()
            .onFailure { Log.w(TAG, "could not cancel pending work for $id", it) }

        sources.deleteSource(id)

        // Wait for the run to actually stop before clearing up after it: a session still winding
        // down writes its own cancellation checkpoint, and revoking its SAF grant underneath it
        // turns a clean stop into a stream of SecurityExceptions. Bounded, because the caller is a
        // blocked bridge call and a stuck worker must not wedge the UI.
        awaitStopped(id)

        runCatching { checkpoints.clearCheckpoint(id) }
            .rethrowCancellation()
            .onFailure { Log.w(TAG, "could not clear the checkpoint for $id", it) }

        releaseGrantIfUnused(source.path)
    }

    private suspend fun awaitStopped(id: String) {
        val name = BackupWorker.uniqueWorkName(id)
        runCatching {
            withTimeout(STOP_TIMEOUT_MILLIS) {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWorkFlow(name)
                    .first { list -> list.isEmpty() || list.all { it.state.isFinished } }
            }
        }.rethrowCancellation().onFailure {
            Log.w(TAG, "gave up waiting for the backup of $id to stop", it)
        }
    }

    private suspend fun releaseGrantIfUnused(path: String) {
        if (!path.startsWith("content://")) {
            return
        }
        if (sources.listSources().any { it.path == path }) {
            // Another source still backs up this tree.
            return
        }
        runCatching { permissions.releasePermission(Uri.parse(path)) }
            .rethrowCancellation()
            .onFailure { Log.w(TAG, "could not release the SAF grant for $path", it) }
    }

    /**
     * A cancelled coroutine is not a failure to swallow -- but our own `withTimeout` is, since that
     * is a deliberate bound rather than the caller going away.
     */
    private fun <T> Result<T>.rethrowCancellation(): Result<T> = also {
        val failure = exceptionOrNull()
        if (failure is CancellationException && failure !is TimeoutCancellationException) {
            throw failure
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 10_000L
    }
}
