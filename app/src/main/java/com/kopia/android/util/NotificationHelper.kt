package com.kopia.android.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kopia.android.MainActivity
import com.kopia.android.R

/**
 * Utility class for managing notifications in the app.
 * Handles creation of notification channels and sending notifications.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    /**
     * Create notification channels for different types of notifications
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Server status channel
            val serverChannel = NotificationChannel(
                CHANNEL_SERVER_STATUS,
                "Server Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications about Kopia server status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serverChannel)

            // Backup notifications channel
            val backupChannel = NotificationChannel(
                CHANNEL_BACKUP,
                "Backup Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications about backup operations"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(backupChannel)

            // Error notifications channel
            val errorChannel = NotificationChannel(
                CHANNEL_ERRORS,
                "Error Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important error notifications"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(errorChannel)
        }
    }

    /**
     * Show a notification that a backup was successful
     */
    fun showBackupSuccessNotification() {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setContentTitle("Backup Completed")
            .setContentText("Your data has been successfully backed up")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID_BACKUP_SUCCESS, notification)
    }

    /**
     * Show a notification that a backup failed
     */
    fun showBackupFailureNotification() {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ERRORS)
            .setContentTitle("Backup Failed")
            .setContentText("There was a problem backing up your data. Tap to view details.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(NOTIFICATION_ID_BACKUP_FAILURE, notification)
    }

    /**
     * Show a notification for backup progress
     */
    fun showBackupProgressNotification(progress: Int, total: Int): Notification {
        val notification = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setContentTitle("Backup in Progress")
            .setContentText("Backing up your data...")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setProgress(total, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID_BACKUP_PROGRESS, notification)
        return notification
    }

    /**
     * Cancel the backup progress notification
     */
    fun cancelBackupProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID_BACKUP_PROGRESS)
    }

    /**
     * Show a notification that the server started
     */
    fun showServerStartedNotification() {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SERVER_STATUS)
            .setContentTitle("Kopia Server Started")
            .setContentText("Server is now running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SERVER_STARTED, notification)
    }

    /**
     * Show a notification that the server stopped
     */
    fun showServerStoppedNotification() {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SERVER_STATUS)
            .setContentTitle("Kopia Server Stopped")
            .setContentText("Server has been stopped")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SERVER_STOPPED, notification)
    }

    /**
     * Show a notification about an error
     */
    fun showErrorNotification(title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ERRORS)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }

    /**
     * Show a notification for restore completion
     */
    fun showRestoreCompleteNotification(success: Boolean) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (success) {
            NotificationCompat.Builder(context, CHANNEL_BACKUP)
                .setContentTitle("Restore Completed")
                .setContentText("Your files have been successfully restored")
                .setSmallIcon(android.R.drawable.ic_menu_revert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_ERRORS)
                .setContentTitle("Restore Failed")
                .setContentText("There was a problem restoring your files")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        }

        notificationManager.notify(NOTIFICATION_ID_RESTORE, notification)
    }

    /**
     * Clear all notifications
     */
    fun clearAllNotifications() {
        notificationManager.cancelAll()
    }

    companion object {
        // Notification channels
        private const val CHANNEL_SERVER_STATUS = "server_status"
        private const val CHANNEL_BACKUP = "backup"
        private const val CHANNEL_ERRORS = "errors"

        // Notification IDs
        private const val NOTIFICATION_ID_BACKUP_SUCCESS = 1001
        private const val NOTIFICATION_ID_BACKUP_FAILURE = 1002
        private const val NOTIFICATION_ID_BACKUP_PROGRESS = 1003
        private const val NOTIFICATION_ID_SERVER_STARTED = 2001
        private const val NOTIFICATION_ID_SERVER_STOPPED = 2002
        private const val NOTIFICATION_ID_ERROR = 3001
        private const val NOTIFICATION_ID_RESTORE = 4001

        @Volatile
        private var instance: NotificationHelper? = null

        @Synchronized
        fun getInstance(context: Context): NotificationHelper {
            if (instance == null) {
                instance = NotificationHelper(context.applicationContext)
            }
            return instance!!
        }
    }
}
