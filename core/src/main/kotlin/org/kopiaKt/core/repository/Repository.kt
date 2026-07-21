package org.kopiaKt.core.repository

import org.kopiaKt.core.blob.BlobReader
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobVolume
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.manifest.EntryMetadata
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.`object`.ObjectReader
import org.kopiaKt.core.`object`.ObjectWriter
import org.kopiaKt.core.`object`.ObjectWriterOptions
import java.io.Closeable
import java.time.Instant

/**
 * Repository provides read access to a Kopia repository.
 *
 * This interface mirrors Go's repo.Repository interface, providing
 * operations to read objects, manifests, and content metadata.
 */
interface Repository : Closeable {
    /**
     * Opens an object for reading.
     *
     * @param objectId The object ID to open
     * @return An ObjectReader for the object
     * @throws ObjectNotFoundException if the object doesn't exist
     */
    fun openObject(objectId: ObjectId): ObjectReader

    /**
     * Reads an entire object into memory.
     *
     * For large objects, consider using [openObject] for streaming access.
     *
     * @param objectId The object ID to read
     * @return The complete object data
     * @throws ObjectNotFoundException if the object doesn't exist
     */
    suspend fun readObject(objectId: ObjectId): ByteArray

    /**
     * Verifies that all content backing an object exists in the repository.
     *
     * @param objectId The object to verify
     * @return List of content IDs that back this object
     * @throws ObjectNotFoundException if any backing content is missing
     */
    suspend fun verifyObject(objectId: ObjectId): List<ContentId>

    /**
     * Retrieves a manifest by ID.
     *
     * @param id The manifest ID
     * @param serializer The serializer for the payload type
     * @return Pair of (payload, metadata)
     * @throws ManifestNotFoundException if manifest doesn't exist
     */
    suspend fun <T> getManifest(id: ManifestId, serializer: kotlinx.serialization.KSerializer<T>): Pair<T, EntryMetadata>

    /**
     * Finds manifests matching all provided labels.
     *
     * @param labels Labels to match (all must match)
     * @return List of matching metadata, sorted by modification time
     */
    suspend fun findManifests(labels: Map<String, String>): List<EntryMetadata>

    /**
     * Gets the information about particular content.
     *
     * @param contentId The content ID to look up
     * @return The content info, or null if not found
     */
    suspend fun contentInfo(contentId: ContentId): ContentInfo?

    /**
     * Returns the current time as seen by the repository.
     */
    fun time(): Instant

    /**
     * Returns client options for this repository connection.
     */
    fun clientOptions(): ClientOptions

    /**
     * Creates a new RepositoryWriter session.
     *
     * @param options Write session options
     * @return A new RepositoryWriter
     */
    suspend fun newWriter(options: WriteSessionOptions = WriteSessionOptions()): RepositoryWriter

    /**
     * Updates the human-readable description of the repository.
     */
    fun updateDescription(description: String)

    /**
     * Refreshes the repository indexes to see external changes.
     */
    suspend fun refresh()

    /**
     * Closes the repository and releases resources.
     */
    override fun close()
}

/**
 * RepositoryWriter provides methods to write to a repository.
 *
 * Extends Repository with write operations.
 */
interface RepositoryWriter : Repository {
    /**
     * Creates a new ObjectWriter for writing objects.
     *
     * @param options Writer options (compression, prefix, etc.)
     * @return A new ObjectWriter instance
     */
    fun newObjectWriter(options: ObjectWriterOptions = ObjectWriterOptions()): ObjectWriter

    /**
     * Writes an object from a complete byte array.
     *
     * @param data The object data to write
     * @param options Writer options (compression, prefix, etc.)
     * @return The object ID
     */
    suspend fun writeObject(data: ByteArray, options: ObjectWriterOptions = ObjectWriterOptions()): ObjectId

    /**
     * Concatenates multiple objects into a single object.
     *
     * @param objectIds The objects to concatenate (in order)
     * @param options Concatenation options
     * @return The object ID of the concatenated object
     */
    suspend fun concatenateObjects(objectIds: List<ObjectId>, options: ConcatenateOptions = ConcatenateOptions()): ObjectId

    /**
     * Stores a manifest with the given labels.
     *
     * @param labels Labels for the manifest (must include "type")
     * @param payload The data to store
     * @param serializer The serializer for the payload type
     * @return The manifest ID
     */
    suspend fun <T> putManifest(labels: Map<String, String>, payload: T, serializer: kotlinx.serialization.KSerializer<T>): ManifestId

    /**
     * Saves the given manifest payload with a set of labels and replaces
     * any previous manifests with the same labels.
     *
     * @param labels Labels for the manifest
     * @param payload The data to store
     * @param serializer The serializer for the payload type
     * @return The manifest ID
     */
    suspend fun <T> replaceManifests(labels: Map<String, String>, payload: T, serializer: kotlinx.serialization.KSerializer<T>): ManifestId

    /**
     * Deletes the manifest with a given ID.
     *
     * @param id The manifest ID to delete
     */
    suspend fun deleteManifest(id: ManifestId)

    /**
     * Registers a callback to be invoked after flush succeeds.
     */
    fun onSuccessfulFlush(callback: suspend (RepositoryWriter) -> Unit)

