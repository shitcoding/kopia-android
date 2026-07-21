package org.kopiaKt.core.encryption

import org.kopiaKt.core.content.ContentId

/**
 * Supported encryption algorithms.
 *
 * These algorithms must match the Go implementation exactly for cross-compatibility.
 */
enum class EncryptionAlgorithm(val id: String) {
    /**
     * No encryption.
     */
    NONE("NONE"),

    /**
     * AES-256-GCM with HMAC-SHA256. Default and recommended algorithm.
     */
    AES256_GCM_HMAC_SHA256("AES256-GCM-HMAC-SHA256"),

    /**
     * ChaCha20-Poly1305 (not yet implemented).
     */
    CHACHA20_POLY1305_HMAC_SHA256("CHACHA20-POLY1305-HMAC-SHA256"),
    ;

    companion object {
        /**
         * Default encryption algorithm for new repositories.
         */
        val DEFAULT = AES256_GCM_HMAC_SHA256

        /**
         * Finds algorithm by ID string.
         */
        fun fromId(id: String): EncryptionAlgorithm? = entries.find { it.id == id }

        /**
         * Finds algorithm by ID string (alias for fromId).
         */
        fun fromString(id: String): EncryptionAlgorithm? = fromId(id)
    }
}

/**
 * Interface for encryption/decryption operations.
 *
 * Implementations must produce byte-exact output matching the Go implementation
 * for cross-compatibility.
 */
interface Encryptor {
    /**
     * The encryption algorithm used by this encryptor.
     */
    val algorithm: EncryptionAlgorithm

    /**
     * Overhead in bytes added by encryption (nonce + tag).
     */
    val overhead: Int

    /**
     * Encrypts plaintext data.
     *
     * The content ID is used to derive the nonce/IV, ensuring deterministic
     * encryption for the same content.
     *
     * @param plaintext The data to encrypt
     * @param contentId The content ID for nonce derivation
     * @return The ciphertext
     */
    suspend fun encrypt(plaintext: ByteArray, contentId: ContentId): ByteArray

    /**
     * Decrypts ciphertext data.
     *
     * @param ciphertext The data to decrypt
     * @param contentId The content ID for nonce derivation
     * @return The plaintext
     * @throws DecryptionException if decryption fails
     */
    suspend fun decrypt(ciphertext: ByteArray, contentId: ContentId): ByteArray

    /**
     * Decrypts ciphertext data using raw bytes as the content ID.
     *
     * This is used for index blob decryption where the content ID is derived
     * directly from the blob ID as raw bytes, not a hex-encoded string.
     *
     * @param ciphertext The data to decrypt
     * @param contentIdBytes The raw content ID bytes for key derivation and AAD
     * @return The plaintext
     * @throws DecryptionException if decryption fails
     */
    suspend fun decryptWithRawId(ciphertext: ByteArray, contentIdBytes: ByteArray): ByteArray

    /**
     * Encrypts plaintext using raw bytes directly as the key-derivation/AAD "content ID".
     *
     * Unlike [encrypt], the given bytes are used verbatim (not truncated via a ContentId) for both the
     * per-message key derivation and the AEAD AAD. This matches Go kopia's `Encryptor.Encrypt(plaintext,
     * contentID)` and is required for the pack-blob local (recovery) index, whose "content ID" is the
     * repo's keyed hash of the plaintext index (full, untruncated output). It is the encrypt counterpart
     * of [decryptWithRawId].
     *
     * @param plaintext The data to encrypt
     * @param contentIdBytes The raw content ID bytes for key derivation and AAD
     * @return The ciphertext (`nonce || ciphertext || tag`)
     */
    suspend fun encryptWithRawId(plaintext: ByteArray, contentIdBytes: ByteArray): ByteArray
}

/**
 * Factory for creating encryptors.
 */
interface EncryptorFactory {
    /**
     * Creates an encryptor with the given master key.
     *
     * @param algorithm The encryption algorithm to use
     * @param masterKey The master key
     * @return An Encryptor instance
     */
    fun create(algorithm: EncryptionAlgorithm, masterKey: ByteArray): Encryptor
}

/**
 * Exception thrown when decryption fails.
 */
class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
