package org.kopiaKt.android.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

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
        // Cancel the work request
        BackupWorker.cancel(context, sourceId)
    }
}
