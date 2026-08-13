package org.kopiaKt.android.worker

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

/**
 * Watches whether a running backup still has the foreground service it asked for.
 *
 * **Why this cannot be done by catching something.** `BackupWorker` promotes the run with
 * `setForeground`, and a refusal there is terminal. But that only covers the *start-service* step:
 * WorkManager's `SystemForegroundService` **catches and logs** the promotion-stage refusal — it is
 * swallowed by design on API 31+, verified in the shipped work-runtime 2.10.0 artifact — so
 * `setForeground` returns normally whenever the service starts and the promotion is then denied.
 * Measured on a device (task-60): the framework refused the promotion twice
 * ("Service.startForeground() not allowed due to app op") and the backup ran to completion with the
 * app none the wiser.
 *
 * **Why importance, and not the notification.** The question worth answering is not "did the
 * promotion take" but "is this run unprotected RIGHT NOW", and process importance answers exactly
 * that. Looking for our own notification instead would report a false loss precisely when
 * POST_NOTIFICATIONS is denied — a state this worker is deliberately built to keep working through
 * (it routes progress through `setForeground` for that very reason), so the check would fire on the
 * users least able to act on it.
 *
 * While the app is in front the process reads `IMPORTANCE_FOREGROUND` whether or not a service was
 * promoted. That is not a blind spot: a foregrounded app is not about to be killed for lacking a
 * service it does not yet need. The answer only becomes interesting once the user leaves — which is
 * also the only moment the foreground service was ever buying anything.
 */
internal class ForegroundProtectionWatch {

    private var consecutiveUnprotected = 0
    private var delivered = false

    /**
     * Feeds one reading of the process's own importance.
     *
     * @return true while the run is judged unprotected and that has not been [markDelivered] yet.
     *   Two consecutive readings are required because leaving the app passes through transitional
     *   states, and a single sample would call every healthy run unprotected the moment the user
     *   switched away.
     *
     * Deliberately keeps saying true until the caller confirms delivery: the session it has to tell
     * may not be registered yet (the progress loop starts before the session does), and latching on
     * the reading rather than on the delivery would drop that report for the rest of the run.
     */
    fun observe(importance: Int): Boolean {
        if (importance <= IMPORTANCE_FOREGROUND_SERVICE) {
            consecutiveUnprotected = 0
            return false
        }
        consecutiveUnprotected++
        return consecutiveUnprotected >= CONSECUTIVE_READINGS_BEFORE_REPORTING && !delivered
    }

    /** Confirms the report reached the session, so it is not made again for the rest of the run. */
    fun markDelivered() {
        delivered = true
    }

    companion object {
        /**
         * Readings at 1 Hz, so five seconds of agreement before acting.
         *
         * Two was enough while the response was a log line. It is not enough now the response is
         * real repository work — a tightened checkpoint interval means extra `putManifest` and
         * `flush` calls — so a transient lifecycle state that reads worse than a foreground service
         * would cost uploads rather than a wrong log. Both reviewers asked for the wider guard once
         * the payload became real. The cost of waiting is three more seconds before a genuinely
         * unprotected run starts checkpointing more often, against a run that has minutes to live.
         */
        private const val CONSECUTIVE_READINGS_BEFORE_REPORTING = 5

        /**
         * This process's importance. Lower is better: `IMPORTANCE_FOREGROUND` (100) while the app is
         * in front, `IMPORTANCE_FOREGROUND_SERVICE` (125) once backgrounded with a promoted service,
         * worse than that with neither.
         */
        fun currentImportance(): Int = ActivityManager.RunningAppProcessInfo()
            .also { ActivityManager.getMyMemoryState(it) }
            .importance
    }
}
