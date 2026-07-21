package org.kopiaKt.core.encryption

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level AES-256-GCM cipher operations.
 *
 * This class provides raw AES-256-GCM encryption/decryption operations
 * that match Go's crypto/aes + cipher.NewGCM behavior exactly.
 *
 * AES-GCM parameters:
 * - Key size: 256 bits (32 bytes)
 * - Nonce size: 96 bits (12 bytes)
 * - Tag size: 128 bits (16 bytes)
 */
object Aes256GcmCipher {

    init {
        // Ensure BouncyCastle provider is registered
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** AES-256 key size in bytes */
    const val KEY_SIZE = 32

    /** GCM nonce size in bytes */
    const val NONCE_SIZE = 12

    /** GCM authentication tag size in bytes */
    const val TAG_SIZE = 16

    /** GCM tag size in bits (used by Java API) */
    private const val TAG_SIZE_BITS = TAG_SIZE * 8

    /** Total overhead added by encryption (nonce + tag) */
    const val OVERHEAD = NONCE_SIZE + TAG_SIZE

    /**
     * Encrypts plaintext using AES-256-GCM with specified key, nonce, and AAD.
     *
     * Note: This does NOT prepend the nonce to the output. For Kopia-compatible
     * format, use [encryptWithPrependedNonce].
     *
     * @param key 32-byte AES-256 key
     * @param nonce 12-byte GCM nonce
     * @param plaintext Data to encrypt
     * @param aad Additional Authenticated Data (can be empty)
     * @return Ciphertext with appended authentication tag
     */
    fun encryptRaw(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes, got ${key.size}" }
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes, got ${nonce.size}" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }

        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts ciphertext using AES-256-GCM with specified key, nonce, and AAD.
     *
     * Note: This expects ciphertext WITHOUT prepended nonce. For Kopia-compatible
     * format, use [decryptWithPrependedNonce].
     *
     * @param key 32-byte AES-256 key
     * @param nonce 12-byte GCM nonce
     * @param ciphertext Ciphertext with appended authentication tag
     * @param aad Additional Authenticated Data (must match what was used during encryption)
     * @return Decrypted plaintext
     * @throws DecryptionException if decryption or authentication fails
     */
    fun decryptRaw(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == KEY_SIZE) { "Key must be $KEY_SIZE bytes, got ${key.size}" }
        require(nonce.size == NONCE_SIZE) { "Nonce must be $NONCE_SIZE bytes, got ${nonce.size}" }

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, nonce)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }

            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw DecryptionException("Failed to decrypt: ${e.message}", e)
        }
    }

    /**
     * Encrypts plaintext and prepends the nonce to the output (Kopia format).
     *
     * Output format: [nonce (12 bytes)][ciphertext][tag (16 bytes)]
     *
     * @param key 32-byte AES-256 key
     * @param nonce 12-byte GCM nonce
     * @param plaintext Data to encrypt
     * @param aad Additional Authenticated Data (can be empty)
     * @return Nonce + Ciphertext + Tag
     */
    fun encryptWithPrependedNonce(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val ciphertext = encryptRaw(key, nonce, plaintext, aad)
        val result = ByteArray(NONCE_SIZE + ciphertext.size)
        System.arraycopy(nonce, 0, result, 0, NONCE_SIZE)
        System.arraycopy(ciphertext, 0, result, NONCE_SIZE, ciphertext.size)
        return result
    }

    /**
     * Decrypts data with prepended nonce (Kopia format).
     *
     * Input format: [nonce (12 bytes)][ciphertext][tag (16 bytes)]
     *
     * @param key 32-byte AES-256 key
     * @param data Nonce + Ciphertext + Tag
     * @param aad Additional Authenticated Data (must match what was used during encryption)
     * @return Decrypted plaintext
     * @throws DecryptionException if data is too short, or decryption/auth fails
     */
    fun decryptWithPrependedNonce(
        key: ByteArray,
        data: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (data.size < NONCE_SIZE + TAG_SIZE) {
            throw DecryptionException(
                "Ciphertext too short: ${data.size} bytes, minimum ${NONCE_SIZE + TAG_SIZE}",
            )
        }

        val nonce = data.copyOfRange(0, NONCE_SIZE)
        val ciphertext = data.copyOfRange(NONCE_SIZE, data.size)

        return decryptRaw(key, nonce, ciphertext, aad)
    }
}
