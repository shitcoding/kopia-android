package com.kopia.android.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import com.kopia.android.repository.RepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Utility class for scheduling automatic backups.
 * Uses WorkManager for reliable background execution.
 */
class BackupScheduler(private val context: Context) {

    private val TAG = "BackupScheduler"
    private val prefs = context.getSharedPreferences("backup_schedule", Context.MODE_PRIVATE)

    /**
     * Schedule configuration data class
     */
    data class ScheduleConfig(
        val enabled: Boolean = false,
        val intervalHours: Long = 24,
        val requireCharging: Boolean = false,
        val requireWifi: Boolean = false,
        val sourcePath: String? = null
    )

    /**
     * Schedule automatic backups
     * @param config Schedule configuration
     * @return true if schedule was created successfully
     */
    fun scheduleBackup(config: ScheduleConfig): Boolean {
        try {
            // Save config to preferences
            saveConfig(config)

            if (!config.enabled) {
                cancelBackup()
                return true
            }

            // Build constraints
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)

            if (config.requireCharging) {
                constraints.setRequiresCharging(true)
            }

            if (config.requireWifi) {
                constraints.setRequiredNetworkType(NetworkType.UNMETERED)
            }

            // Create the work request
            val backupWork = PeriodicWorkRequestBuilder<BackupWorker>(
                config.intervalHours, TimeUnit.HOURS,
                // Add 15 minute flex period for better battery optimization
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints.build())
                .addTag(BACKUP_WORK_TAG)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            // Enqueue the work
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                backupWork
            )

            Log.i(TAG, "Backup scheduled: interval=${config.intervalHours}h, requireCharging=${config.requireCharging}, requireWifi=${config.requireWifi}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule backup", e)
            return false
        }
    }

    /**
     * Cancel scheduled backups
     */
    fun cancelBackup() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(BACKUP_WORK_NAME)
            Log.i(TAG, "Backup schedule cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel backup", e)
        }
    }

    /**
     * Check if a backup is currently scheduled
     * @return true if backup is scheduled
     */
    fun isBackupScheduled(): Boolean {
        val workInfo = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(BACKUP_WORK_NAME)
            .get()

        return workInfo.any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
    }

    /**
     * Get the current schedule configuration
     * @return Current schedule configuration
     */
    fun getConfig(): ScheduleConfig {
        return ScheduleConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            intervalHours = prefs.getLong(KEY_INTERVAL_HOURS, 24),
            requireCharging = prefs.getBoolean(KEY_REQUIRE_CHARGING, false),
            requireWifi = prefs.getBoolean(KEY_REQUIRE_WIFI, false),
            sourcePath = prefs.getString(KEY_SOURCE_PATH, null)
        )
    }

    /**
     * Save schedule configuration
     */
    private fun saveConfig(config: ScheduleConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, config.enabled)
            putLong(KEY_INTERVAL_HOURS, config.intervalHours)
            putBoolean(KEY_REQUIRE_CHARGING, config.requireCharging)
            putBoolean(KEY_REQUIRE_WIFI, config.requireWifi)
            putString(KEY_SOURCE_PATH, config.sourcePath)
            apply()
        }
    }

    /**
     * Get the status of the last backup
     * @return Status message or null if no backup has run
     */
    fun getLastBackupStatus(): String? {
        val timestamp = prefs.getLong(KEY_LAST_BACKUP_TIME, 0)
        val success = prefs.getBoolean(KEY_LAST_BACKUP_SUCCESS, false)

        if (timestamp == 0L) {
            return null
        }

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date(timestamp))

        return if (success) {
            "Last backup: $date (Success)"
        } else {
            "Last backup: $date (Failed)"
        }
    }

    /**
     * Worker class that performs the actual backup
     */
    class BackupWorker(
        context: Context,
        params: WorkerParameters
    ) : CoroutineWorker(context, params) {

        private val TAG = "BackupWorker"

        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Starting scheduled backup")

                // Get configuration
                val scheduler = BackupScheduler(applicationContext)
                val config = scheduler.getConfig()

                if (!config.enabled) {
                    Log.w(TAG, "Backup not enabled, skipping")
                    return@withContext Result.success()
                }

                // Initialize repository manager
                val repositoryManager = RepositoryManager(applicationContext)

                // Get the Kopia binary path
                val kopiaPath = File(applicationContext.filesDir, "kopia").absolutePath
                if (!File(kopiaPath).exists()) {
                    Log.e(TAG, "Kopia binary not found")
                    recordBackupResult(false)
                    return@withContext Result.failure()
                }

                // Get config directory
                val configDir = File(applicationContext.filesDir, ".kopia")
                if (!configDir.exists()) {
                    Log.e(TAG, "Kopia config directory not found")
                    recordBackupResult(false)
                    return@withContext Result.failure()
                }

                // Determine source path - use the configured path or default to app files
                val sourcePath = config.sourcePath ?: applicationContext.filesDir.absolutePath

                // Build the backup command
                val command = listOf(
                    kopiaPath,
                    "snapshot",
                    "create",
                    sourcePath
                )

                Log.i(TAG, "Running backup command: ${command.joinToString(" ")}")

                // Execute the backup
                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectErrorStream(true)
                processBuilder.environment().apply {
                    put("HOME", applicationContext.filesDir.absolutePath)
                    put("KOPIA_CONFIG_PATH", File(configDir, "repository.config").absolutePath)

                    // Read password from file if it exists
                    val passwordFile = File(configDir, "password.txt")
                    if (passwordFile.exists()) {
                        put("KOPIA_PASSWORD", passwordFile.readText().trim())
                    } else {
                        put("KOPIA_PASSWORD", "android")
                    }
                }

                val process = processBuilder.start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                Log.i(TAG, "Backup command completed with exit code $exitCode")
                Log.d(TAG, "Backup output: $output")

                val success = exitCode == 0
                recordBackupResult(success)

                // Send notification about backup result
                sendBackupNotification(success)

                return@withContext if (success) {
                    Result.success()
                } else {
                    Result.retry()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Backup failed with exception", e)
                recordBackupResult(false)
                sendBackupNotification(false)
                return@withContext Result.failure()
            }
        }

        /**
         * Record the result of the backup
         */
        private fun recordBackupResult(success: Boolean) {
            val prefs = applicationContext.getSharedPreferences("backup_schedule", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                putBoolean(KEY_LAST_BACKUP_SUCCESS, success)
                apply()
            }
        }

        /**
         * Send a notification about the backup result
         */
        private fun sendBackupNotification(success: Boolean) {
            try {
                val notificationHelper = NotificationHelper(applicationContext)
                if (success) {
                    notificationHelper.showBackupSuccessNotification()
                } else {
                    notificationHelper.showBackupFailureNotification()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send backup notification", e)
            }
        }
    }

    companion object {
        private const val BACKUP_WORK_NAME = "kopia_scheduled_backup"
        private const val BACKUP_WORK_TAG = "kopia_backup"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_REQUIRE_CHARGING = "require_charging"
        private const val KEY_REQUIRE_WIFI = "require_wifi"
        private const val KEY_SOURCE_PATH = "source_path"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_LAST_BACKUP_SUCCESS = "last_backup_success"

        @Volatile
        private var instance: BackupScheduler? = null

        @Synchronized
        fun getInstance(context: Context): BackupScheduler {
            if (instance == null) {
                instance = BackupScheduler(context.applicationContext)
            }
            return instance!!
        }
    }
}
