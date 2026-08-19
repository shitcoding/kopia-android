package org.kopiaKt.core.blob

import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

/**
 * Metadata about a blob stored in a storage backend.
 */
data class BlobMetadata(
    val blobId: BlobId,
    val length: Long,
    val timestamp: Instant,
)

/**
 * Options for putting a blob to storage.
 */
data class PutBlobOptions(
    /**
     * If true, don't overwrite existing blobs.
     * PutBlob will return without error if blob already exists.
     */
    val dontOverwrite: Boolean = false,

    /**
     * If set, use this timestamp for the blob instead of server-assigned time.
     * Only supported by some backends; others will ignore this.
     */
    val setModTime: Instant? = null,

    /**
     * If non-null, will be populated with the actual modification time after put.
     */
    val getModTime: Instant? = null,

    /**
     * Retention mode for the blob (for supported backends).
     */
    val retentionMode: RetentionMode = RetentionMode.NONE,

    /**
     * Retention period for the blob (for supported backends).
     */
    val retentionPeriod: Duration = Duration.ZERO,
)

/**
 * Options for extending blob retention.
 */
data class ExtendBlobRetentionOptions(
    /**
     * Retention mode for the blob.
     */
    val retentionMode: RetentionMode = RetentionMode.NONE,

    /**
     * New retention period for the blob.
     */
    val retentionPeriod: Duration = Duration.ZERO,
)

/**
 * Retention modes for blob storage backends that support object locking.
 */
enum class RetentionMode {
    NONE,
    GOVERNANCE,
    COMPLIANCE,

    /** Locked policy mode (Azure-specific) */
    LOCKED,
}

/**
 * Information about storage capacity.
 */
data class Capacity(
    /**
     * Total size of the volume in bytes.
     */
    val sizeBytes: Long,

    /**
     * Available (writable) space in bytes.
     */
    val freeBytes: Long,
)

/**
 * Information about the storage connection.
 */
data class ConnectionInfo(
    val type: String,
    val config: Map<String, String>,
)

/**
 * Read-only interface for blob storage.
 */
interface BlobReader {
    /**
     * Gets a blob by its ID.
     *
     * @param blobId The ID of the blob to retrieve
     * @param offset Starting byte offset (0-indexed)
     * @param length Number of bytes to read (-1 for all remaining bytes)
     * @return The blob data
     * @throws BlobNotFoundException if the blob does not exist
     * @throws InvalidBlobRangeException if offset/length is out of bounds
     */
    suspend fun getBlob(blobId: BlobId, offset: Long = 0, length: Long = -1): ByteArray

    /**
     * Gets metadata for a blob.
     *
     * @param blobId The ID of the blob
     * @return Metadata or null if blob doesn't exist
     */
    suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata?

    /**
     * Lists blobs with the given prefix.
     *
     * @param prefix The prefix to filter by
     * @return Flow of blob metadata
     */
    suspend fun listBlobs(prefix: String): Flow<BlobMetadata>

    /**
     * Gets connection information about the storage backend.
     */
    fun connectionInfo(): ConnectionInfo

    /**
     * Gets a human-readable display name for this storage.
     */
    fun displayName(): String
}

/**
 * Interface for storage backends that support volume capacity queries.
 */
interface BlobVolume {
    /**
     * Gets the capacity of this storage volume.
     *
     * @return Capacity information
     * @throws UnsupportedOperationException if this backend doesn't support capacity queries
     */
    suspend fun getCapacity(): Capacity
}

/**
 * Full interface for blob storage with read and write operations.
 */
