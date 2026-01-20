package org.kopiaKt.core.encryption

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.kopiaKt.core.content.ContentId
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption with HMAC-SHA256 key derivation.
 *
 * This implementation matches Go Kopia's AES256-GCM-HMAC-SHA256 encryptor exactly:
 *
 * 1. Master key is used with HKDF-SHA256 to derive a "key derivation secret"
 *    using "encryption" as the salt/purpose
 *
 * 2. For each content, a per-content AES key is derived using HMAC-SHA256:
 *    contentKey = HMAC-SHA256(keyDerivationSecret, contentId)
 *
 * 3. Content is encrypted with AES-256-GCM using a random 12-byte nonce
 *
 * 4. The content ID is used as Additional Authenticated Data (AAD)
 *
 * 5. Output format: [nonce (12 bytes)][ciphertext][tag (16 bytes)]
 *
 * Total overhead: 28 bytes (12 nonce + 16 tag)
 */
class Aes256GcmHmacSha256Encryptor(
    private val keyDerivationSecret: ByteArray
) : Encryptor {

    private val secureRandom = SecureRandom()

    init {
        ensureBouncyCastleProvider()
        require(keyDerivationSecret.size == KEY_DERIVATION_SECRET_SIZE) {
            "Key derivation secret must be $KEY_DERIVATION_SECRET_SIZE bytes, got ${keyDerivationSecret.size}"
        }
    }

    override val algorithm: EncryptionAlgorithm = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256

    override val overhead: Int = OVERHEAD

    override suspend fun encrypt(plaintext: ByteArray, contentId: ContentId): ByteArray {
        // Derive per-content AES key using HMAC-SHA256
        val contentKey = deriveContentKey(contentId)

        // Generate random nonce
        val nonce = ByteArray(Aes256GcmCipher.NONCE_SIZE)
        secureRandom.nextBytes(nonce)

        // Content ID bytes are used as AAD
        val aad = contentId.value.toByteArray(Charsets.UTF_8)

        // Encrypt with nonce prepended
        return Aes256GcmCipher.encryptWithPrependedNonce(contentKey, nonce, plaintext, aad)
    }

    override suspend fun decrypt(ciphertext: ByteArray, contentId: ContentId): ByteArray {
        // Derive per-content AES key using HMAC-SHA256
        val contentKey = deriveContentKey(contentId)

        // Content ID bytes are used as AAD
        val aad = contentId.value.toByteArray(Charsets.UTF_8)

        // Decrypt (nonce is prepended to ciphertext)
        return Aes256GcmCipher.decryptWithPrependedNonce(contentKey, ciphertext, aad)
    }

    /**
     * Derives the per-content AES-256 key using HMAC-SHA256.
     *
     * This matches Go's behavior:
     * ```go
     * h := hmac.New(sha256.New, keyDerivationSecret)
     * h.Write(contentID)
     * key := h.Sum(nil) // 32 bytes
     * ```
     */
    private fun deriveContentKey(contentId: ContentId): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(keyDerivationSecret, HMAC_ALGORITHM))
        mac.update(contentId.value.toByteArray(Charsets.UTF_8))
        return mac.doFinal()
    }

    companion object {
        /** Purpose string used for HKDF key derivation (matches Go) */
        const val PURPOSE_ENCRYPTION_KEY = "encryption"

        /** Size of the key derivation secret in bytes */
        const val KEY_DERIVATION_SECRET_SIZE = 32

        /** Total overhead: nonce (12) + tag (16) */
        const val OVERHEAD = Aes256GcmCipher.OVERHEAD

        /** HMAC algorithm for per-content key derivation */
        private const val HMAC_ALGORITHM = "HmacSHA256"

        /**
         * Creates an encryptor from a master key.
         *
         * Uses HKDF-SHA256 to derive the key derivation secret from the master key,
         * matching Go's behavior:
         * ```go
         * keyDerivationSecret, err := hkdf.Key(sha256.New, masterKey, purpose, "", 32)
         * ```
         */
        fun create(masterKey: ByteArray): Aes256GcmHmacSha256Encryptor {
            val keyDerivationSecret = deriveKeyDerivationSecret(
                masterKey,
                PURPOSE_ENCRYPTION_KEY.toByteArray(Charsets.UTF_8),
                KEY_DERIVATION_SECRET_SIZE
            )
            return Aes256GcmHmacSha256Encryptor(keyDerivationSecret)
        }

        /**
         * Derives a key using HKDF-SHA256.
         *
         * Go's hkdf.Key function signature:
         * ```go
         * func Key(h func() hash.Hash, secret, salt []byte, info string, length int) ([]byte, error)
         * ```
         *
         * In Go's encryption.deriveKey:
         * - secret = masterKey
         * - salt = purpose (e.g., "encryption")
         * - info = ""
         */
        internal fun deriveKeyDerivationSecret(
            masterKey: ByteArray,
            salt: ByteArray,
            length: Int
        ): ByteArray {
            val hkdf = HKDFBytesGenerator(SHA256Digest())

            // Go's hkdf.Key uses: Extract(salt, secret) then Expand(info, length)
            // When salt is provided, it's used in the Extract phase
            // When info is "", we pass empty byte array to Expand
            val params = HKDFParameters(masterKey, salt, ByteArray(0))
            hkdf.init(params)

            val output = ByteArray(length)
            hkdf.generateBytes(output, 0, length)

            return output
        }

        private fun ensureBouncyCastleProvider() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}
