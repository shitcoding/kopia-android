package org.kopiaKt.android.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Broadcast receiver for handling backup cancellation requests from notifications.
 *
 * When the user taps "Cancel" on a backup progress notification, this receiver
 * handles the cancellation by calling WorkManager to stop the backup.
 *
 * Register this receiver in AndroidManifest.xml:
 * ```xml
 * <receiver
 *     android:name=".worker.BackupCancelReceiver"
 *     android:exported="false" />
 * ```
 */
class BackupCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BackupWorker.ACTION_CANCEL_BACKUP -> {
                val sourceId = intent.getStringExtra(BackupWorker.KEY_SOURCE_ID) ?: return
                cancelBackup(context, sourceId)
            }
        }
    }

    private fun cancelBackup(context: Context, sourceId: String) {
        // First tap: cooperative cancellation. Tell the running session (same process) to stop at a clean
        // boundary and write a resumable, incomplete-manifest checkpoint; the worker then winds down and
        // completes on its own. Do NOT also cancel the WorkManager job in that case -- an abrupt coroutine
        // teardown would race the cooperative wind-down and discard the clean checkpoint. This leaves any
        // periodic schedule intact (a Cancel tap stops the current run, it does not unschedule backups).
        if (BackupSessionRegistry.cancel(sourceId)) return

        // Otherwise -- no session in this process (queued/finished, or a non-default multi-process
        // WorkManager setup), OR a repeat tap on a session that is already winding down but wedged in
        // blocking I/O -- fall back to a hard WorkManager cancel. This also unschedules any periodic work
        // for the source, so a second tap doubles as "stop this backup for good".
        BackupWorker.cancel(context, sourceId)
    }
}