interface BlobStorage : BlobReader {
    /**
     * Puts a blob to storage.
     *
     * @param blobId The ID for the blob
     * @param data The blob data
     * @param options Options for the put operation
     * @throws BlobAlreadyExistsException if blob exists and options.dontOverwrite is true
     *         (only for implementations that support this check)
     */
    suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions = PutBlobOptions())

    /**
     * Deletes a blob from storage.
     * Does not throw if the blob doesn't exist.
     *
     * @param blobId The ID of the blob to delete
     */
    suspend fun deleteBlob(blobId: BlobId)

    /**
     * Extends the retention period for a blob.
     * Only supported by backends with object locking capability.
     *
     * @param blobId The ID of the blob
     * @param options Options for the retention extension
     * @throws UnsupportedOperationException if this backend doesn't support object locking
     */
    suspend fun extendBlobRetention(blobId: BlobId, options: ExtendBlobRetentionOptions): Unit = throw UnsupportedOperationException("Object locking not supported by this backend")

    /**
     * Flushes any local caches associated with this storage.
     * Call this to ensure all data has been persisted.
     */
    suspend fun flushCaches() {
        // Default implementation does nothing
    }

    /**
     * Closes this storage and releases any associated resources.
     * After calling this method, the storage should not be used.
     */
    suspend fun close() {
        // Default implementation does nothing
    }

    /**
     * Returns whether this storage is in read-only mode.
     * When in read-only mode, all mutation operations (put, delete) will fail.
     */
    fun isReadOnly(): Boolean = false
}

/**
 * Exception thrown when a blob is not found.
 */
class BlobNotFoundException(
    blobId: BlobId,
    cause: Throwable? = null,
) : Exception("Blob not found: $blobId", cause)

/**
 * Exception thrown when a blob already exists (for dontOverwrite option).
 */
class BlobAlreadyExistsException(blobId: BlobId) : Exception("Blob already exists: $blobId")

/**
 * The storage this session is connected to no longer holds the repository it was opened on.
 *
 * The repository directory was deleted, moved, or **replaced** — a sync client swapping it, a file
 * manager, a volume remounted, the user tidying up — while the app held the repository open. This
 * app keeps one connection for a whole session and reads the format blob only at connect, so
 * without this nothing notices: measured on a phone (task-65), a run wrote 2.34 GB into a directory
 * that had been recreated by `mkdir -p` on the write path, reported "Backed up 200 files (2.34 GB)",
 * and Go then answered "repository not initialized in the provided storage".
 *
 * A distinct type because it decides something. It is **terminal** for a backup
 * (`BackupWorker.isTerminalFailure`), for the same reason
 * `org.kopiaKt.android.worker.SourceUnavailableException` is: what has to change is outside the
 * backup — the user restores the folder, remounts the volume, or reconnects — and retrying three
 * times over an exponential backoff meanwhile only spins their awaited task while
 * `ExistingWorkPolicy.KEEP` swallows every "Back Up Now" they tap. A terminal failure with a message
 * written for them leaves them free to act and tap again immediately (task-59's recorded recipe for
 * extending that list: classify at the source, throw a typed error, write the message for a person).
 *
 * Being typed also keeps it out of the retry machinery it would otherwise be ground through: a plain
 * `IOException` is read by `SftpBlobStorage.isSftpConnectionError` as a dropped connection (forcing a
 * pointless SSH reconnect and replay) and by `RetryingBlobStorage.isRetryable` as worth ten attempts
 * with backoff — per blob write.
 *
 * @param message written for the user; it is persisted on the source and rendered on the dashboard.
 */
class RepositoryUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when an invalid blob range is requested.
 */
class InvalidBlobRangeException(message: String) : Exception(message)

/**
 * Exception thrown when storage credentials are invalid or expired.
 */
class InvalidCredentialsException(message: String = "The provided token has expired") : Exception(message)

/**
 * Thrown when a server's host key is not trusted (e.g. SFTP with no known_hosts entry or matching
 * pinned fingerprint). Distinct from [InvalidCredentialsException] so callers/UI don't mistake a
 * potential MITM for a wrong password. Never retryable.
 */
class HostKeyNotTrustedException(message: String) : Exception(message)

/**
 * Exception thrown when a put option is not supported by the backend.
 */
class UnsupportedPutOptionException(option: String) : Exception("Unsupported put option: $option")
