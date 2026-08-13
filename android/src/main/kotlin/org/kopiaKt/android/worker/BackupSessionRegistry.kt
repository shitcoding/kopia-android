package org.kopiaKt.android.worker

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of in-flight backup sessions, keyed by source id.
 *
 * A backup runs inside a [BackupWorker] (a `CoroutineWorker`). WorkManager makes
 * `CoroutineWorker.onStopped` final and stops work by cancelling the `doWork` coroutine, so a Cancel tap
 * cannot be routed through the worker instance itself. This registry lets [BackupCancelReceiver] -- which
 * runs in the same app process -- look the running session up by source id and invoke its cooperative
 * [BackupSession.cancel] (set the cancel flag + tell the uploader to stop at a clean boundary and write a
 * resumable, incomplete-manifest checkpoint) BEFORE/independently of WorkManager's coroutine teardown.
 *
 * WorkManager runs workers in the app's main process by default, the same process as a manifest-registered
 * receiver, so this singleton is shared between the two.
 */
object BackupSessionRegistry {
    private val sessions = ConcurrentHashMap<String, BackupSession>()

    /** Registers the [session] currently backing up [sourceId] (replacing any prior one for that id). */
    fun register(sourceId: String, session: BackupSession) {
        sessions[sourceId] = session
    }

    /**
     * Removes [session] for [sourceId], but only if it is still the registered instance. The identity
     * guard matters under `ExistingWorkPolicy.REPLACE`: a restarted worker for the same source may have
     * already registered a newer session, and the old worker finishing must not unregister the new one.
     */
    fun unregister(sourceId: String, session: BackupSession) {
        sessions.remove(sourceId, session)
    }

    /** The session backing up [sourceId], if one is running in this process. */
    fun forSource(sourceId: String): BackupSession? = sessions[sourceId]

    /**
     * Cooperatively cancels the session backing up [sourceId], if one is running and not already
     * cancelled.
     *
     * Returns false when the session was ALREADY cancelled, so a caller (the cancel receiver) can escalate
     * a repeated Cancel tap to a hard WorkManager cancel -- the cooperative flag only stops the walk at
     * entry boundaries, so a backup wedged in a blocking read/write or a wind-down flush over a dead
     * connection would otherwise be un-cancellable from the notification.
     *
     * @return true if a running session was found and this call is the one that signalled cancellation.
     */
    fun cancel(sourceId: String): Boolean {
        val session = sessions[sourceId] ?: return false
        if (session.isCancelled()) return false
        session.cancel()
        return true
    }

    /** Clears all registrations. For test isolation only (the registry is a process-wide singleton). */
    @VisibleForTesting
    internal fun unregisterAllForTest() {
        sessions.clear()
    }
}
