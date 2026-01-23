package org.kopiaKt.core.encryption

import org.kopiaKt.core.content.ContentId

/**
 * No-operation encryptor that passes data through unchanged.
 *
 * Used when encryption is disabled. Not recommended for production use.
 */
object NoOpEncryptor : Encryptor {

    override val algorithm: EncryptionAlgorithm = EncryptionAlgorithm.NONE

    override val overhead: Int = 0

    override suspend fun encrypt(plaintext: ByteArray, contentId: ContentId): ByteArray {
        return plaintext.copyOf()
    }

    override suspend fun decrypt(ciphertext: ByteArray, contentId: ContentId): ByteArray {
        return ciphertext.copyOf()
    }

    override suspend fun decryptWithRawId(ciphertext: ByteArray, contentIdBytes: ByteArray): ByteArray {
        return ciphertext.copyOf()
    }
}
