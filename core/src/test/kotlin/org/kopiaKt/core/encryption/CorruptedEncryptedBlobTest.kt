package org.kopiaKt.core.encryption

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.testutil.CorruptionHelpers
import kotlin.random.Random

/**
 * Tests that AES-256-GCM AEAD properly rejects corrupted ciphertexts.
 *
 * Ciphertext format: [nonce (12 bytes)][ciphertext][tag (16 bytes)]
 * Total overhead: 28 bytes (NONCE_SIZE=12 + TAG_SIZE=16)
 *
 * Expands on the single corruption test in Aes256GcmEncryptorTest to provide
 * systematic coverage of all corruption locations and types.
 */
class CorruptedEncryptedBlobTest {

    private lateinit var encryptor: Encryptor
    private val masterKey = ByteArray(32) { it.toByte() }
    private val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")
    private val differentContentId = ContentId.parse("kff0e0d0c0b0a09080706050403020100")
    private val plaintext = "Hello, World! This is a test payload for corruption detection.".toByteArray()
    private lateinit var validCiphertext: ByteArray

    @BeforeEach
    fun setup() = runBlocking {
        encryptor = DefaultEncryptorFactory().create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)
        validCiphertext = encryptor.encrypt(plaintext, contentId)
    }

    @Nested
    @DisplayName("Nonce Area Corruption")
    inner class NonceCorruption {
        @Test
        fun `should reject ciphertext with bit flip at byte 0 (first nonce byte)`() {
            val corrupted = CorruptionHelpers.bitFlip(validCiphertext, 0)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext with bit flip at byte 11 (last nonce byte)`() {
            val corrupted = CorruptionHelpers.bitFlip(validCiphertext, 11)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext with zeroed nonce`() {
            val corrupted = CorruptionHelpers.zeroRange(validCiphertext, 0, Aes256GcmCipher.NONCE_SIZE)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }
    }

    @Nested
    @DisplayName("Ciphertext Body Corruption")
    inner class CiphertextBodyCorruption {
        @Test
        fun `should reject ciphertext with bit flip at byte 12 (first ciphertext byte)`() {
            val corrupted = CorruptionHelpers.bitFlip(validCiphertext, Aes256GcmCipher.NONCE_SIZE)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext with bit flip in middle of body`() {
            val bodyStart = Aes256GcmCipher.NONCE_SIZE
            val bodyEnd = validCiphertext.size - Aes256GcmCipher.TAG_SIZE
            val middleOfBody = bodyStart + (bodyEnd - bodyStart) / 2
            val corrupted = CorruptionHelpers.bitFlip(validCiphertext, middleOfBody)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext with last body byte before tag flipped`() {
            val lastBodyByte = validCiphertext.size - Aes256GcmCipher.TAG_SIZE - 1
            val corrupted = CorruptionHelpers.bitFlip(validCiphertext, lastBodyByte)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }
    }

    @Nested
    @DisplayName("GCM Tag Corruption")
    inner class TagCorruption {
        @Test
        fun `should reject ciphertext with bit flip at first tag byte`() {
            val tagStart = validCiphertext.size - Aes256GcmCipher.TAG_SIZE
            val corrupted = CorruptionHelpers.bitFlip(validCiphertext, tagStart)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext with bit flip at last tag byte`() {
            val corrupted = CorruptionHelpers.bitFlip(validCiphertext, validCiphertext.size - 1)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext with zeroed tag`() {
            val tagStart = validCiphertext.size - Aes256GcmCipher.TAG_SIZE
            val corrupted = CorruptionHelpers.zeroRange(validCiphertext, tagStart, Aes256GcmCipher.TAG_SIZE)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }
    }

    @Nested
    @DisplayName("Truncation")
    inner class Truncation {
        @Test
        fun `should reject empty byte array`() {
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(ByteArray(0), contentId) }
            }
        }

        @Test
        fun `should reject ciphertext shorter than overhead`() {
            val tooShort = ByteArray(Aes256GcmCipher.OVERHEAD - 1)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(tooShort, contentId) }
            }
        }

        @Test
        fun `should reject truncated ciphertext missing GCM tag`() {
            val corrupted = CorruptionHelpers.truncate(validCiphertext, validCiphertext.size - Aes256GcmCipher.TAG_SIZE)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject truncated ciphertext missing 1 byte`() {
            val corrupted = CorruptionHelpers.truncate(validCiphertext, validCiphertext.size - 1)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext truncated to just nonce`() {
            val corrupted = CorruptionHelpers.truncate(validCiphertext, Aes256GcmCipher.NONCE_SIZE)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }
    }

    @Nested
    @DisplayName("Appended Data")
    inner class AppendedData {
        @Test
        fun `should reject ciphertext with appended bytes`() {
            val corrupted = CorruptionHelpers.appendBytes(validCiphertext, byteArrayOf(0x00, 0x01, 0x02))
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext with single appended byte`() {
            val corrupted = CorruptionHelpers.appendBytes(validCiphertext, byteArrayOf(0x00))
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }
    }

    @Nested
    @DisplayName("Wrong Content ID (Key Mismatch)")
    inner class WrongContentId {
        @Test
        fun `should reject ciphertext decrypted with different content ID`() {
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(validCiphertext, differentContentId) }
            }
        }
    }

    @Nested
    @DisplayName("Wrong Master Key")
    inner class WrongMasterKey {
        @Test
        fun `should reject ciphertext decrypted with different master key`() {
            val differentKey = ByteArray(32) { (it + 1).toByte() }
            val differentEncryptor = DefaultEncryptorFactory().create(
                EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, differentKey
            )
            assertThrows(DecryptionException::class.java) {
                runBlocking { differentEncryptor.decrypt(validCiphertext, contentId) }
            }
        }
    }

    @Nested
    @DisplayName("Inserted Garbage")
    inner class InsertedGarbage {
        @Test
        fun `should reject ciphertext with garbage inserted in nonce area`() {
            val corrupted = CorruptionHelpers.insertGarbage(validCiphertext, 6, 4, Random(42))
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }

        @Test
        fun `should reject ciphertext with garbage inserted in body`() {
            val bodyMid = Aes256GcmCipher.NONCE_SIZE + (validCiphertext.size - Aes256GcmCipher.OVERHEAD) / 2
            val corrupted = CorruptionHelpers.insertGarbage(validCiphertext, bodyMid, 8, Random(42))
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(corrupted, contentId) }
            }
        }
    }

    @Nested
    @DisplayName("Complete Replacement")
    inner class CompleteReplacement {
        @Test
        fun `should reject completely random data of same length`() {
            val random = Random(42)
            val garbage = ByteArray(validCiphertext.size).also { random.nextBytes(it) }
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(garbage, contentId) }
            }
        }

        @Test
        fun `should reject all-zeros data of same length`() {
            val zeros = ByteArray(validCiphertext.size)
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(zeros, contentId) }
            }
        }
    }
}
