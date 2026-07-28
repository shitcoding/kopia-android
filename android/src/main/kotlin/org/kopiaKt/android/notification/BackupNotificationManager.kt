package org.kopiaKt.android.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat

/**
 * Notification IDs used by backup operations.
 */
object BackupNotificationIds {
    // The two fixed restore IDs below live BELOW [BACKUP_PROGRESS_BASE, ...), the range the per-source
    // backup registry hands out, so a per-source backup id can never collide with a fixed id. Restore is a
    // single-at-a-time operation, so it keeps fixed ids.

    /** ID for restore progress notifications. */
    const val RESTORE_PROGRESS = 3

    /** ID for restore completion notifications. */
    const val RESTORE_COMPLETE = 4

    /** Base ID for per-source backup notifications; each source owns a contiguous slot of [SLOT_SIZE] ids. */
    const val BACKUP_PROGRESS_BASE = 1000

    // Each source gets a slot of 3 consecutive ids: progress, completion, error. The old design used a
    // single per-source progress id PLUS the fixed BACKUP_COMPLETE/BACKUP_ERROR shared by ALL sources, so
    // two sources finishing close together overwrote each other's completion/error notification. Handing
    // each source its own {progress, completion, error} triple is collision-free by construction — a
    // single registry + counter, no separate ranges to keep disjoint. Ids only need to be stable within a
    // process (a backup runs in one WorkManager worker process; notifications don't survive process death).
    private const val SLOT_SIZE = 3
    private val slotBySource = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val nextSlot = java.util.concurrent.atomic.AtomicInteger(0)

    private fun slotBase(sourceId: String): Int = BACKUP_PROGRESS_BASE + SLOT_SIZE * slotBySource.computeIfAbsent(sourceId) { nextSlot.getAndIncrement() }

    /** Returns a distinct, stable progress-notification ID for [sourceId] (collision-free across sources). */
    fun forSource(sourceId: String): Int = slotBase(sourceId)

    /** Returns a distinct, stable completion-notification ID for [sourceId] (collision-free across sources). */
    fun completionForSource(sourceId: String): Int = slotBase(sourceId) + 1

    /** Returns a distinct, stable error-notification ID for [sourceId] (collision-free across sources). */
    fun errorForSource(sourceId: String): Int = slotBase(sourceId) + 2
}

/**
 * Notification channels used by backup operations.
 */
object BackupNotificationChannels {
    /** Channel for backup/restore progress notifications (shown during operations). */
    const val PROGRESS = "kopiaKt_backup_progress"

    /** Channel for backup completion notifications. */
    const val COMPLETION = "kopiaKt_backup_completion"

    /** Channel for error notifications. */
    const val ERROR = "kopiaKt_backup_error"
}

/**
 * Manages notifications for backup and restore operations.
 *
 * Handles notification channel creation and provides builders for
 * progress, completion, and error notifications.
 */
