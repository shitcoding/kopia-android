package org.kopiaKt.android.worker

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.maintenance.MaintenanceRunner
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.policy.PolicyManager
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadCounters
import org.kopiaKt.snapshot.upload.UploadOptions
import org.kopiaKt.snapshot.upload.UploadResult
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "BackupSession"

/**
 * Configuration for a backup session.
 */
data class BackupSessionConfig(
    /** The source path to back up. */
    val sourcePath: String,

    /** Unique identifier for this backup source. */
    val sourceId: String,

    /** Description for the snapshot. */
    val description: String = "",

    /** Tags to attach to the snapshot. */
    val tags: Map<String, String> = emptyMap(),

    /** Number of parallel uploads. */
    val parallelUploads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),

    /** Percentage of files to force re-hash (0-100). */
    val forceHashPercentage: Int = 0,

    /**
     * Policy to apply, or null to resolve the source's effective policy from the repository — the
     * normal case. Nothing used to resolve it, so every ignore rule and compression setting a user
     * configured was silently inert and the files they excluded were backed up anyway.
     */
    val policy: Policy? = null,

    /** Checkpoint options. */
    val checkpointOptions: CheckpointOptions = CheckpointOptions(),
)

/**
 * Result of a backup session.
 */
sealed class BackupSessionResult {
    /**
     * The snapshot was saved. [fatalErrorCount] is non-zero when entries were recorded as failed:
     * Go's record-and-continue still produces a complete, usable snapshot, but the run is
     * "completed with N errors" rather than a plain success — and specifically not a WorkManager
     * failure, which would retry a snapshot that is already saved and valid.
     */
    data class Success(
        val manifestId: ManifestId,
        val manifest: SnapshotManifest,
        val counters: UploadCounters,
        val durationMillis: Long,
        val fatalErrorCount: Int = 0,
    ) : BackupSessionResult() {
        val completedWithErrors: Boolean get() = fatalErrorCount > 0
    }

    /** Backup was cancelled. */
    data class Cancelled(
        val counters: UploadCounters,
        val checkpointSaved: Boolean,
    ) : BackupSessionResult()

    /** Backup failed with an error. */
    data class Failed(
        val error: Throwable,
        val counters: UploadCounters,
        val checkpointSaved: Boolean,
    ) : BackupSessionResult()
}

/**
 * Callback interface for backup session progress.
 */
interface BackupSessionCallback {
    /** Called when progress is updated. */
    fun onProgress(counters: UploadCounters)

    /** Called when a checkpoint is saved. */
    fun onCheckpointSaved(checkpoint: BackupCheckpoint)

    /** Called when the session completes. */
    fun onComplete(result: BackupSessionResult)
}

/**
 * Null implementation of BackupSessionCallback.
 */
open class NullBackupSessionCallback : BackupSessionCallback {
    override fun onProgress(counters: UploadCounters) {}
    override fun onCheckpointSaved(checkpoint: BackupCheckpoint) {}
    override fun onComplete(result: BackupSessionResult) {}
}

/**
 * Manages a single backup operation with progress tracking and checkpoint support.
 *
 * Handles:
 * - Directory scanning and upload via SnapshotUploader
 * - Progress reporting
 * - Periodic checkpoint creation for process death recovery
 * - Cancellation support
 *
 * @param repository The repository to back up to
 * @param config Backup session configuration
 * @param checkpointStore Store for saving checkpoints
 * @param callback Callback for progress and completion events
 */
