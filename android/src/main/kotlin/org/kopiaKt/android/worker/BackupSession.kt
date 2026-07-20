package org.kopiaKt.android.worker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kopiaKt.android.storage.SafFilesystem
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadCounters
import org.kopiaKt.snapshot.upload.UploadOptions
import org.kopiaKt.snapshot.upload.UploadResult
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    /** Backup policy to apply. */
    val policy: Policy = Policy(),

    /** Checkpoint options. */
    val checkpointOptions: CheckpointOptions = CheckpointOptions()
)

/**
 * Result of a backup session.
 */
sealed class BackupSessionResult {
    /** Backup completed successfully. */
    data class Success(
        val manifestId: ManifestId,
        val manifest: SnapshotManifest,
        val counters: UploadCounters,
        val durationMillis: Long
    ) : BackupSessionResult()

    /** Backup was cancelled. */
    data class Cancelled(
        val counters: UploadCounters,
        val checkpointSaved: Boolean
    ) : BackupSessionResult()

    /** Backup failed with an error. */
    data class Failed(
        val error: Throwable,
        val counters: UploadCounters,
        val checkpointSaved: Boolean
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
    private val context: Context? = null
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

        // Create source info
        val hostName = android.os.Build.DEVICE
        val userName = "android"
        val sourceInfo = SourceInfo(
            host = hostName,
            userName = userName,
            path = config.sourcePath
        )

        // Create repository connection JSON for checkpoint (simplified - just store path info)
        val repoConnectionJson = "{\"path\":\"${config.sourcePath}\"}"

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
                lastError = e.message
            )
            val checkpointSaved = try {
                checkpointStore.saveCheckpoint(checkpoint)
                true
            } catch (_: Exception) {
                false
            }
            result = BackupSessionResult.Failed(e, counters, checkpointSaved)
            callback.onComplete(result)
            return@coroutineScope result
        }

        try {
            // Create uploader
            val uploader = SnapshotUploader(
                writer = writer,
                source = sourceInfo,
                policy = config.policy,
                progress = progress
            )
            uploaderRef.set(uploader)

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
                resumeCount = (existingCheckpoint?.resumeCount ?: 0) + if (existingCheckpoint != null) 1 else 0
            )
            checkpointStore.saveCheckpoint(initialCheckpoint)

            // Open source directory
            val rootDir = openSourceDirectory()

            // Run the upload
            val uploadResult = uploader.upload(
                rootDir = rootDir,
                options = UploadOptions(
                    description = config.description,
                    tags = config.tags,
                    parallelUploads = config.parallelUploads,
                    forceHashPercentage = config.forceHashPercentage
                )
            )

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
                lastError = e.message
            )
            checkpointStore.saveCheckpoint(checkpoint)
            result = BackupSessionResult.Failed(e, counters, true)
        } finally {
            checkpointJob?.cancel()
            uploaderRef.set(null)
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

    private fun openSourceDirectory(): Directory {
        if (config.sourcePath.startsWith("content://")) {
            val ctx = context
                ?: throw IllegalStateException(
                    "Context is required for SAF URI backup. " +
                        "Pass context to BackupSession constructor."
                )
            val uri = Uri.parse(config.sourcePath)
            return SafFilesystem.directory(ctx, uri)
        }

        val sourceFile = File(config.sourcePath)
        if (!sourceFile.exists()) {
            throw IllegalArgumentException("Source path does not exist: ${config.sourcePath}")
        }
        if (!sourceFile.isDirectory) {
            throw IllegalArgumentException("Source path is not a directory: ${config.sourcePath}")
        }
        return LocalFilesystem.directory(sourceFile.toPath())
    }

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
        return try {
            val counters = currentCounters.get()
            val checkpoint = BackupCheckpoint(
                sourceId = config.sourceId,
                sourcePath = config.sourcePath,
                repositoryConnectionJson = repoConnectionJson,
                processedFiles = counters.totalCachedFiles + counters.totalHashedFiles,
                processedBytes = counters.totalCachedBytes + counters.totalHashedBytes,
                startTime = startTime.get()?.toEpochMilli() ?: System.currentTimeMillis()
            )
            checkpointStore.saveCheckpoint(checkpoint)
            callback.onCheckpointSaved(checkpoint)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun handleCancelledOrIncomplete(
        uploadResult: UploadResult,
        repoConnectionJson: String,
        sourceInfo: SourceInfo
    ): BackupSessionResult {
        val counters = currentCounters.get()

        // Save checkpoint with incomplete manifest info
        val checkpoint = BackupCheckpoint(
            sourceId = config.sourceId,
            sourcePath = config.sourcePath,
            repositoryConnectionJson = repoConnectionJson,
            incompleteManifestId = uploadResult.manifestId.value,
            processedFiles = counters.totalCachedFiles + counters.totalHashedFiles,
            processedBytes = counters.totalCachedBytes + counters.totalHashedBytes,
            startTime = startTime.get()?.toEpochMilli() ?: System.currentTimeMillis()
        )
        checkpointStore.saveCheckpoint(checkpoint)

        return if (cancelled.get()) {
            BackupSessionResult.Cancelled(counters, true)
        } else {
            BackupSessionResult.Failed(
                error = RuntimeException(uploadResult.incompleteReason ?: "Unknown error"),
                counters = counters,
                checkpointSaved = true
            )
        }
    }

    private suspend fun handleSuccess(
        uploadResult: UploadResult,
        startTimeValue: Instant
    ): BackupSessionResult {
        // Clear the checkpoint on success
        checkpointStore.clearCheckpoint(config.sourceId)

        val counters = currentCounters.get()
        val durationMillis = System.currentTimeMillis() - startTimeValue.toEpochMilli()

        return BackupSessionResult.Success(
            manifestId = uploadResult.manifestId,
            manifest = uploadResult.manifest,
            counters = counters,
            durationMillis = durationMillis
        )
    }

    /**
     * Internal progress tracker that delegates to a lambda.
     */
    private class SessionUploadProgress(
        private val onUpdate: (UploadCounters) -> Unit
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