    /**
     * Flushes all pending changes to storage.
     */
    suspend fun flush()
}

/**
 * DirectRepository provides additional low-level repository functionality.
 *
 * This interface is for advanced use cases requiring direct access to
 * underlying components.
 */
interface DirectRepository : Repository {
    /**
     * Returns the object format configuration.
     */
    fun objectFormat(): ObjectFormatInfo

    /**
     * Returns the blob reader for direct blob access.
     */
    fun blobReader(): BlobReader

    /**
     * Returns the blob volume interface for capacity queries.
     */
    fun blobVolume(): BlobVolume?

    /**
     * Returns the unique repository ID (32 bytes).
     */
    fun uniqueId(): ByteArray

    /**
     * Derives an encryption key from the master key.
     *
     * @param purpose The key purpose string
     * @param keyLength The desired key length in bytes
     * @return The derived key
     */
    fun deriveKey(purpose: String, keyLength: Int): ByteArray

    /**
     * Iterates the merge-resolved [ContentInfo] for every content id in the repository.
     *
     * Surfaces tombstones (deleted=true) only when [includeDeleted] is true. Used by snapshot GC
     * Phase 2 to enumerate all content and find what is unreferenced. Delegates to
     * `ContentManager.iterateContentInfos`.
     *
     * @param includeDeleted whether to yield tombstone entries as well as live ones
     * @param callback invoked once per content id with its current [ContentInfo]
     */
    suspend fun iterateContentInfos(
        includeDeleted: Boolean,
        callback: suspend (ContentInfo) -> Unit,
    )

    /**
     * Whether the most recent [refresh] loaded the repository's index AND manifest state completely,
     * i.e. no index blob or manifest content block was skipped as unreadable/malformed.
     *
     * A false result means the in-memory view is PARTIAL — content or entire snapshots may be hidden.
     * A destructive caller (snapshot GC content deletion) MUST refuse to act on a partial view: a hidden
     * snapshot would make its still-referenced content look unreferenced and be deleted. See task-9.
     */
    fun lastLoadWasComplete(): Boolean

    /**
     * Creates a new DirectRepositoryWriter session.
     *
     * @param options Write session options
     * @return A new DirectRepositoryWriter
     */
    suspend fun newDirectWriter(options: WriteSessionOptions = WriteSessionOptions()): DirectRepositoryWriter

    /**
     * Returns whether password change is supported.
     */
    fun supportsPasswordChange(): Boolean
}

/**
 * DirectRepositoryWriter provides low-level write access to the repository.
 */
interface DirectRepositoryWriter :
    RepositoryWriter,
    DirectRepository {
    /**
     * Returns the blob storage for direct access.
     */
    fun blobStorage(): BlobStorage

    /**
     * Soft-deletes [contentId] by writing a Go-compatible tombstone (deleted=true, strictly-increasing
     * timestamp). No-op if the content is unknown or already deleted. The bytes are not removed; the
     * tombstone supersedes the live entry once flushed. Reversible with [undeleteContent].
     *
     * Used by snapshot GC Phase 2 to reclaim unreferenced content. Delegates to
     * `ContentManager.deleteContent`. Must be flushed ([flush]) to persist.
     */
    suspend fun deleteContent(contentId: ContentId)

    /**
     * Revives a soft-deleted [contentId] by writing a live entry with a strictly-increasing timestamp.
     * No-op if the content is unknown or already live. Used by GC to recover content that was deleted
     * but turns out to still be referenced. Delegates to `ContentManager.undeleteContent`.
     */
    suspend fun undeleteContent(contentId: ContentId)
}

/**
 * Options for a write session.
 */
data class WriteSessionOptions(
    /** Human-readable purpose of this write session. */
    val purpose: String = "",

    /** Whether to flush changes even if the session fails. */
    val flushOnFailure: Boolean = false,

    /** Callback invoked after each upload completes. */
    val onUpload: ((Long) -> Unit)? = null,
)

/**
 * Options for concatenating objects.
 */
data class ConcatenateOptions(
    /** Compression algorithm for the concatenated index. */
    val compressor: String? = null,
)

/**
 * Client options for a repository connection.
 */
data class ClientOptions(
    /** Hostname of the client. */
    val hostname: String = "",

    /** Username of the client. */
    val username: String = "",

    /** Whether this is a read-only connection. */
    val readOnly: Boolean = false,

    /** Human-readable description of this repository connection. */
    var description: String = "",
) {
    /**
     * Returns the combined username@hostname string.
     */
    fun usernameAtHost(): String = if (hostname.isNotEmpty()) "$username@$hostname" else username

    companion object {
        /**
         * Creates ClientOptions with defaults filled in.
         */
        fun withDefaults(
            hostname: String = getDefaultHostname(),
            username: String = getDefaultUsername(),
            description: String = "",
        ): ClientOptions = ClientOptions(
            hostname = hostname,
            username = username,
            description = description,
        )

        private fun getDefaultHostname(): String = try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }

        private fun getDefaultUsername(): String = System.getProperty("user.name") ?: "unknown"
    }
}

/**
 * Information about the object format configuration.
 */
data class ObjectFormatInfo(
    /** The splitter algorithm name. */
    val splitter: String,
)