class BackupSession(
    private val repository: DirectRepository,
    private val config: BackupSessionConfig,
    private val checkpointStore: CheckpointStore,
    private val callback: BackupSessionCallback = NullBackupSessionCallback(),
    private val context: Context? = null,
) {
    private val cancelled = AtomicBoolean(false)
    private val uploaderRef = AtomicReference<SnapshotUploader?>(null)
    private val currentCounters = AtomicReference(UploadCounters())
    private val startTime = AtomicReference<Instant?>(null)

    /**
     * Cancels the backup operation.
     *
     * This is a cooperative cancellation - the backup will stop at the next opportunity
     * and save a checkpoint for later resumption.
     */
    fun cancel() {
        cancelled.set(true)
        uploaderRef.get()?.cancel()
    }

    /**
     * Returns true if the backup has been cancelled.
     */
    fun isCancelled(): Boolean = cancelled.get()

    /**
     * Returns the current progress counters.
     */
    fun currentProgress(): UploadCounters = currentCounters.get()

    /**
     * Runs the backup session.
     *
     * @param existingCheckpoint Optional checkpoint to resume from
     * @return The result of the backup operation
     */
    suspend fun run(existingCheckpoint: BackupCheckpoint? = null): BackupSessionResult = coroutineScope {
        val startTimeValue = Instant.now()
        startTime.set(startTimeValue)

        // Create progress tracker
        val progress = SessionUploadProgress { counters ->
            currentCounters.set(counters)
            callback.onProgress(counters)
        }

        // The snapshot's identity IS the source's id -- the same string the source's policy is
        // stored under. Snapshotting as anything else (this used to be android@Build.DEVICE) means
        // the effective policy resolved at backup time is never the one the wizard saved.
        val sourceInfo = sourceIdentity()

        // Create repository connection JSON for checkpoint (simplified - just store path info).
        // Built by the serializer, not string interpolation: a source path containing a quote or a
        // backslash would otherwise write invalid JSON into the checkpoint.
        val repoConnectionJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.JsonObject(
                mapOf("path" to kotlinx.serialization.json.JsonPrimitive(config.sourcePath)),
            ),
        )

        var checkpointJob: Job? = null
        var result: BackupSessionResult

        // Create writer for the session (inside try to handle connection failures)
        val writer: RepositoryWriter
        try {
            writer = repository.newWriter(WriteSessionOptions())
        } catch (e: Throwable) {
            val counters = currentCounters.get()
            val checkpoint = BackupCheckpoint(
                sourceId = config.sourceId,
                sourcePath = config.sourcePath,
                repositoryConnectionJson = repoConnectionJson,
                startTime = startTimeValue.toEpochMilli(),
                lastError = e.message,
            )
            val checkpointSaved = persistCheckpoint(checkpoint)
            result = BackupSessionResult.Failed(e, counters, checkpointSaved)
            callback.onComplete(result)
            return@coroutineScope result
        }

        try {
            // Resolved, not defaulted, and deliberately not swallowed: backing up files the user
            // excluded because their policy could not be read is worse than not backing up. Inside
            // the try so a failure travels the session's normal Failed/callback path.
            val effectivePolicy = config.policy
                ?: PolicyManager.getEffectivePolicy(repository, sourceInfo)

            // Create uploader
            val uploader = SnapshotUploader(
                writer = writer,
                source = sourceInfo,
                policy = effectivePolicy,
                progress = progress,
            )
            uploaderRef.set(uploader)
            // Replay a cancel that arrived during setup (between register and here -- e.g. while
            // repository.newWriter opened a slow SAF/network connection), when cancel() reached only a null
            // uploaderRef. SnapshotUploader.cancel() remembers the request (sticky) and applies it once the
            // walker exists, so this reliably stops the upload before the tree walk instead of after.
            if (cancelled.get()) uploader.cancel()

            // Start checkpoint job
            checkpointJob = launch {
                runCheckpointLoop(repoConnectionJson, sourceInfo)
            }

            // Create initial checkpoint
            val initialCheckpoint = BackupCheckpoint(
                sourceId = config.sourceId,
                sourcePath = config.sourcePath,
                repositoryConnectionJson = repoConnectionJson,
                startTime = startTimeValue.toEpochMilli(),
                resumeCount = (existingCheckpoint?.resumeCount ?: 0) + if (existingCheckpoint != null) 1 else 0,
            )
            persistCheckpoint(initialCheckpoint)

            // Open source directory
            val rootDir = openSourceDirectory()

            // Run the upload
            val uploadResult = uploader.upload(
                rootDir = rootDir,
                options = UploadOptions(
                    description = config.description,
                    tags = config.tags,
                    parallelUploads = config.parallelUploads,
                    forceHashPercentage = config.forceHashPercentage,
                    // The SAME interval that drives the local bookkeeping loop, not the uploader's
                    // Go-inherited 45 minutes. Go's default is sized for a desktop that will still
                    // be running in 45 minutes; on Android the process is killed on someone else's
                    // schedule, and everything uploaded since the last checkpoint is what a kill
                    // costs. One knob, so the number a user or a config sees is the one that
                    // decides how much work an abrupt stop throws away.
                    checkpointInterval = Duration.ofMillis(config.checkpointOptions.effectiveIntervalMillis),
                ),
            )

            // Stop the periodic checkpoint loop before deciding success/clear. Its saves are now
            // NonCancellable, so an in-flight one could otherwise land AFTER handleSuccess clears the
            // checkpoint -- leaving a stale checkpoint that triggers a spurious resume next run.
            checkpointJob?.cancelAndJoin()
            checkpointJob = null

            // Check for cancellation
            result = if (cancelled.get() || uploadResult.incomplete) {
                handleCancelledOrIncomplete(uploadResult, repoConnectionJson, sourceInfo)
            } else {
                handleSuccess(uploadResult, startTimeValue)
            }
        } catch (e: CancellationException) {
            // Save checkpoint and return cancelled result
            val counters = currentCounters.get()
            val checkpointSaved = saveCheckpoint(repoConnectionJson, sourceInfo)
            result = BackupSessionResult.Cancelled(counters, checkpointSaved)
        } catch (e: Throwable) {
            // Save checkpoint and return failed result
            val counters = currentCounters.get()
            val checkpoint = BackupCheckpoint(
                sourceId = config.sourceId,
                sourcePath = config.sourcePath,
                repositoryConnectionJson = repoConnectionJson,
                processedFiles = counters.totalCachedFiles + counters.totalHashedFiles,
                processedBytes = counters.totalCachedBytes + counters.totalHashedBytes,
                startTime = startTime.get()?.toEpochMilli() ?: System.currentTimeMillis(),
                lastError = e.message,
            )
            val checkpointSaved = persistCheckpoint(checkpoint)
            result = BackupSessionResult.Failed(e, counters, checkpointSaved)
        } finally {
            checkpointJob?.cancel()
            uploaderRef.set(null)
            // Retention runs on EVERY path out of the upload, which is why it lives here rather
            // than on the success branch. Go applies it after every checkpoint as well as every
            // snapshot; doing that mid-upload here would mean a repository refresh and a second
            // writer session inside a live backup, so it is done once, at the end, for the one
            // thing that actually needs bounding — the incomplete manifests a run leaves behind.
            // A run that fails or is cancelled leaves the most of them and used to leave them
            // forever: it never reached retention at all, and the next attempt only ran retention
            // if IT succeeded. NonCancellable for the same reason persistCheckpoint is: on the
            // cancellation path this would otherwise suspend inside an already-cancelled coroutine
            // and never run.
            withContext(NonCancellable) { applyRetention(sourceInfo) }
            // Close writer to release resources
            try {
                writer.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }

        callback.onComplete(result)
        result
    }

    /**
     * Applies the source's retention policy, as Go does after every `snapshot create`.
     *
     * Failure ordering matters: the snapshot is already saved and valid, so a retention failure must
     * not undo it or fail the run. It is only reported. The local checkpoint is cleared afterwards
     * by [handleSuccess], so an interrupted retention leaves at worst an over-full snapshot list —
     * never a lost snapshot.
     *
     * The incomplete rules are what keep a resume possible: the newest incomplete manifests survive
     * (Go keeps at least three, plus anything under four hours old) and only the checkpoints of a
     * run that has since finished are reaped.
     */
    private suspend fun applyRetention(sourceInfo: SourceInfo) {
        try {
            MaintenanceRunner(repository).applyRetention(sourceInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "retention failed after a successful snapshot", e)
        }
    }

    /**
     * The identity to snapshot under. `config.sourceId` already carries `user@host:path` (it is the
     * key the source and its policy live under), so it is the authority; the persisted device
     * identity is the fallback for a session built with a bare id, and the path always comes from
     * `config.sourcePath` so the two can never disagree about what was backed up.
     */
    private fun sourceIdentity(): SourceInfo {
        val ctx = context
        return backupSourceIdentity(ctx, config.sourceId, config.sourcePath)
    }

    private fun openSourceDirectory(): Directory = openBackupSource(context, config.sourcePath)

    private suspend fun runCheckpointLoop(repoConnectionJson: String, sourceInfo: SourceInfo) {
        var lastCheckpointBytes = 0L

        while (true) {
            // Clamped interval: a zero/negative config must not turn this into a tight busy-loop (task-14).
            delay(config.checkpointOptions.effectiveIntervalMillis)

            if (cancelled.get()) break

            val counters = currentCounters.get()
            val totalBytes = counters.totalCachedBytes + counters.totalHashedBytes

            // Only checkpoint if we've made meaningful progress
            if (totalBytes - lastCheckpointBytes >= config.checkpointOptions.minBytesBeforeCheckpoint) {
                saveCheckpoint(repoConnectionJson, sourceInfo)
                lastCheckpointBytes = totalBytes
            }
        }
    }

    private suspend fun saveCheckpoint(repoConnectionJson: String, sourceInfo: SourceInfo): Boolean {
        val counters = currentCounters.get()
        val checkpoint = BackupCheckpoint(
            sourceId = config.sourceId,
            sourcePath = config.sourcePath,
            repositoryConnectionJson = repoConnectionJson,
            processedFiles = counters.totalCachedFiles + counters.totalHashedFiles,
            processedBytes = counters.totalCachedBytes + counters.totalHashedBytes,
            startTime = startTime.get()?.toEpochMilli() ?: System.currentTimeMillis(),
        )
        val saved = persistCheckpoint(checkpoint)
        if (saved) callback.onCheckpointSaved(checkpoint)
        return saved
    }

    // NonCancellable single choke point for EVERY checkpoint write. The checkpoint save on a cancellation
    // path (run's `catch (CancellationException)` / `catch (Throwable)`, and the writer-creation failure)
    // otherwise suspends inside an already-cancelled coroutine and throws immediately, the caller's catch
    // swallows it, and NO resumable checkpoint is written -- so a WorkManager-initiated cancel (constraint
    // loss, cancelAll, system, or a torn-stream IOException) would leave the backup un-checkpointed.
    // Running the periodic-loop / initial saves non-cancellably too just means an in-flight save completes
    // instead of being torn. (task-14)
    private suspend fun persistCheckpoint(checkpoint: BackupCheckpoint): Boolean = withContext(NonCancellable) {
        try {
            checkpointStore.saveCheckpoint(checkpoint)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun handleCancelledOrIncomplete(
        uploadResult: UploadResult,
        repoConnectionJson: String,
        sourceInfo: SourceInfo,
    ): BackupSessionResult {
        val counters = currentCounters.get()

        // Bookkeeping only: what the next run actually resumes from is the partial tree this run
        // left in the repository, which findPreviousManifests picks up on its own.
        val checkpoint = BackupCheckpoint(
            sourceId = config.sourceId,
            sourcePath = config.sourcePath,
            repositoryConnectionJson = repoConnectionJson,
            processedFiles = counters.totalCachedFiles + counters.totalHashedFiles,
            processedBytes = counters.totalCachedBytes + counters.totalHashedBytes,
            startTime = startTime.get()?.toEpochMilli() ?: System.currentTimeMillis(),
        )
        val checkpointSaved = persistCheckpoint(checkpoint)

        return if (cancelled.get()) {
            BackupSessionResult.Cancelled(counters, checkpointSaved)
        } else {
            BackupSessionResult.Failed(
                error = RuntimeException(uploadResult.incompleteReason ?: "Unknown error"),
                counters = counters,
                checkpointSaved = checkpointSaved,
            )
        }
    }

    private suspend fun handleSuccess(
        uploadResult: UploadResult,
        startTimeValue: Instant,
    ): BackupSessionResult {
        // Clear the checkpoint on success
        checkpointStore.clearCheckpoint(config.sourceId)

        val counters = currentCounters.get()
        val durationMillis = System.currentTimeMillis() - startTimeValue.toEpochMilli()

        return BackupSessionResult.Success(
            manifestId = uploadResult.manifestId,
            manifest = uploadResult.manifest,
            counters = counters,
            durationMillis = durationMillis,
            fatalErrorCount = uploadResult.stats.errorCount,
        )
    }

    /**
     * Internal progress tracker that delegates to a lambda.
     */
    private class SessionUploadProgress(
        private val onUpdate: (UploadCounters) -> Unit,
    ) : CountingUploadProgress() {

        override fun hashedBytes(numBytes: Long) {
            super.hashedBytes(numBytes)
            onUpdate(snapshot())
        }

        override fun uploadedBytes(numBytes: Long) {
            super.uploadedBytes(numBytes)
            onUpdate(snapshot())
        }

        override fun finishedFile(filename: String, error: Throwable?) {
            super.finishedFile(filename, error)
            onUpdate(snapshot())
        }

        override fun cachedFile(path: String, size: Long) {
            super.cachedFile(path, size)
            onUpdate(snapshot())
        }
    }
}
