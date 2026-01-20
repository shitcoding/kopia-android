package org.kopiaKt.core.index

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.encryption.Encryptor
import org.kopiaKt.core.pack.PackIndex
import org.kopiaKt.core.pack.PackIndexV1
import org.kopiaKt.core.pack.PackIndexV2

/**
 * Reader for index blobs.
 *
 * Index blobs contain encrypted pack index data (V1 or V2 format)
 * with a random suffix for uniqueness.
 *
 * Usage:
 * ```
 * val reader = IndexBlobReader.open(
 *     data = encryptedData,
 *     blobId = blobId,
 *     encryptor = encryptor
 * )
 * val info = reader.getInfo(contentId)
 * reader.close()
 * ```
 */
class IndexBlobReader private constructor(
    private val index: PackIndex,
    private val blobId: BlobId
) : PackIndex by index {

    /**
     * The blob ID of this index blob.
     */
    fun getBlobId(): BlobId = blobId

    override fun close() {
        index.close()
    }

    companion object {
        /**
         * Opens an index blob for reading.
         *
         * @param data The raw blob data (encrypted or unencrypted)
         * @param blobId The blob ID (used for nonce derivation if encrypted)
         * @param encryptor The encryptor to use for decryption, or null if unencrypted
         * @param v1PerContentOverhead Encryption overhead for V1 indexes
         * @return The opened index blob reader
         * @throws IllegalArgumentException if the data is invalid
         */
        suspend fun open(
            data: ByteArray,
            blobId: BlobId,
            encryptor: Encryptor? = null,
            v1PerContentOverhead: UInt = 0u
        ): IndexBlobReader {
            // Decrypt if encryptor provided
            val decrypted = if (encryptor != null) {
                val contentId = IndexBlobBuilder.deriveContentIdFromBlobId(blobId)
                encryptor.decrypt(data, contentId)
            } else {
                data
            }

            // Remove the random suffix (last 32 bytes)
            val indexData = if (decrypted.size > IndexBlobConstants.RANDOM_SUFFIX_SIZE) {
                decrypted.copyOfRange(0, decrypted.size - IndexBlobConstants.RANDOM_SUFFIX_SIZE)
            } else {
                decrypted
            }

            // Detect version and open appropriate index
            if (indexData.isEmpty()) {
                throw IllegalArgumentException("Index blob data is empty")
            }

            val version = indexData[0].toInt() and 0xFF
            val index = when (version) {
                IndexVersion.V1 -> PackIndexV1.open(indexData, v1PerContentOverhead)
                IndexVersion.V2 -> PackIndexV2.open(indexData)
                else -> throw IllegalArgumentException("Unsupported index version: $version")
            }

            return IndexBlobReader(index, blobId)
        }

        /**
         * Opens an unencrypted index blob.
         *
         * @param data The raw index blob data
         * @param blobId The blob ID
         * @param v1PerContentOverhead Encryption overhead for V1 indexes
         * @return The opened index blob reader
         */
        fun openUnencrypted(
            data: ByteArray,
            blobId: BlobId,
            v1PerContentOverhead: UInt = 0u
        ): IndexBlobReader {
            // Remove the random suffix (last 32 bytes)
            val indexData = if (data.size > IndexBlobConstants.RANDOM_SUFFIX_SIZE) {
                data.copyOfRange(0, data.size - IndexBlobConstants.RANDOM_SUFFIX_SIZE)
            } else {
                data
            }

            // Detect version and open appropriate index
            if (indexData.isEmpty()) {
                throw IllegalArgumentException("Index blob data is empty")
            }

            val version = indexData[0].toInt() and 0xFF
            val index = when (version) {
                IndexVersion.V1 -> PackIndexV1.open(indexData, v1PerContentOverhead)
                IndexVersion.V2 -> PackIndexV2.open(indexData)
                else -> throw IllegalArgumentException("Unsupported index version: $version")
            }

            return IndexBlobReader(index, blobId)
        }

        /**
         * Opens an index blob from raw pack index data (without random suffix).
         *
         * This is useful when reading embedded local indexes from pack blobs.
         *
         * @param indexData The raw pack index data
         * @param blobId The blob ID
         * @param v1PerContentOverhead Encryption overhead for V1 indexes
         * @return The opened index blob reader
         */
        fun openRaw(
            indexData: ByteArray,
            blobId: BlobId,
            v1PerContentOverhead: UInt = 0u
        ): IndexBlobReader {
            if (indexData.isEmpty()) {
                throw IllegalArgumentException("Index data is empty")
            }

            val version = indexData[0].toInt() and 0xFF
            val index = when (version) {
                IndexVersion.V1 -> PackIndexV1.open(indexData, v1PerContentOverhead)
                IndexVersion.V2 -> PackIndexV2.open(indexData)
                else -> throw IllegalArgumentException("Unsupported index version: $version")
            }

            return IndexBlobReader(index, blobId)
        }
    }
}

/**
 * Extension function to get all content infos from an index as a list.
 */
fun PackIndex.toList(): List<ContentInfo> = iterate().toList()

/**
 * Extension function to filter content infos by pack blob ID.
 */
fun PackIndex.filterByPackBlobId(packBlobId: BlobId): Sequence<ContentInfo> =
    iterate().filter { it.packBlobId == packBlobId }

/**
 * Extension function to find all deleted content IDs.
 */
fun PackIndex.findDeleted(): Sequence<ContentInfo> =
    iterate().filter { it.deleted }

/**
 * Extension function to find all non-deleted content IDs.
 */
fun PackIndex.findActive(): Sequence<ContentInfo> =
    iterate().filter { !it.deleted }
