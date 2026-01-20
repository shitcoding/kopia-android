package org.kopiaKt.core.index

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.hexToByteArray
import org.kopiaKt.core.encryption.Encryptor

/**
 * Manages encryption and decryption of index blobs.
 *
 * Index blobs are encrypted using AES-256-GCM with a nonce derived from
 * the blob ID. This ensures deterministic encryption while maintaining security.
 *
 * @property encryptor The encryptor to use for encryption/decryption operations
 */
class IndexBlobEncryption(
    private val encryptor: Encryptor?
) {
    /**
     * Whether encryption is enabled.
     */
    val isEncryptionEnabled: Boolean
        get() = encryptor != null

    /**
     * The encryption overhead in bytes.
     */
    val overhead: Int
        get() = encryptor?.overhead ?: 0

    /**
     * Encrypts raw index data for storage as an index blob.
     *
     * @param data The raw index data (pack index + random suffix)
     * @param blobId The blob ID (used for nonce derivation)
     * @return The encrypted data, or the original data if encryption is disabled
     */
    suspend fun encrypt(data: ByteArray, blobId: BlobId): ByteArray {
        return if (encryptor != null) {
            val contentId = deriveContentIdFromBlobId(blobId)
            encryptor.encrypt(data, contentId)
        } else {
            data
        }
    }

    /**
     * Decrypts an encrypted index blob.
     *
     * @param encryptedData The encrypted blob data
     * @param blobId The blob ID (used for nonce derivation)
     * @return The decrypted data
     * @throws org.kopiaKt.core.encryption.DecryptionException if decryption fails
     */
    suspend fun decrypt(encryptedData: ByteArray, blobId: BlobId): ByteArray {
        return if (encryptor != null) {
            val contentId = deriveContentIdFromBlobId(blobId)
            encryptor.decrypt(encryptedData, contentId)
        } else {
            encryptedData
        }
    }

    companion object {
        /**
         * Size of the initialization vector for AES-GCM encryption.
         */
        private const val AES_BLOCK_SIZE = 16

        /**
         * Derives a content ID from a blob ID for nonce derivation.
         *
         * The Go implementation derives the IV by taking the last 32 hex characters
         * (16 bytes = AES block size) from the blob ID before any dash separator.
         *
         * For example, for blob ID "n1234567890abcdef1234567890abcdef-s12345":
         * - Remove prefix 'n': "1234567890abcdef1234567890abcdef-s12345"
         * - Take part before dash: "1234567890abcdef1234567890abcdef"
         * - Take last 32 hex chars: "1234567890abcdef1234567890abcdef"
         * - Convert to ContentId
         *
         * @param blobId The blob ID to derive from
         * @return The derived content ID for encryption nonce
         */
        fun deriveContentIdFromBlobId(blobId: BlobId): ContentId {
            val id = blobId.value

            // Remove the prefix (e.g., "n", "p", "q")
            val withoutPrefix = if (id.isNotEmpty() && id[0] in 'a'..'z') {
                id.substring(1)
            } else {
                id
            }

            // Take part before dash (if any)
            val dashIndex = withoutPrefix.indexOf('-')
            val hexPart = if (dashIndex >= 0) {
                withoutPrefix.substring(0, dashIndex)
            } else {
                withoutPrefix
            }

            // Validate hex characters
            val validHex = hexPart.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

            // Take last 32 hex chars (16 bytes = AES block size) or all if shorter
            val contentIdHex = if (validHex.length > AES_BLOCK_SIZE * 2) {
                validHex.takeLast(AES_BLOCK_SIZE * 2)
            } else {
                validHex
            }

            return if (contentIdHex.isEmpty()) {
                ContentId.Empty
            } else {
                // Pad with zeros if needed to ensure valid ContentId
                val paddedHex = contentIdHex.padStart(2, '0')
                ContentId.parse(paddedHex.lowercase())
            }
        }

        /**
         * Creates an unencrypted IndexBlobEncryption instance.
         */
        fun unencrypted(): IndexBlobEncryption = IndexBlobEncryption(null)
    }
}

/**
 * Extension function to extract IV bytes from a blob ID.
 *
 * This is useful for low-level encryption operations.
 *
 * @return The IV bytes (16 bytes) derived from the blob ID
 */
fun BlobId.toIvBytes(): ByteArray {
    val contentId = IndexBlobEncryption.deriveContentIdFromBlobId(this)
    val idStr = contentId.toString()

    return if (idStr.isEmpty()) {
        ByteArray(16) // All zeros
    } else {
        // Pad to 32 hex chars (16 bytes)
        val paddedHex = idStr.padStart(32, '0')
        paddedHex.hexToByteArray()
    }
}
