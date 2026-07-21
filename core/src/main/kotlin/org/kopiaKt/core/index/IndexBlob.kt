package org.kopiaKt.core.index

import org.kopiaKt.core.blob.BlobId
import java.security.SecureRandom

/**
 * Represents metadata about an index blob.
 *
 * Index blobs are encrypted pack indexes stored in blob storage with the 'n' prefix.
 * They contain content location information across multiple pack blobs.
 *
 * @property blobId The blob ID (e.g., "n1234567890abcdef...")
 * @property length The size of the blob in bytes
 * @property timestamp When the index blob was created (Unix seconds)
 */
data class IndexBlobInfo(
    val blobId: BlobId,
    val length: Long,
    val timestamp: Long,
)

/**
 * Constants for index blob handling.
 */
object IndexBlobConstants {
    /**
     * Random suffix size appended to index blobs for uniqueness.
     * This ensures that each index blob has a globally unique ID
     * even if the content would otherwise be identical.
     */
    const val RANDOM_SUFFIX_SIZE = 32

    /**
     * Maximum number of entries per index shard.
     * When an index exceeds this, it should be split into multiple shards.
     */
    const val DEFAULT_INDEX_SHARD_SIZE = 16_000_000

    /**
     * Prefix for index blobs.
     */
    const val INDEX_BLOB_PREFIX = "n"

    /**
     * Prefix for compaction log blobs (V0 manager).
     */
    const val COMPACTION_LOG_PREFIX = "m"

    /**
     * Prefix for cleanup log blobs (V0 manager).
     */
    const val CLEANUP_LOG_PREFIX = "l"
}

/**
 * Generates cryptographically secure random bytes.
 */
internal fun generateRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return bytes
}

/**
 * Generates a random suffix for index blob uniqueness.
 */
fun generateIndexBlobSuffix(): ByteArray = generateRandomBytes(IndexBlobConstants.RANDOM_SUFFIX_SIZE)

/**
 * Index version constants.
 */
object IndexVersion {
    const val V1 = 1
    const val V2 = 2
}

/**
 * ID range for iterating over content IDs.
 */
data class IdRange(
    val startId: org.kopiaKt.core.content.ContentId?,
    val endId: org.kopiaKt.core.content.ContentId?,
) {
    companion object {
        /**
         * Range that includes all content IDs.
         */
        val ALL = IdRange(null, null)
    }
}