class BackupNotificationManager(
    private val context: Context,
    @DrawableRes private val smallIcon: Int,
) {
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Creates all required notification channels.
     *
     * Should be called at app startup, before any notifications are posted.
     */
    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                BackupNotificationChannels.PROGRESS,
                "Backup Progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress during backup and restore operations"
                setShowBadge(false)
            }

            val completionChannel = NotificationChannel(
                BackupNotificationChannels.COMPLETION,
                "Backup Complete",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies when backup or restore operations complete"
            }

            val errorChannel = NotificationChannel(
                BackupNotificationChannels.ERROR,
                "Backup Errors",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifies about backup or restore errors"
            }

            notificationManager.createNotificationChannels(
                listOf(progressChannel, completionChannel, errorChannel),
            )
        }
    }

    /**
     * Builds a progress notification for an ongoing backup operation.
     *
     * @param sourceId The backup source identifier
     * @param sourcePath The path being backed up
     * @param currentFile Current file being processed (optional)
     * @param progress Progress percentage (0-100), or null for indeterminate
     * @param processedBytes Bytes processed so far
     * @param totalBytes Total bytes to process (0 if unknown)
     * @param processedFiles Number of files processed
     * @param cancelIntent PendingIntent to trigger cancellation
     * @return A notification suitable for foreground service
     */
    fun buildProgressNotification(
        sourceId: String,
        sourcePath: String,
        currentFile: String? = null,
        progress: Int? = null,
        processedBytes: Long = 0,
        totalBytes: Long = 0,
        processedFiles: Int = 0,
        cancelIntent: PendingIntent? = null,
    ): Notification {
        val title = "Backing up: $sourcePath"
        val text = buildProgressText(currentFile, processedFiles, processedBytes, totalBytes)

        return NotificationCompat.Builder(context, BackupNotificationChannels.PROGRESS)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (progress != null) {
                    setProgress(100, progress.coerceIn(0, 100), false)
                } else {
                    setProgress(0, 0, true)
                }

                cancelIntent?.let { intent ->
                    addAction(
                        NotificationCompat.Action.Builder(
                            0, // No icon needed
                            "Cancel",
                            intent,
                        ).build(),
                    )
                }

                // Add details in expanded view
                if (currentFile != null || processedBytes > 0) {
                    val style = NotificationCompat.BigTextStyle()
                        .bigText(buildDetailedProgressText(currentFile, processedFiles, processedBytes, totalBytes))
                    setStyle(style)
                }
            }
            .build()
    }

    /**
     * Builds a notification for a completed backup.
     *
     * @param sourcePath The path that was backed up
     * @param filesCount Number of files backed up
     * @param totalBytes Total bytes backed up
     * @param duration Duration in milliseconds
     * @param contentIntent PendingIntent to open backup details
     * @return A completion notification
     */
    fun buildCompletionNotification(
        sourcePath: String,
        filesCount: Int,
        totalBytes: Long,
        duration: Long,
        contentIntent: PendingIntent? = null,
    ): Notification {
        val title = "Backup complete"
        val text = "Backed up $filesCount files (${formatBytes(totalBytes)})"

        return NotificationCompat.Builder(context, BackupNotificationChannels.COMPLETION)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply {
                contentIntent?.let { setContentIntent(it) }

                val style = NotificationCompat.BigTextStyle()
                    .bigText("Backed up $sourcePath\n$filesCount files, ${formatBytes(totalBytes)}\nDuration: ${formatDuration(duration)}")
                setStyle(style)
            }
            .build()
    }

    /**
     * Builds an error notification for a failed backup.
     *
     * @param sourcePath The path that failed to back up
     * @param errorMessage The error description
     * @param retryIntent PendingIntent to retry the backup (optional)
     * @param detailsIntent PendingIntent to view error details (optional)
     * @return An error notification
     */
    fun buildErrorNotification(
        sourcePath: String,
        errorMessage: String,
        retryIntent: PendingIntent? = null,
        detailsIntent: PendingIntent? = null,
        /**
         * False when the snapshot WAS saved but skipped entries it could not read. Announcing that
         * as "Backup failed" would send someone hunting for a backup that is sitting in the
         * repository, complete and restorable.
         */
        failed: Boolean = true,
    ): Notification = NotificationCompat.Builder(context, BackupNotificationChannels.ERROR)
        .setSmallIcon(smallIcon)
        .setContentTitle(if (failed) "Backup failed" else "Backup completed with errors")
        .setContentText(if (failed) "Failed to backup: $sourcePath" else "$sourcePath: $errorMessage")
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .apply {
            val style = NotificationCompat.BigTextStyle().bigText(
                if (failed) {
                    "Failed to backup: $sourcePath\n\nError: $errorMessage"
                } else {
                    "Backed up: $sourcePath\n\nSome entries were skipped: $errorMessage"
                },
            )
            setStyle(style)

            retryIntent?.let { intent ->
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        "Retry",
                        intent,
                    ).build(),
                )
            }

            detailsIntent?.let { intent ->
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        "Details",
                        intent,
                    ).build(),
                )
            }
        }
        .build()

    /**
     * Builds a progress notification for an ongoing restore operation.
     */
    fun buildRestoreProgressNotification(
        destinationPath: String,
        currentFile: String? = null,
        progress: Int? = null,
        processedBytes: Long = 0,
        totalBytes: Long = 0,
        processedFiles: Int = 0,
        cancelIntent: PendingIntent? = null,
    ): Notification {
        val title = "Restoring to: $destinationPath"
        val text = buildProgressText(currentFile, processedFiles, processedBytes, totalBytes)

        return NotificationCompat.Builder(context, BackupNotificationChannels.PROGRESS)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (progress != null) {
                    setProgress(100, progress.coerceIn(0, 100), false)
                } else {
                    setProgress(0, 0, true)
                }

                cancelIntent?.let { intent ->
                    addAction(
                        NotificationCompat.Action.Builder(
                            0,
                            "Cancel",
                            intent,
                        ).build(),
                    )
                }
            }
            .build()
    }

    /**
     * Builds a completion notification for a restore operation.
     */
    fun buildRestoreCompletionNotification(
        destinationPath: String,
        filesCount: Int,
        totalBytes: Long,
        duration: Long,
        contentIntent: PendingIntent? = null,
    ): Notification {
        val title = "Restore complete"
        val text = "Restored $filesCount files (${formatBytes(totalBytes)})"

        return NotificationCompat.Builder(context, BackupNotificationChannels.COMPLETION)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply {
                contentIntent?.let { setContentIntent(it) }

                val style = NotificationCompat.BigTextStyle()
                    .bigText("Restored to $destinationPath\n$filesCount files, ${formatBytes(totalBytes)}\nDuration: ${formatDuration(duration)}")
                setStyle(style)
            }
            .build()
    }

    /**
     * Shows a notification.
     */
    fun notify(notificationId: Int, notification: Notification) {
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Cancels a notification.
     */
    fun cancel(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    private fun buildProgressText(
        currentFile: String?,
        processedFiles: Int,
        processedBytes: Long,
        totalBytes: Long,
    ): String {
        val parts = mutableListOf<String>()

        if (processedFiles > 0) {
            parts.add("$processedFiles files")
        }

        if (processedBytes > 0) {
            val bytesText = if (totalBytes > 0) {
                "${formatBytes(processedBytes)} / ${formatBytes(totalBytes)}"
            } else {
                formatBytes(processedBytes)
            }
            parts.add(bytesText)
        }

        return if (parts.isEmpty()) {
            currentFile ?: "Preparing..."
        } else {
            parts.joinToString(" - ")
        }
    }

    private fun buildDetailedProgressText(
        currentFile: String?,
        processedFiles: Int,
        processedBytes: Long,
        totalBytes: Long,
    ): String {
        val lines = mutableListOf<String>()

        if (processedFiles > 0) {
            lines.add("Files processed: $processedFiles")
        }

        if (processedBytes > 0) {
            if (totalBytes > 0) {
                val percent = (processedBytes * 100 / totalBytes).toInt()
                lines.add("Progress: ${formatBytes(processedBytes)} / ${formatBytes(totalBytes)} ($percent%)")
            } else {
                lines.add("Processed: ${formatBytes(processedBytes)}")
            }
        }

        if (currentFile != null) {
            lines.add("Current: $currentFile")
        }

        return lines.joinToString("\n")
    }

    companion object {
        /**
         * Formats bytes into human-readable format.
         */
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }

        /**
         * Formats duration in milliseconds to human-readable format.
         */
        fun formatDuration(millis: Long): String {
            val seconds = millis / 1000
            val minutes = seconds / 60
            val hours = minutes / 60

            return when {
                hours > 0 -> "${hours}h ${minutes % 60}m"
                minutes > 0 -> "${minutes}m ${seconds % 60}s"
                else -> "${seconds}s"
            }
        }
    }
}
