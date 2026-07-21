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
    private val encryptor: Encryptor?,
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
    suspend fun encrypt(data: ByteArray, blobId: BlobId): ByteArray = if (encryptor != null) {
        val contentId = deriveContentIdFromBlobId(blobId)
        encryptor.encrypt(data, contentId)
    } else {
        data
    }

    /**
     * Decrypts an encrypted index blob.
     *
     * @param encryptedData The encrypted blob data
     * @param blobId The blob ID (used for nonce derivation)
     * @return The decrypted data
     * @throws org.kopiaKt.core.encryption.DecryptionException if decryption fails
     */
    suspend fun decrypt(encryptedData: ByteArray, blobId: BlobId): ByteArray = if (encryptor != null) {
        // Get raw IV bytes from blob ID (16 bytes)
        val ivBytes = deriveIvBytesFromBlobId(blobId)
        // Use decryptWithRawId which passes raw bytes to HMAC and as AAD
        encryptor.decryptWithRawId(encryptedData, ivBytes)
    } else {
        encryptedData
    }

    companion object {
        /**
         * Size of the initialization vector for AES-GCM encryption.
         */
        private const val AES_BLOCK_SIZE = 16

        /**
         * Derives raw IV bytes from a blob ID.
         *
         * The Go implementation derives the IV by extracting hex characters from the
         * blob ID before any dash separator, taking the last 32 hex characters
         * (16 bytes = AES block size), and decoding them as hex to get 16 raw bytes.
         *
         * For example, for blob ID "xn0_86e1a966f4faa78b4155dbe7f1866fa8-sc05c6694229ca11e13d-c1":
         * - Take part before first dash: "xn0_86e1a966f4faa78b4155dbe7f1866fa8"
         * - Extract only hex chars: "086e1a966f4faa78b4155dbe7f1866fa8"
         * - Take last 32 hex chars: "86e1a966f4faa78b4155dbe7f1866fa8"
         * - Decode as hex to get 16 bytes
         *
         * @param blobId The blob ID to derive from
         * @return The raw IV bytes (16 bytes)
         */
        fun deriveIvBytesFromBlobId(blobId: BlobId): ByteArray {
            val id = blobId.value

            // Take part before first dash (if any) - no prefix removal
            val dashIndex = id.indexOf('-')
            val beforeDash = if (dashIndex >= 0) {
                id.substring(0, dashIndex)
            } else {
                id
            }

            // Extract only hex characters from the string (filter out prefixes like 'x', 'n0_' etc.)
            val hexChars = beforeDash.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

            // Take last 32 hex characters (16 bytes = AES block size)
            if (hexChars.length < AES_BLOCK_SIZE * 2) {
                return ByteArray(AES_BLOCK_SIZE) // Return zeros if too short
            }

            val last32 = hexChars.takeLast(AES_BLOCK_SIZE * 2)

            return last32.lowercase().hexToByteArray()
        }

        /**
         * Derives a content ID from a blob ID for nonce derivation.
         * (Kept for backwards compatibility but prefer deriveIvBytesFromBlobId)
         */
        fun deriveContentIdFromBlobId(blobId: BlobId): ContentId {
            val ivBytes = deriveIvBytesFromBlobId(blobId)
            if (ivBytes.all { it == 0.toByte() }) {
                return ContentId.Empty
            }
            return ContentId.parse(ivBytes.joinToString("") { "%02x".format(it) })
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
