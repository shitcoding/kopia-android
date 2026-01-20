package org.kopiaKt.core.encryption

/**
 * Default implementation of EncryptorFactory.
 *
 * Creates encryptors for supported encryption algorithms.
 */
class DefaultEncryptorFactory : EncryptorFactory {

    override fun create(algorithm: EncryptionAlgorithm, masterKey: ByteArray): Encryptor {
        return when (algorithm) {
            EncryptionAlgorithm.AES256_GCM_HMAC_SHA256 -> {
                Aes256GcmHmacSha256Encryptor.create(masterKey)
            }
            EncryptionAlgorithm.NONE -> {
                NoOpEncryptor
            }
            EncryptionAlgorithm.CHACHA20_POLY1305_HMAC_SHA256 -> {
                throw UnsupportedOperationException(
                    "ChaCha20-Poly1305 encryption is not yet implemented"
                )
            }
        }
    }
}
