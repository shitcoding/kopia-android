package org.kopiaKt.android.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for executing backups.
 *
 * Supports both one-time and periodic backups with constraints
 * for battery, network, and charging state.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID)
            ?: return Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR, "Missing source ID")
                    .build()
            )

        return try {
            // Mark as running in foreground for long operations
            setForeground(createForegroundInfo())

            performBackup(sourceId)

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR, e.message)
                        .build()
                )
            }
        }
    }

    private suspend fun performBackup(sourceId: String) {
        // TODO: Implement actual backup logic using repository
        // This will be implemented when the full snapshot system is ready
    }

    private fun createForegroundInfo(): androidx.work.ForegroundInfo {
        // TODO: Create proper notification for foreground service
        // This requires the notification channel to be set up
        throw NotImplementedError("Foreground notification not yet implemented")
    }

    companion object {
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_ERROR = "error"
        private const val MAX_RETRY_COUNT = 3
        private const val UNIQUE_WORK_PREFIX = "backup_"

        /**
         * Schedules a one-time backup for the given source.
         */
        fun scheduleOneTime(
            context: Context,
            sourceId: String,
            constraints: BackupConstraints = BackupConstraints()
        ) {
            val workConstraints = constraints.toWorkConstraints()

            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_SOURCE_ID, sourceId)
                        .build()
                )
                .setConstraints(workConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "$UNIQUE_WORK_PREFIX$sourceId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }

        /**
         * Schedules periodic backups for the given source.
         */
        fun schedulePeriodic(
            context: Context,
            sourceId: String,
            intervalHours: Long,
            constraints: BackupConstraints = BackupConstraints()
        ) {
            val workConstraints = constraints.toWorkConstraints()

            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                intervalHours,
                TimeUnit.HOURS
            )
                .setInputData(
                    Data.Builder()
                        .putString(KEY_SOURCE_ID, sourceId)
                        .build()
                )
                .setConstraints(workConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "${UNIQUE_WORK_PREFIX}periodic_$sourceId",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }

        /**
         * Cancels all scheduled backups for the given source.
         */
        fun cancel(context: Context, sourceId: String) {
            WorkManager.getInstance(context)
                .cancelUniqueWork("$UNIQUE_WORK_PREFIX$sourceId")
            WorkManager.getInstance(context)
                .cancelUniqueWork("${UNIQUE_WORK_PREFIX}periodic_$sourceId")
        }

        private const val BACKOFF_DELAY_MINUTES = 5L
    }
}

/**
 * Constraints for backup execution.
 */
data class BackupConstraints(
    /**
     * Require the device to be charging.
     */
    val requiresCharging: Boolean = false,

    /**
     * Require WiFi connection (no metered network).
     */
    val requiresWifi: Boolean = true,

    /**
     * Require battery to not be low.
     */
    val requiresBatteryNotLow: Boolean = true,

    /**
     * Require device to be idle (API 23+).
     */
    val requiresDeviceIdle: Boolean = false,

    /**
     * Require sufficient storage space.
     */
    val requiresStorageNotLow: Boolean = true
)

/**
 * Converts BackupConstraints to WorkManager Constraints.
 */
internal fun BackupConstraints.toWorkConstraints(): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(
            if (requiresWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .setRequiresCharging(requiresCharging)
        .setRequiresBatteryNotLow(requiresBatteryNotLow)
        .setRequiresDeviceIdle(requiresDeviceIdle)
        .setRequiresStorageNotLow(requiresStorageNotLow)
        .build()
