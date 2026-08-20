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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kopiaKt.android.notification.BackupNotificationIds
import org.kopiaKt.android.notification.BackupNotificationManager
import org.kopiaKt.core.blob.HostKeyNotTrustedException
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.core.blob.RepositoryUnavailableException
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.snapshot.RepositoryWriteLock
import org.kopiaKt.snapshot.upload.UploadCounters
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A failure that retrying cannot fix, because what has to change is outside this worker.
 *
 * "Retryable" was never a design: several of the ways a phone backup ends cannot be retried at all,
 * and a blind `Result.retry()` makes them worse — it burns the backoff schedule re-discovering the
 * same refusal while the user is shown nothing. The [message] is written for them, not for a log.
 */
private class BackupBlocked(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    /**
     * The same instance the UI holds. A second one would cache its own copy of the whole source map
     * and overwrite this one's writes on its next save, which is how a recorded failure would be
     * erased before anyone saw it.
     */
    private val sourceManager: BackupSourceManager by lazy {
        BackupSourceManager.getInstance(applicationContext)
    }

    /**
     * Records a terminal failure against the source and returns the WorkManager result.
     *
     * `Result.failure`'s output data is read by the interactive await and by nothing else, and the
     * error notification is dropped outright on API 33+ when POST_NOTIFICATIONS was denied. So for a
     * run that dies in the background there was no evidence anywhere that the backup had failed --
     * the user's belief that their files are backed up simply stayed wrong. This leaves something
     * durable for the app to show.
     */
    /**
     * Ends a run: records [message] against the source, then tries to tell the user.
     *
     * In that order, and never the other way. Building a Notification allocates, and on the path
     * this matters most for — an exhausted heap — it is the likelier of the two to throw. Notifying
     * first cost the durable record the dashboard reads; and because these run inside catch blocks,
     * where a sibling catch cannot help, a throw from the notification also escaped `doWork`
     * entirely — or, from the Failed branch, was caught by `catch (e: Exception)` below and turned a
     * terminal failure into `Result.retry()`. Both reviewers found this.
     */
    private fun endRun(sourceId: String, sourcePath: String, message: String): Result {
        val result = recordTerminal(sourceId, message)
        runCatching { showErrorNotification(sourceId, sourcePath, message) }
            .onFailure { android.util.Log.w(TAG, "could not show the failure notification", it) }
        return result
    }

    private fun recordTerminal(sourceId: String, message: String?): Result {
        val reason = message ?: "Unknown error"
        runCatching { sourceManager.recordFailure(sourceId, reason) }
            .onFailure { android.util.Log.w(TAG, "could not record the failure for $sourceId", it) }
        return Result.failure(workDataOf(KEY_ERROR to reason))
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

            // Set foreground with initial notification.
            //
            // A refusal here is terminal, not transient. From API 31 the system rejects a foreground
            // service started from the background outright, and after Android 15's six-hour dataSync
            // cap has fired no further dataSync service starts in that 24-hour window until the user
            // brings the app forward. In both cases every retry meets exactly the same refusal, so
            // retrying only spends the backoff schedule finding that out — while the user sees
            // nothing at all, because there is no notification to see.
            try {
                setForeground(buildProgressForegroundInfo(notificationId, sourcePath, counters = null))
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                throw BackupBlocked(FOREGROUND_DENIED_MESSAGE, e)
            }

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
                    // Symmetrical with recordTerminal, and required for it to be honest: a run that
                    // succeeds out here never goes through the interactive bridge, so without this a
                    // source would keep showing yesterday's failure above a snapshot written minutes
                    // ago -- the mirror of the bug the failure record exists to fix. Clearing is a
                    // side effect of recording the success, so the two cannot drift apart.
                    runCatching { sourceManager.updateLastSnapshotTime(sourceId, Instant.now()) }
                        .onFailure { android.util.Log.w(TAG, "could not record the success for $sourceId", it) }
                    if (result.completedWithErrors) {
                        // A complete, saved, usable snapshot that skipped entries it could not
                        // read. Not a plain success -- the user should know -- and deliberately not
                        // a WorkManager failure, which would retry a snapshot that is already
                        // written and valid.
                        showErrorNotification(
                            sourceId,
                            sourcePath,
                            "${result.fatalErrorCount} entr${if (result.fatalErrorCount == 1) "y" else "ies"} skipped",
                            failed = false,
                        )
                    } else {
                        showCompletionNotification(
                            sourceId = sourceId,
                            sourcePath = sourcePath,
                            counters = result.counters,
                            durationMillis = result.durationMillis,
                        )
                    }
                    // The counters ride along with the result. The progress loop delays BEFORE its
                    // first publish, so a backup that finishes inside one second -- an incremental
                    // run with nothing to do, which is most of them -- would otherwise leave the
                    // finished task showing an empty counter map. Go reports once more after the
                    // upload returns for the same reason.
                    Result.success(
                        Data.Builder()
                            .putAll(result.counters.toProgressData())
                            .putString(KEY_MANIFEST_ID, result.manifestId.value)
                            .putInt(
                                KEY_FILES_COUNT,
                                result.counters.totalCachedFiles + result.counters.totalHashedFiles,
                            )
                            .putLong(
                                KEY_BYTES_TOTAL,
                                result.counters.totalCachedBytes + result.counters.totalHashedBytes,
                            )
                            .putLong(KEY_DURATION_MILLIS, result.durationMillis)
                            .putInt(KEY_ERROR_COUNT, result.fatalErrorCount)
                            .build(),
                    )
                }

                is BackupSessionResult.Cancelled -> reportCancellation(sourceId, sourcePath)

                is BackupSessionResult.Failed -> {
                    // A backup that fails silently is unusable: without this, the only evidence a
                    // user or a developer has is a notification with a one-line message.
                    android.util.Log.e(TAG, "backup of $sourcePath failed", result.error)
                    val retryable = runAttemptCount < MAX_RETRY_COUNT && result.checkpointSaved
                    if (retryable && !isTerminalFailure(result.error)) {
                        // Retry with checkpoint
                        Result.retry()
                    } else {
                        endRun(sourceId, sourcePath, terminalMessage(result.error))
                    }
                }
            }
        } catch (e: BackupBlocked) {
            // Terminal on purpose, and specifically NOT a retry. Backups here are interactive and
            // awaited (runInteractiveBackup blocks until the work reaches a finished state), so a
            // pending retry leaves the user's task spinning for the whole backoff schedule with
            // nothing on screen to explain it; and the unique work is enqueued with
            // ExistingWorkPolicy.KEEP, so that same pending retry silently swallows the user's next
            // "Back up now" — the very action the notification is asking them to take.
            android.util.Log.w(TAG, "backup of $sourcePath is blocked: ${e.message}", e.cause)
            if (!isStopped) {
                // A cancel racing the foreground promotion surfaces here as a failed setForeground.
                // Whoever cancelled does not need to be told Android refused them something.
                endRun(sourceId, sourcePath, e.message ?: FOREGROUND_DENIED_MESSAGE)
            } else {
                // A cancel racing the foreground promotion surfaces here too. Recording it would
                // leave the user staring at a failure they caused on purpose.
                Result.failure(workDataOf(KEY_ERROR to e.message))
            }
        } catch (e: CancellationException) {
            // The session has already checkpointed (its saves are NonCancellable), so the run is
            // resumable either way. What differs is whether the user is owed an explanation: they
            // asked for a cancel, or a constraint went away and WorkManager will re-run this itself,
            // or something only they can lift stopped it.
            reportCancellation(sourceId, sourcePath)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "backup of $sourcePath threw", e)
            if (runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                endRun(sourceId, sourcePath, e.message ?: "Unknown error")
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Error) {
            // The setup path -- everything before BackupSession.run, which catches Throwable itself.
            // Uncaught, this escapes doWork and WorkManager fails the work with nothing recorded
            // anywhere, which is the silence task-39 exists to end. Terminal for the same reason as
            // [isTerminalFailure]: no retry.
            android.util.Log.e(TAG, "backup of $sourcePath threw", e)
            endRun(sourceId, sourcePath, terminalMessage(e))
        }
    }

    /**
     * Whether re-running this backup could plausibly end differently.
     *
     * A JVM [Error] cannot: the retry does the same work against the same per-app heap ceiling, and
     * if WorkManager re-runs it in a fresh process instead, nothing there has re-opened the
     * repository — that needs the password, which the app deliberately does not keep — so the
     * provider answers null and the retry fails at [NEEDS_REPOSITORY_MESSAGE] without reaching the
     * backup at all. Measured on a
     * device before task-59 was fixed — an OutOfMemoryError was answered with RETRY, so the whole
     * backup ran again and died the same way. The checkpoint does not rescue it either: what a run
     * actually resumes from is the partial tree in the repository, and a run that died before
     * writing one has nothing to resume.
     *
     * A [SourceUnavailableException] cannot either, and for a plainer reason: the folder is gone or
     * the grant to read it is. A [RepositoryUnavailableException] is the same shape seen from the
     * other end -- the DESTINATION is gone, moved, replaced, or unmounted (task-65) -- and takes the
     * same answer for the same reason: what has to change is outside the backup, and retrying three
     * times over an exponential backoff meanwhile only spins the user's awaited task while
     * `ExistingWorkPolicy.KEEP` swallows every "Back Up Now" they tap. Nor can wrong credentials or
     * an untrusted SFTP host key — and those
     * two are not a judgement made here: `RetryingBlobStorage.isRetryable` already refuses to retry
     * them, so retrying the whole backup around them only repeats the refusal more expensively.
     * Everything else keeps the retry it had. A dropped connection or a busy storage backend is exactly what
     * the backoff schedule is for, and guessing which other Exceptions are permanent is a taxonomy
     * nothing here can justify.
     */
    private fun isTerminalFailure(error: Throwable): Boolean = error is Error ||
        error is SourceUnavailableException ||
        error is RepositoryUnavailableException ||
        error is InvalidCredentialsException ||
        error is HostKeyNotTrustedException

    /**
     * What to tell the user about a failure that ends the backup.
     *
     * An Error's own message is an allocator's ("Failed to allocate a 26497240 byte allocation with
     * 25100288 free bytes"), and since task-39 this string is persisted on the source and rendered
     * on the dashboard — so for those it has to be written for a person instead.
     */
    private fun terminalMessage(error: Throwable): String = when (error) {
        is OutOfMemoryError ->
            "Ran out of memory during this backup. " +
                "Try backing up a smaller folder, or run it again."
        is Error ->
            "This backup could not run on this device (${error::class.java.simpleName}). Please report it."
        else -> error.message ?: "Unknown error"
    }

    /**
     * Ends a cancelled run, saying something only if the user is the one who has to act.
     *
     * Reached from two places on purpose. A stop from WorkManager cancels this coroutine, and
     * `BackupSession.run`'s `coroutineScope` cannot complete normally once its job is cancelled, so
     * it re-throws and lands in the outer catch. A **cooperative** cancel — the Cancel action on the
     * notification — leaves the coroutine alive and returns a `Cancelled` result instead. Routing
     * both here means the answer does not depend on which of those happened, only on the stop
     * reason, which is what actually decides whether there is anything to say.
     */
    private fun reportCancellation(sourceId: String, sourcePath: String): Result {
        val message = cancellationMessage(stopReason)
        if (message != null) {
            showErrorNotification(sourceId, sourcePath, message)
            // A message here means the stop is one only the user can lift -- the six-hour foreground
            // cap or a background restriction. The latter blocks every future run too and may never
            // produce another attempt to report it, so if this is not recorded now there may never
            // be anything to record. A plain cancel (message null) is not remembered: the user asked
            // for it.
            return recordTerminal(sourceId, message)
        }
        return Result.failure(workDataOf(KEY_ERROR to "Backup cancelled"))
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
        // Terminal, not retryable. WorkManager re-runs a retried worker in a FRESH process, and
        // nothing there has reconnected the repository — opening it needs the password, which the
        // app deliberately does not keep. So every retry meets the same wall, and the old behaviour
        // spent the entire backoff schedule proving it before failing with "Repository not
        // configured. Call BackupWorker.setRepositoryProvider first" — a message about an internal
        // API, shown to someone whose backup has been silently stalled for minutes.
        val repository = getRepository() ?: throw BackupBlocked(NEEDS_REPOSITORY_MESSAGE)

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
            // Backups, retention and maintenance take turns: they share one repository writer, and
            // one operation's flush() would commit another's half-written state. See
            // RepositoryWriteLock.
            return RepositoryWriteLock.withLock { session.run(existingCheckpoint) }
        } finally {
            BackupSessionRegistry.unregister(sourceId, session)
        }
    }

    /**
     * Notices that this run is going without the foreground service it asked for, and makes it
     * cheaper to lose (task-60).
     *
     * Deliberately not a failure. WorkManager's own service swallows the promotion-stage refusal, so
     * this is the only way to find out at all — and by the time we know, the run is often most of
     * the way through. Stopping it would abandon backups that mostly do finish, while the kill this
     * guards against already ends in a recorded, correctly-worded failure: a killed run's re-run
     * starts backgrounded, so its `setForeground` throws for real and the user is told to open the
     * app and start it again. What is left worth doing is bounding what a kill costs, which is
     * everything since the last checkpoint.
     */
    private fun noticeLostForegroundProtection(watch: ForegroundProtectionWatch, sourcePath: String) {
        if (!watch.observe(ForegroundProtectionWatch.currentImportance())) return
        val session = inputData.getString(KEY_SOURCE_ID)?.let { BackupSessionRegistry.forSource(it) }
            // Not registered yet -- this loop starts before the session does. The watch keeps
            // saying so until it is, rather than losing the report for the rest of the run.
            ?: return
        session.reportForegroundProtectionLost()
        watch.markDelivered()
        android.util.Log.w(
            TAG,
            "backup of $sourcePath is running without a foreground service; checkpointing more often",
        )
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
        val protection = ForegroundProtectionWatch()
        while (true) {
            delay(PROGRESS_UPDATE_INTERVAL_MILLIS)
            noticeLostForegroundProtection(protection, sourcePath)
            val counters = latestCounters.get() ?: continue
            try {
                // Same cadence as the notification. WorkManager progress is how the counters leave
                // this process's worker and reach whoever is watching the work -- without it the
                // Tasks screen has a task with no numbers in it, which is what it had.
                setProgress(counters.toProgressData())
                setForeground(buildProgressForegroundInfo(notificationId, sourcePath, counters))
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Throwable) {
                // Best-effort progress update; keep backing up. Throwable rather than Exception
                // because this is a CHILD coroutine: an Error escaping here fails the enclosing
                // withContext, whose thrown result bypasses every catch in doWork -- so the run
                // would end with nothing recorded anywhere, which is the one silence the terminal
                // handling below cannot cover.
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

    private fun showErrorNotification(
        sourceId: String,
        sourcePath: String,
        errorMessage: String,
        failed: Boolean = true,
    ) {
        val notification = notificationManager.buildErrorNotification(
            sourcePath = sourcePath,
            errorMessage = errorMessage,
            failed = failed,
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
        private const val TAG = "BackupWorker"

        const val KEY_ERROR_COUNT = "error_count"

        const val KEY_SOURCE_ID = "source_id"
        const val KEY_SOURCE_PATH = "source_path"
        const val KEY_CONFIG = "config"
        const val KEY_NOTIFICATION_ICON = "notification_icon"
        const val KEY_ERROR = "error"
        const val KEY_MANIFEST_ID = "manifest_id"
        const val KEY_FILES_COUNT = "files_count"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_DURATION_MILLIS = "duration_millis"

        // Progress keys. Separate from the terminal output keys above: these are republished every
        // second while the run is alive, and are absent from the final Data.
        const val KEY_PROGRESS_CACHED_BYTES = "p_cached_bytes"
        const val KEY_PROGRESS_HASHED_BYTES = "p_hashed_bytes"
        const val KEY_PROGRESS_UPLOADED_BYTES = "p_uploaded_bytes"
        const val KEY_PROGRESS_ESTIMATED_BYTES = "p_estimated_bytes"
        const val KEY_PROGRESS_CACHED_FILES = "p_cached_files"
        const val KEY_PROGRESS_HASHED_FILES = "p_hashed_files"
        const val KEY_PROGRESS_EXCLUDED_FILES = "p_excluded_files"
        const val KEY_PROGRESS_EXCLUDED_DIRS = "p_excluded_dirs"
        const val KEY_PROGRESS_FATAL_ERRORS = "p_fatal_errors"
        const val KEY_PROGRESS_ESTIMATED_FILES = "p_estimated_files"
        const val KEY_PROGRESS_CURRENT_DIR = "p_current_dir"

        const val ACTION_CANCEL_BACKUP = "org.kopiaKt.android.CANCEL_BACKUP"

        private const val MAX_RETRY_COUNT = 3
        private const val UNIQUE_WORK_PREFIX = "backup_"
        private const val BACKOFF_DELAY_MINUTES = 5L

        // Every one of these ends in "start it again", and none of them says the app will do it for
        // you. That is deliberate and it is the honest wording: a terminal failure ends the work
        // item, and nothing re-enqueues it when the user reconnects or opens the app. What IS true,
        // since task-30.16, is that starting it again resumes from the last checkpoint rather than
        // beginning the whole backup over -- which is the only reason asking the user to do it is a
        // reasonable thing to ask.

        /** Shown when a retried worker wakes in a fresh process with no repository open. */
        const val NEEDS_REPOSITORY_MESSAGE =
            "Connect to your repository and start this backup again — it carries on from where it stopped"

        /** Shown when the system refuses to let the backup's foreground service start. */
        const val FOREGROUND_DENIED_MESSAGE =
            "Android would not let this backup run in the background — open KopiaKt and start it again"

        /** Shown when Android 15's six-hour foreground-service cap stops a run. */
        const val FOREGROUND_TIMEOUT_MESSAGE =
            "Backup paused after 6 hours — start it again to carry on from where it stopped"

        /** Shown when the app is under the system's background restriction. */
        const val BACKGROUND_RESTRICTED_MESSAGE =
            "Battery settings are blocking backups — allow KopiaKt to run in the background, then start it again"

        /**
         * What to tell the user about a stopped run, or null to say nothing.
         *
         * The distinction that matters is **whether anyone but the user can get it going again**.
         * WorkManager re-runs work stopped by a lost constraint, a quota, app standby or device
         * state entirely on its own, and a cancel was the user's own doing — a notification in any
         * of those cases is the app either duplicating itself or arguing with them.
         *
         * The two that need saying are the ones only the user can lift. Android 15's six-hour
         * `dataSync` cap: no further `dataSync` service may start in the same 24-hour window until
         * the app is foregrounded. And the system's background restriction, which is worse, because
         * it blocks every future run too and is exactly what aggressive OEM battery managers apply.
         */
        fun cancellationMessage(stopReason: Int): String? = when (stopReason) {
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> FOREGROUND_TIMEOUT_MESSAGE
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> BACKGROUND_RESTRICTED_MESSAGE
            else -> null
        }

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

    /**
     * Number of files uploaded at once, capped at **4** — the KDoc said "1-8" for a long time and the
     * code has always said `coerceIn(1, 4)`.
     *
     * The cap is not arbitrary and raising it is not free. On a Nothing Phone (2) the pipeline was
     * measured sitting at a flat 4.0 cores busy for a whole 1.4 GB backup, so these permits are the
     * binding constraint (task-66) — but that SoC is 1×X2 + 3×A710 + 4×A510, so the cores a higher
     * cap would reach are the little ones, worth perhaps +25–30 % rather than 2×, against more
     * in-flight buffers on a heap that ART clamps to 256 MB and that has already shipped one OOM
     * (task-59). Measure before changing it.
     */
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
