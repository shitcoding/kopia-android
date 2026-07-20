package org.kopiaKt.core.index

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.content.toHexString
import org.kopiaKt.core.encryption.Encryptor
import org.kopiaKt.core.pack.PackIndexV1
import org.kopiaKt.core.pack.PackIndexV2
import java.io.ByteArrayOutputStream

/**
 * Builder for creating index blobs.
 *
 * Index blobs contain pack index data (V1 or V2 format) with:
 * - Optional encryption
 * - Random suffix for uniqueness
 *
 * Usage:
 * ```
 * val builder = IndexBlobBuilder(version = IndexVersion.V2)
 * builder.add(contentInfo1)
 * builder.add(contentInfo2)
 * val indexData = builder.build(encryptor)
 * ```
 *
 * @property version The pack index version to use (V1 or V2)
 */
class IndexBlobBuilder(
    private val version: Int = IndexVersion.V2
) {
    private val entries = mutableListOf<ContentInfo>()

    /**
     * Adds a content info entry to the index.
     *
     * @param info The content info to add
     */
    fun add(info: ContentInfo) {
        entries.add(info)
    }

    /**
     * Adds multiple content info entries to the index.
     *
     * @param infos The content infos to add
     */
    fun addAll(infos: Collection<ContentInfo>) {
        entries.addAll(infos)
    }

    /**
     * Returns the number of entries in the builder.
     */
    fun size(): Int = entries.size

    /**
     * Clears all entries from the builder.
     */
    fun clear() {
        entries.clear()
    }

    /**
     * Builds the index blob data without encryption.
     *
     * The output includes:
     * - Pack index data (V1 or V2 format)
     * - 32-byte random suffix for uniqueness
     *
     * @return The raw index blob data (unencrypted)
     */
    fun buildUnencrypted(): ByteArray {
        val indexData = when (version) {
            IndexVersion.V1 -> PackIndexV1.build(entries)
            IndexVersion.V2 -> PackIndexV2.build(entries)
            else -> throw IllegalArgumentException("Unsupported index version: $version")
        }

        // Append random suffix for uniqueness
        val suffix = generateIndexBlobSuffix()

        val output = ByteArrayOutputStream()
        output.write(indexData)
        output.write(suffix)

        return output.toByteArray()
    }

    /**
     * Builds the index blob data with optional encryption.
     *
     * @param encryptor The encryptor to use, or null for no encryption
     * @param blobId The blob ID for nonce derivation during encryption
     * @return The final index blob data (encrypted if encryptor provided)
     */
    suspend fun build(encryptor: Encryptor?, blobId: BlobId): ByteArray {
        val unencrypted = buildUnencrypted()

        return if (encryptor != null) {
            // Derive content ID from blob ID for encryption nonce
            val contentId = deriveContentIdFromBlobId(blobId)
            encryptor.encrypt(unencrypted, contentId)
        } else {
            unencrypted
        }
    }

    /**
     * Builds the index blob and generates a blob ID from the resulting hash.
     *
     * @param encryptor The encryptor to use, or null for no encryption
     * @param hasher Function to hash the blob data and produce an ID suffix
     * @return Pair of (BlobId, encrypted index data)
     */
    suspend fun buildWithGeneratedId(
        encryptor: Encryptor?,
        hasher: (ByteArray) -> ByteArray
    ): Pair<BlobId, ByteArray> {
        // First build unencrypted to generate a hash-based ID
        val unencrypted = buildUnencrypted()
        val hash = hasher(unencrypted)
        val blobId = BlobId.indexBlob(hash.toHexString())

        // Then encrypt with the generated blob ID
        val encrypted = if (encryptor != null) {
            val contentId = deriveContentIdFromBlobId(blobId)
            encryptor.encrypt(unencrypted, contentId)
        } else {
            unencrypted
        }

        return Pair(blobId, encrypted)
    }

    /**
     * Returns a copy of the current entries.
     */
    fun getEntries(): List<ContentInfo> = entries.toList()

    companion object {
        /**
         * Derives a content ID from a blob ID for encryption nonce derivation.
         *
         * For index blobs, the nonce is derived from the blob ID by taking
         * the last 32 hex characters (16 bytes) before any dash separator.
         */
        fun deriveContentIdFromBlobId(blobId: BlobId): ContentId {
            val id = blobId.value
            // Remove the prefix (e.g., "n")
            val withoutPrefix = if (id.isNotEmpty() && id[0].isLetter()) {
                id.substring(1)
            } else {
                id
            }

            // Take up to 32 hex chars (16 bytes) for the content ID
            val dashIndex = withoutPrefix.indexOf('-')
            val hexPart = if (dashIndex >= 0) {
                withoutPrefix.substring(0, dashIndex)
            } else {
                withoutPrefix
            }

            // Take last 32 chars or all if shorter
            val contentIdHex = if (hexPart.length > 32) {
                hexPart.takeLast(32)
            } else {
                hexPart
            }

            return if (contentIdHex.isEmpty()) {
                ContentId.Empty
            } else {
                ContentId.parse(contentIdHex)
            }
        }
    }
}
