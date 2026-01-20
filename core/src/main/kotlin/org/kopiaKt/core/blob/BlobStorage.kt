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
    val timestamp: Instant
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
    val retentionPeriod: Duration = Duration.ZERO
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
    val retentionPeriod: Duration = Duration.ZERO
)

/**
 * Retention modes for blob storage backends that support object locking.
 */
enum class RetentionMode {
    NONE,
    GOVERNANCE,
    COMPLIANCE,
    /** Locked policy mode (Azure-specific) */
    LOCKED
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
    val freeBytes: Long
)

/**
 * Information about the storage connection.
 */
data class ConnectionInfo(
    val type: String,
    val config: Map<String, String>
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
     */
    suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions = PutBlobOptions())

    /**
     * Deletes a blob from storage.
     *
     * @param blobId The ID of the blob to delete
     */
    suspend fun deleteBlob(blobId: BlobId)

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
 * Exception thrown when a blob is not found.
 */
class BlobNotFoundException(blobId: BlobId) : Exception("Blob not found: $blobId")
