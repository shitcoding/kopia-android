package org.kopiaKt.core.content

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.compression.CompressionAlgorithm

/**
 * Represents metadata about a content block stored in a pack blob.
 *
 * This corresponds to Go's `index.Info` struct and contains all information
 * needed to locate and decode content within a pack file.
 *
 * @property contentId The content ID (hash-based identifier)
 * @property packBlobId The ID of the pack blob containing this content
 * @property timestampSeconds Unix timestamp when the content was written
 * @property originalLength Size of uncompressed/unencrypted content in bytes
 * @property packedLength Size of encrypted/compressed content in bytes
 * @property packOffset Byte offset within the pack blob where this content starts
 * @property compressionHeaderId Compression algorithm header ID (0 = no compression)
 * @property deleted Whether this content is marked as deleted (tombstone)
 * @property formatVersion Repository format version at time of writing
 * @property encryptionKeyId Key ID used for encryption (0 = default key)
 */
data class ContentInfo(
    val contentId: ContentId,
    val packBlobId: BlobId,
    val timestampSeconds: Long,
    val originalLength: UInt,
    val packedLength: UInt,
    val packOffset: UInt,
    val compressionHeaderId: Int = 0,
    val deleted: Boolean = false,
    val formatVersion: Byte = 0,
    val encryptionKeyId: Byte = 0,
) {
    /**
     * Returns true if the content is compressed (non-zero compression header ID).
     */
    val isCompressed: Boolean
        get() = compressionHeaderId != 0

    /**
     * Returns the compression algorithm if compressed, null otherwise.
     */
    val compressionAlgorithm: CompressionAlgorithm?
        get() = if (compressionHeaderId != 0) {
            CompressionAlgorithm.fromHeaderId(compressionHeaderId)
        } else {
            null
        }

    companion object {
        /**
         * Invalid marker values used when data cannot be parsed.
         */
        const val INVALID_FORMAT_VERSION: Byte = 0xFF.toByte()
        const val INVALID_COMPRESSION_HEADER_ID: Int = 0xFFFF
        const val INVALID_ENCRYPTION_KEY_ID: Byte = 0xFF.toByte()
    }
}
