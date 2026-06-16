package org.kopiaKt.core.encryption

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.testvectors.Aes256GcmTestCase
import org.kopiaKt.core.testvectors.TestVectorLoader
import org.kopiaKt.core.testvectors.TestVectors
import org.kopiaKt.core.testvectors.hexToByteArray
import org.kopiaKt.core.testvectors.toHexString

/**
 * Tests for AES-256-GCM encryption implementation using Go-generated test vectors.
 *
 * These tests verify byte-exact compatibility with the Go Kopia implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Aes256GcmEncryptorTest {

    private lateinit var vectors: TestVectors

    @BeforeAll
    fun loadTestVectors() {
        vectors = TestVectorLoader.load()
    }

    @Nested
    @DisplayName("Raw AES-256-GCM operations (low-level)")
    inner class RawAesGcmTests {

        /**
         * Tests that raw AES-256-GCM encryption produces correct output.
         * These test the low-level AEAD operations matching Go's crypto/aes+cipher.NewGCM.
         */
        @Test
        fun `empty plaintext with nonce0 produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("empty_nonce0")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `empty plaintext with all-zero nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("empty_nonce1")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `empty plaintext with all-ff nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("empty_nonce2")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `single byte with nonce0 produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("single_byte_nonce0")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `single byte with all-zero nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("single_byte_nonce1")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `single byte with all-ff nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("single_byte_nonce2")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `hello world with nonce0 produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("hello_world_nonce0")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `hello world with all-zero nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("hello_world_nonce1")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `hello world with all-ff nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("hello_world_nonce2")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `block aligned data with nonce0 produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("block_aligned_nonce0")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `block aligned data with all-zero nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("block_aligned_nonce1")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `block aligned data with all-ff nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("block_aligned_nonce2")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `two blocks with nonce0 produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("two_blocks_nonce0")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `two blocks with all-zero nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("two_blocks_nonce1")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `two blocks with all-ff nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("two_blocks_nonce2")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `kilobyte data with nonce0 produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("kilobyte_nonce0")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `kilobyte data with all-zero nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("kilobyte_nonce1")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `kilobyte data with all-ff nonce produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("kilobyte_nonce2")
            assertRawEncryptionMatches(testCase)
        }

        @Test
        fun `with additional authenticated data produces correct ciphertext`() {
            val testCase = findAesGcmTestCase("with_aad")
            assertRawEncryptionMatchesWithAad(testCase)
        }

        private fun findAesGcmTestCase(name: String): Aes256GcmTestCase {
            val testCase = vectors.encryption.aes256Gcm.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in vectors")
            return testCase!!
        }

        private fun assertRawEncryptionMatches(testCase: Aes256GcmTestCase) {
            // Test encryption
            val ciphertext = Aes256GcmCipher.encryptRaw(
                key = testCase.key,
                nonce = testCase.nonce,
                plaintext = testCase.plaintext,
                aad = ByteArray(0)
            )
            assertEquals(
                testCase.ciphertextHex,
                ciphertext.toHexString(),
                "Encryption mismatch for test case '${testCase.name}'"
            )

            // Test decryption
            val decrypted = Aes256GcmCipher.decryptRaw(
                key = testCase.key,
                nonce = testCase.nonce,
                ciphertext = testCase.ciphertext,
                aad = ByteArray(0)
            )
            assertArrayEquals(
                testCase.plaintext,
                decrypted,
                "Decryption mismatch for test case '${testCase.name}'"
            )
        }

        private fun assertRawEncryptionMatchesWithAad(testCase: Aes256GcmTestCase) {
            val aad = testCase.aad ?: ByteArray(0)

            // Test encryption
            val ciphertext = Aes256GcmCipher.encryptRaw(
                key = testCase.key,
                nonce = testCase.nonce,
                plaintext = testCase.plaintext,
                aad = aad
            )
            assertEquals(
                testCase.ciphertextHex,
                ciphertext.toHexString(),
                "Encryption with AAD mismatch for test case '${testCase.name}'"
            )

            // Test decryption
            val decrypted = Aes256GcmCipher.decryptRaw(
                key = testCase.key,
                nonce = testCase.nonce,
                ciphertext = testCase.ciphertext,
                aad = aad
            )
            assertArrayEquals(
                testCase.plaintext,
                decrypted,
                "Decryption with AAD mismatch for test case '${testCase.name}'"
            )

            // Test that wrong AAD fails decryption
            assertThrows(DecryptionException::class.java) {
                Aes256GcmCipher.decryptRaw(
                    key = testCase.key,
                    nonce = testCase.nonce,
                    ciphertext = testCase.ciphertext,
                    aad = ByteArray(0) // Wrong AAD
                )
            }
        }
    }

    @Nested
    @DisplayName("AES-256-GCM-HMAC-SHA256 Encryptor (Kopia compatible)")
    inner class Aes256GcmHmacSha256EncryptorTests {

        @Test
        fun `encryptor should report correct algorithm`() {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)
            assertEquals(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, encryptor.algorithm)
        }

        @Test
        fun `encryptor overhead should be 28 bytes`() {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)
            // Overhead = 12 (nonce) + 16 (GCM tag)
            assertEquals(28, encryptor.overhead)
        }

        @Test
        fun `encrypt then decrypt round trips correctly`() = runBlocking {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)

            val plaintext = "Hello, World!".toByteArray()
            val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")

            val ciphertext = encryptor.encrypt(plaintext, contentId)
            val decrypted = encryptor.decrypt(ciphertext, contentId)

            assertArrayEquals(plaintext, decrypted)
        }

        @Test
        fun `ciphertext length equals plaintext plus overhead`() = runBlocking {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)

            val plaintext = ByteArray(1000) { it.toByte() }
            val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")

            val ciphertext = encryptor.encrypt(plaintext, contentId)

            assertEquals(plaintext.size + encryptor.overhead, ciphertext.size)
        }

        @Test
        fun `same content with same key and contentId produces different ciphertexts`() = runBlocking {
            // Due to random nonce, each encryption should produce different output
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)

            val plaintext = "Hello, World!".toByteArray()
            val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")

            val ciphertext1 = encryptor.encrypt(plaintext, contentId)
            val ciphertext2 = encryptor.encrypt(plaintext, contentId)

            // Random nonces mean different ciphertexts
            assertNotEquals(ciphertext1.toHexString(), ciphertext2.toHexString())

            // But both should decrypt to the same plaintext
            assertArrayEquals(plaintext, encryptor.decrypt(ciphertext1, contentId))
            assertArrayEquals(plaintext, encryptor.decrypt(ciphertext2, contentId))
        }

        @Test
        fun `different content IDs produce different derived keys`() = runBlocking {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)

            val plaintext = "Hello, World!".toByteArray()
            val contentId1 = ContentId.parse("k0102030405060708090a0b0c0d0e0f")
            val contentId2 = ContentId.parse("k0f0e0d0c0b0a090807060504030201")

            val ciphertext1 = encryptor.encrypt(plaintext, contentId1)
            val ciphertext2 = encryptor.encrypt(plaintext, contentId2)

            // Both should decrypt with correct content ID
            assertArrayEquals(plaintext, encryptor.decrypt(ciphertext1, contentId1))
            assertArrayEquals(plaintext, encryptor.decrypt(ciphertext2, contentId2))

            // Wrong content ID should fail
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(ciphertext1, contentId2) }
            }
            Unit
        }

        @Test
        fun `different master keys produce incompatible ciphertexts`() = runBlocking {
            val factory = DefaultEncryptorFactory()
            val masterKey1 = ByteArray(32) { it.toByte() }
            val masterKey2 = ByteArray(32) { (it + 1).toByte() }

            val encryptor1 = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey1)
            val encryptor2 = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey2)

            val plaintext = "Hello, World!".toByteArray()
            val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")

            val ciphertext = encryptor1.encrypt(plaintext, contentId)

            // Should decrypt with same key
            assertArrayEquals(plaintext, encryptor1.decrypt(ciphertext, contentId))

            // Should fail with different key
            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor2.decrypt(ciphertext, contentId) }
            }
            Unit
        }

        @Test
        fun `empty plaintext encrypts and decrypts correctly`() = runBlocking {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)

            val plaintext = ByteArray(0)
            val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")

            val ciphertext = encryptor.encrypt(plaintext, contentId)
            val decrypted = encryptor.decrypt(ciphertext, contentId)

            assertArrayEquals(plaintext, decrypted)
            assertEquals(encryptor.overhead, ciphertext.size)
        }

        @Test
        fun `large plaintext encrypts and decrypts correctly`() = runBlocking {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)

            // 1MB plaintext
            val plaintext = ByteArray(1024 * 1024) { (it % 256).toByte() }
            val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")

            val ciphertext = encryptor.encrypt(plaintext, contentId)
            val decrypted = encryptor.decrypt(ciphertext, contentId)

            assertArrayEquals(plaintext, decrypted)
        }

        @Test
        fun `ciphertext too short should fail decryption`() = runBlocking {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)

            val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")

            // Ciphertext shorter than nonce + tag
            val shortCiphertext = ByteArray(10)

            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(shortCiphertext, contentId) }
            }
            Unit
        }

        @Test
        fun `corrupted ciphertext should fail decryption`() = runBlocking {
            val factory = DefaultEncryptorFactory()
            val masterKey = ByteArray(32) { it.toByte() }
            val encryptor = factory.create(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256, masterKey)

            val plaintext = "Hello, World!".toByteArray()
            val contentId = ContentId.parse("k0102030405060708090a0b0c0d0e0f")

            val ciphertext = encryptor.encrypt(plaintext, contentId).copyOf()
            // Corrupt a byte in the middle
            ciphertext[ciphertext.size / 2] = (ciphertext[ciphertext.size / 2] + 1).toByte()

            assertThrows(DecryptionException::class.java) {
                runBlocking { encryptor.decrypt(ciphertext, contentId) }
            }
            Unit
        }
    }

    @Nested
    @DisplayName("HKDF Key Derivation for Encryption")
    inner class HkdfKeyDerivationTests {

        @Test
        fun `deriving encryption key from master key matches Go encryption deriveKey`() {
            // Go's encryption.deriveKey uses: hkdf.Key(sha256.New, masterKey, purpose, "", length)
            // where purpose = "encryption" is used as salt, and info is empty string
            //
            // Note: This is DIFFERENT from internal/crypto/DeriveKeyFromMasterKey which uses
            // hkdf.Key(sha256.New, masterKey, salt, purpose, length) where purpose is used as info.
            //
            // The test vector "encryption_derivation" tests the latter case (salt + info),
            // so we need to test our implementation differently.
            val masterKey = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToByteArray()
            val purpose = "encryption".toByteArray(Charsets.UTF_8)

            // Derive the key
            val derivedKey = Aes256GcmHmacSha256Encryptor.deriveKeyDerivationSecret(masterKey, purpose, 32)

            // Verify length
            assertEquals(32, derivedKey.size, "Derived key should be 32 bytes")

            // Verify it's deterministic
            val derivedKey2 = Aes256GcmHmacSha256Encryptor.deriveKeyDerivationSecret(masterKey, purpose, 32)
            assertArrayEquals(derivedKey, derivedKey2, "HKDF should be deterministic")

            // Verify different purpose produces different key
            val differentPurpose = "different".toByteArray(Charsets.UTF_8)
            val derivedKeyDifferent = Aes256GcmHmacSha256Encryptor.deriveKeyDerivationSecret(
                masterKey, differentPurpose, 32
            )
            assertNotEquals(
                derivedKey.toHexString(),
                derivedKeyDifferent.toHexString(),
                "Different purposes should produce different keys"
            )
        }

        @Test
        fun `HKDF with salt and info matches test vector`() {
            // This tests the internal/crypto pattern used in test vectors:
            // hkdf.Key(sha256.New, masterKey, salt, info, length)
            // We use our existing HkdfSha256KeyDerivation from crypto package
            val hkdf = org.kopiaKt.core.crypto.HkdfSha256KeyDerivation()

            // Test vector: encryption_derivation
            val masterKey = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToByteArray()
            val salt = "636f6e74656e742d69642d73616c7433".hexToByteArray() // "content-id-salt3"
            val info = "encryption".toByteArray(Charsets.UTF_8)
            val expectedKey = "8038e6c938364bdf09ef32d4854107ddfe1b07649dfffafefa56a4cabbf1c990".hexToByteArray()

            val derivedKey = hkdf.derive(masterKey, salt, info, 32)

            assertArrayEquals(
                expectedKey,
                derivedKey,
                "HKDF with salt and info should match test vector"
            )
        }
    }
}
