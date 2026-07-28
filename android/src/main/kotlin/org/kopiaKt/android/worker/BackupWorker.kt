package org.kopiaKt.android.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kopiaKt.android.notification.BackupNotificationIds
import org.kopiaKt.android.notification.BackupNotificationManager
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.snapshot.upload.UploadCounters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WorkManager worker for executing backups.
 *
 * Supports both one-time and periodic backups with constraints
 * for battery, network, and charging state. Integrates with
 * the snapshot layer for actual backup operations and provides
 * foreground notifications for long-running operations.
 *
 * Features:
 * - Progress notifications with cancel action
 * - Checkpoint persistence for process death recovery
 * - Automatic retry with exponential backoff
 * - Constraint-based scheduling (WiFi, charging, battery)
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val notificationManager: BackupNotificationManager by lazy {
        val iconRes = inputData.getInt(KEY_NOTIFICATION_ICON, android.R.drawable.ic_popup_sync)
        BackupNotificationManager(applicationContext, iconRes)
    }

    private val checkpointStore: CheckpointStore by lazy {
        CheckpointStore(applicationContext)
    }

    // Latest progress, published (cheaply) by the upload callback and read by the throttled foreground
    // loop -- so we never build a Notification per uploaded byte-chunk.
    private val latestCounters = AtomicReference<UploadCounters?>(null)

    // Built once: the cancel action's PendingIntent is identical for the whole run.
    private val cancelPendingIntent: PendingIntent by lazy {
        createCancelIntent(inputData.getString(KEY_SOURCE_ID) ?: "")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Parse input data
        val sourceId = inputData.getString(KEY_SOURCE_ID)
            ?: return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Missing source ID"),
            )

        val sourcePath = inputData.getString(KEY_SOURCE_PATH)
            ?: return@withContext Result.failure(
                workDataOf(KEY_ERROR to "Missing source path"),
            )

        val configJson = inputData.getString(KEY_CONFIG)
        val config = if (configJson != null) {
            try {
                Json.decodeFromString<BackupWorkerConfig>(configJson)
            } catch (e: Exception) {
                BackupWorkerConfig()
            }
        } else {
            BackupWorkerConfig()
        }

        val notificationId = BackupNotificationIds.forSource(sourceId)

        try {
            // Ensure notification channels exist
            notificationManager.createNotificationChannels()

            // Set foreground with initial notification
            setForeground(buildProgressForegroundInfo(notificationId, sourcePath, counters = null))

            // Check for existing checkpoint
            val checkpointResult = checkpointStore.getCheckpoint(sourceId)
            val existingCheckpoint = when (checkpointResult) {
                is CheckpointResult.Found -> checkpointResult.checkpoint
                is CheckpointResult.Stale -> {
                    // Clear stale checkpoint
                    checkpointStore.clearCheckpoint(sourceId)
                    null
                }
                CheckpointResult.NotFound -> null
            }

            // Drive progress through setForeground (throttled) rather than notificationManager.notify:
            // notify() is silently dropped when POST_NOTIFICATIONS is denied on API 33+ (freezing the
            // notification at "Preparing..."), and is throttled past its ~5/sec rate limit even when
            // granted. The foreground-service update path avoids both; when the permission is denied the
            // FGS notification may surface only in the system's Task Manager, but it no longer freezes.
            val progressJob = launch { runForegroundProgressLoop(notificationId, sourcePath) }

            // Open repository and run backup
            val result = try {
                runBackup(
                    sourceId = sourceId,
                    sourcePath = sourcePath,
                    config = config,
                    existingCheckpoint = existingCheckpoint,
                )
            } finally {
                progressJob.cancel()
            }

            // Handle result
            when (result) {
                is BackupSessionResult.Success -> {
                    showCompletionNotification(
                        sourceId = sourceId,
                        sourcePath = sourcePath,
                        counters = result.counters,
                        durationMillis = result.durationMillis,
                    )
                    Result.success(
                        workDataOf(
                            KEY_MANIFEST_ID to result.manifestId.value,
                            KEY_FILES_COUNT to (result.counters.totalCachedFiles + result.counters.totalHashedFiles),
                            KEY_BYTES_TOTAL to (result.counters.totalCachedBytes + result.counters.totalHashedBytes),
                            KEY_DURATION_MILLIS to result.durationMillis,
                        ),
                    )
                }

                is BackupSessionResult.Cancelled -> {
                    // Don't show error for cancellation
                    Result.failure(
                        workDataOf(KEY_ERROR to "Backup cancelled"),
                    )
                }

                is BackupSessionResult.Failed -> {
                    if (runAttemptCount < MAX_RETRY_COUNT && result.checkpointSaved) {
                        // Retry with checkpoint
                        Result.retry()
                    } else {
                        showErrorNotification(sourceId, sourcePath, result.error.message ?: "Unknown error")
                        Result.failure(
                            workDataOf(KEY_ERROR to result.error.message),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            // WorkManager cancellation - session should have saved checkpoint
            Result.failure(workDataOf(KEY_ERROR to "Backup cancelled"))
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                showErrorNotification(sourceId, sourcePath, e.message ?: "Unknown error")
                Result.failure(workDataOf(KEY_ERROR to e.message))
            }
        }
    }

    private suspend fun runBackup(
        sourceId: String,
        sourcePath: String,
        config: BackupWorkerConfig,
        existingCheckpoint: BackupCheckpoint?,
    ): BackupSessionResult {
        // Get repository from the repository provider
        // Note: In a real implementation, this would come from a RepositoryProvider
        // that handles repository opening/caching. For now, we throw if not configured.
        val repository = getRepository()
            ?: throw IllegalStateException("Repository not configured. Call BackupWorker.setRepositoryProvider first.")

        val sessionConfig = BackupSessionConfig(
            sourcePath = sourcePath,
            sourceId = sourceId,
            description = config.description,
            tags = config.tags,
            parallelUploads = config.parallelUploads,
            forceHashPercentage = config.forceHashPercentage,
            checkpointOptions = CheckpointOptions(
                intervalMillis = config.checkpointIntervalMillis,
                minBytesBeforeCheckpoint = config.minBytesBeforeCheckpoint,
            ),
        )

        val callback = object : BackupSessionCallback {
            override fun onProgress(counters: UploadCounters) {
                // Cheap: just publish the latest counters. The throttled foreground loop turns them into a
                // notification at most once per interval, instead of rebuilding one per uploaded byte-chunk.
                latestCounters.set(counters)
            }

            override fun onCheckpointSaved(checkpoint: BackupCheckpoint) {
                // Checkpoint saved - nothing special to do
            }

            override fun onComplete(result: BackupSessionResult) {
                // Completion handled in doWork
            }
        }

        val session = BackupSession(
            repository = repository,
            config = sessionConfig,
            checkpointStore = checkpointStore,
            callback = callback,
            context = applicationContext,
        )
        // Publish the running session so a Cancel tap (BackupCancelReceiver, same process) can route through
        // BackupSession.cancel() cooperatively -- WorkManager makes CoroutineWorker.onStopped final, so the
        // stop otherwise only cancels the coroutine (an abrupt teardown, no clean incomplete-manifest
        // checkpoint). See BackupSessionRegistry.
        BackupSessionRegistry.register(sourceId, session)
        try {
            // Two sources share one DirectRepositoryImpl and one ContentManager. The mutexes inside
            // make that memory-safe, but one session's flush() would commit the other's half-written
            // packs and manifest state -- Go isolates each upload in its own WriteSession. Backups
            // therefore run one at a time process-wide.
            return repositoryMutex.withLock { session.run(existingCheckpoint) }
        } finally {
            BackupSessionRegistry.unregister(sourceId, session)
        }
    }

    private fun getRepository(): DirectRepository? = repositoryProvider?.invoke(applicationContext)

    /**
     * Periodically refreshes the foreground notification with the latest progress. Time-throttled to one
     * update per [PROGRESS_UPDATE_INTERVAL_MILLIS] (the upload publishes counters far more often), so we
     * neither rebuild a Notification per byte-chunk nor hit the framework's notification rate limit.
     * Cancelled by [doWork] once the backup finishes; a failed foreground update is best-effort and must
     * not abort the backup.
     */
    private suspend fun runForegroundProgressLoop(notificationId: Int, sourcePath: String) {
        while (true) {
            delay(PROGRESS_UPDATE_INTERVAL_MILLIS)
            val counters = latestCounters.get() ?: continue
            try {
                setForeground(buildProgressForegroundInfo(notificationId, sourcePath, counters))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort progress update; keep backing up.
            }
        }
    }

    private fun buildProgressForegroundInfo(
        notificationId: Int,
        sourcePath: String,
        counters: UploadCounters?,
    ): ForegroundInfo {
        val notification = if (counters == null) {
            notificationManager.buildProgressNotification(
                sourceId = inputData.getString(KEY_SOURCE_ID) ?: "",
                sourcePath = sourcePath,
                currentFile = null,
                progress = null,
                cancelIntent = cancelPendingIntent,
            )
        } else {
            notificationManager.buildProgressNotification(
                sourceId = inputData.getString(KEY_SOURCE_ID) ?: "",
                sourcePath = sourcePath,
                currentFile = counters.currentDirectory.takeIf { it.isNotEmpty() },
                progress = computeProgressPercent(counters),
                processedBytes = counters.totalCachedBytes + counters.totalHashedBytes,
                totalBytes = counters.estimatedBytes,
                processedFiles = counters.totalCachedFiles + counters.totalHashedFiles,
                cancelIntent = cancelPendingIntent,
            )
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Percentage (0..99) of [counters] against the estimated total, or null (indeterminate) when no
     * estimate is available yet. Capped at 99 so the bar never shows 100% before completion.
     */
    internal fun computeProgressPercent(counters: UploadCounters): Int? {
        val totalBytes = counters.totalCachedBytes + counters.totalHashedBytes
        val estimatedBytes = counters.estimatedBytes
        return if (estimatedBytes > 0) {
            ((totalBytes * 100) / estimatedBytes).toInt().coerceIn(0, 99)
        } else {
            null
        }
    }

    private fun showCompletionNotification(
        sourceId: String,
        sourcePath: String,
        counters: UploadCounters,
        durationMillis: Long,
    ) {
        val notification = notificationManager.buildCompletionNotification(
            sourcePath = sourcePath,
            filesCount = counters.totalCachedFiles + counters.totalHashedFiles,
            totalBytes = counters.totalCachedBytes + counters.totalHashedBytes,
            duration = durationMillis,
        )
        notificationManager.notify(BackupNotificationIds.completionForSource(sourceId), notification)
    }

    private fun showErrorNotification(sourceId: String, sourcePath: String, errorMessage: String) {
        val notification = notificationManager.buildErrorNotification(
            sourcePath = sourcePath,
            errorMessage = errorMessage,
        )
        notificationManager.notify(BackupNotificationIds.errorForSource(sourceId), notification)
    }

    private fun createCancelIntent(sourceId: String): PendingIntent {
        val intent = Intent(applicationContext, BackupCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_BACKUP
            putExtra(KEY_SOURCE_ID, sourceId)
        }
        return PendingIntent.getBroadcast(
            applicationContext,
            // Not sourceId.hashCode(): PendingIntent matching ignores extras, so two sources whose ids
            // collide (trivial for path-based ids -- "Aa" and "BB" hash alike) would share one
            // PendingIntent, and the second FLAG_UPDATE_CURRENT would rewrite the first's source id.
            // Cancel on one backup would then cancel the other. The notification registry already
            // hands out collision-free per-source slots.
            BackupNotificationIds.forSource(sourceId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Serializes every backup in this process; see the call site in [runBackup]. */
        private val repositoryMutex = Mutex()

        const val KEY_SOURCE_ID = "source_id"
        const val KEY_SOURCE_PATH = "source_path"
        const val KEY_CONFIG = "config"
        const val KEY_NOTIFICATION_ICON = "notification_icon"
        const val KEY_ERROR = "error"
        const val KEY_MANIFEST_ID = "manifest_id"
        const val KEY_FILES_COUNT = "files_count"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_DURATION_MILLIS = "duration_millis"

        const val ACTION_CANCEL_BACKUP = "org.kopiaKt.android.CANCEL_BACKUP"

        private const val MAX_RETRY_COUNT = 3
        private const val UNIQUE_WORK_PREFIX = "backup_"
        private const val BACKOFF_DELAY_MINUTES = 5L

        /** Minimum interval between foreground progress-notification refreshes (throttle). */
        private const val PROGRESS_UPDATE_INTERVAL_MILLIS = 1000L

        /**
         * Repository provider function.
         *
         * Must be set before scheduling backups. The provider is called with the
         * application context and should return an opened DirectRepository.
         */
        @Volatile
        var repositoryProvider: ((Context) -> DirectRepository?)? = null

        /**
         * Schedules a one-time backup for the given source.
         *
         * @param context Application context
         * @param sourceId Unique identifier for the backup source
         * @param sourcePath Path to the directory to back up
         * @param config Backup configuration
         * @param constraints Execution constraints
         * @param notificationIcon Resource ID for notification icon
         */
        fun scheduleOneTime(
            context: Context,
            sourceId: String,
            sourcePath: String,
            config: BackupWorkerConfig = BackupWorkerConfig(),
            constraints: BackupConstraints = BackupConstraints(),
            @DrawableRes notificationIcon: Int = android.R.drawable.ic_popup_sync,
            existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ): Operation {
            val workConstraints = constraints.toWorkConstraints()

            val inputData = Data.Builder()
                .putString(KEY_SOURCE_ID, sourceId)
                .putString(KEY_SOURCE_PATH, sourcePath)
                .putString(KEY_CONFIG, Json.encodeToString(config))
                .putInt(KEY_NOTIFICATION_ICON, notificationIcon)
                .build()

            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .addTag(UNIQUE_WORK_PREFIX) // so cancelAll (cancelAllWorkByTag) actually matches this work
                .setInputData(inputData)
                .setConstraints(workConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()

            // KEEP, not REPLACE: a second "back up now" tap used to cancel the running backup and
            // start again from nothing, which on a large first backup is the worst possible answer
            // to an impatient user.
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    uniqueWorkName(sourceId),
                    existingWorkPolicy,
                    request,
                )
        }

        /** The unique-work name a source's one-time backup is enqueued under. */
        fun uniqueWorkName(sourceId: String): String = "$UNIQUE_WORK_PREFIX$sourceId"

        /**
         * Schedules periodic backups for the given source.
         *
         * @param context Application context
         * @param sourceId Unique identifier for the backup source
         * @param sourcePath Path to the directory to back up
         * @param intervalHours Interval between backups in hours
         * @param config Backup configuration
         * @param constraints Execution constraints
         * @param notificationIcon Resource ID for notification icon
         */
        fun schedulePeriodic(
            context: Context,
            sourceId: String,
            sourcePath: String,
            intervalHours: Long,
            config: BackupWorkerConfig = BackupWorkerConfig(),
            constraints: BackupConstraints = BackupConstraints(),
            @DrawableRes notificationIcon: Int = android.R.drawable.ic_popup_sync,
        ) {
            val workConstraints = constraints.toWorkConstraints()

            val inputData = Data.Builder()
                .putString(KEY_SOURCE_ID, sourceId)
                .putString(KEY_SOURCE_PATH, sourcePath)
                .putString(KEY_CONFIG, Json.encodeToString(config))
                .putInt(KEY_NOTIFICATION_ICON, notificationIcon)
                .build()

            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                intervalHours,
                TimeUnit.HOURS,
            )
                .addTag(UNIQUE_WORK_PREFIX) // so cancelAll (cancelAllWorkByTag) actually matches this work
                .setInputData(inputData)
                .setConstraints(workConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "${UNIQUE_WORK_PREFIX}periodic_$sourceId",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
        }

        /**
         * Cancels both the current run and the source's schedule.
         */
        fun cancel(context: Context, sourceId: String): Operation {
            cancelOneTime(context, sourceId)
            return WorkManager.getInstance(context)
                .cancelUniqueWork("${UNIQUE_WORK_PREFIX}periodic_$sourceId")
        }

        /**
         * Cancels the source's current run only, leaving its schedule alone -- stopping a backup is
         * not the same as never backing this source up again.
         */
        fun cancelOneTime(context: Context, sourceId: String): Operation {
            val workManager = WorkManager.getInstance(context)
            return workManager.cancelUniqueWork(uniqueWorkName(sourceId))
        }

        /**
         * Cancels all scheduled backups.
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK_PREFIX)
        }

        /**
         * Gets the work info for a backup source.
         */
        fun getWorkInfo(context: Context, sourceId: String) = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData("$UNIQUE_WORK_PREFIX$sourceId")
    }
}

/**
 * Configuration for backup worker operations.
 */
@Serializable
data class BackupWorkerConfig(
    /** Description for the snapshot. */
    val description: String = "",

    /** Tags to attach to the snapshot. */
    val tags: Map<String, String> = emptyMap(),

    /** Number of parallel uploads (1-8). */
    val parallelUploads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),

    /** Percentage of files to force re-hash for validation (0-100). */
    val forceHashPercentage: Int = 0,

    /** Checkpoint interval in milliseconds. */
    val checkpointIntervalMillis: Long = CheckpointOptions.DEFAULT_CHECKPOINT_INTERVAL_MILLIS,

    /** Minimum bytes before creating first checkpoint. */
    val minBytesBeforeCheckpoint: Long = CheckpointOptions.DEFAULT_MIN_BYTES_BEFORE_CHECKPOINT,
)

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
    val requiresStorageNotLow: Boolean = true,

    /**
     * Require any network at all. False for a local repository, where waiting for connectivity would
     * block a backup that needs none.
     */
    val requiresNetwork: Boolean = true,
) {
    companion object {
        /**
         * Constraints for a backup the user just asked for. The scheduled defaults require an
         * unmetered network and a healthy battery, which is right for "every night" and wrong for
         * "now" -- a user on cellular taps Back Up Now and nothing happens at all.
         */
        fun interactive(): BackupConstraints = BackupConstraints(
            requiresWifi = false,
            requiresBatteryNotLow = false,
            requiresStorageNotLow = false,
            requiresNetwork = false,
        )
    }
}

/**
 * Converts BackupConstraints to WorkManager Constraints.
 */
internal fun BackupConstraints.toWorkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(
        when {
            !requiresNetwork -> NetworkType.NOT_REQUIRED
            requiresWifi -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        },
    )
    .setRequiresCharging(requiresCharging)
    .setRequiresBatteryNotLow(requiresBatteryNotLow)
    .setRequiresDeviceIdle(requiresDeviceIdle)
    .setRequiresStorageNotLow(requiresStorageNotLow)
    .build()
