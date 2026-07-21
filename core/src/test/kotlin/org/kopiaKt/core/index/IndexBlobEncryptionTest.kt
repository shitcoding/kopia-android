package org.kopiaKt.core.index

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.hexToByteArray

/**
 * Tests for IndexBlobEncryption.
 */
class IndexBlobEncryptionTest {

    // ===== Content ID Derivation Tests =====

    @Test
    fun `deriveContentIdFromBlobId should handle standard index blob ID`() {
        val blobId = BlobId("n1234567890abcdef1234567890abcdef")

        val contentId = IndexBlobEncryption.deriveContentIdFromBlobId(blobId)

        assertEquals("1234567890abcdef1234567890abcdef", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should return empty for short IDs after prefix removal`() {
        // These IDs have only 16 hex chars after prefix removal, which is less than
        // the required 32 hex chars (16 bytes). The IV-based derivation returns zeros,
        // which maps to ContentId.Empty.
        val nBlobId = BlobId("n1234567890abcdef")
        val pBlobId = BlobId("p1234567890abcdef")
        val qBlobId = BlobId("q1234567890abcdef")

        assertEquals("", IndexBlobEncryption.deriveContentIdFromBlobId(nBlobId).toString())
        assertEquals("", IndexBlobEncryption.deriveContentIdFromBlobId(pBlobId).toString())
        assertEquals("", IndexBlobEncryption.deriveContentIdFromBlobId(qBlobId).toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should handle blob ID with dash suffix`() {
        val blobId = BlobId("n1234567890abcdef1234567890abcdef-s123-suffix")

        val contentId = IndexBlobEncryption.deriveContentIdFromBlobId(blobId)

        // Should only take part before the first dash
        assertEquals("1234567890abcdef1234567890abcdef", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should take last 32 hex chars for long IDs`() {
        val blobId = BlobId("naaaa1111222233334444555566667777888899990000")

        val contentId = IndexBlobEncryption.deriveContentIdFromBlobId(blobId)

        // Should take last 32 hex chars (16 bytes = AES block size)
        assertEquals("33334444555566667777888899990000", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should return empty for short blob ID`() {
        val blobId = BlobId("n1234")

        val contentId = IndexBlobEncryption.deriveContentIdFromBlobId(blobId)

        // Short IDs (< 32 hex chars) result in all-zero IV bytes,
        // which maps to ContentId.Empty
        assertEquals("", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should return empty for short uppercase hex`() {
        val blobId = BlobId("nABCDEF1234567890")

        val contentId = IndexBlobEncryption.deriveContentIdFromBlobId(blobId)

        // 16 hex chars after prefix removal, < 32 required -> empty
        assertEquals("", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should return empty for short filtered hex`() {
        val blobId = BlobId("n12-34-56-78")

        val contentId = IndexBlobEncryption.deriveContentIdFromBlobId(blobId)

        // Before first dash: "n12" -> hex chars "12" -> only 2 hex chars, < 32 -> empty
        assertEquals("", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should return empty for short ID without letter prefix`() {
        // 16 hex chars without prefix, still < 32 required -> empty
        val blobId = BlobId("1234567890abcdef")

        val contentId = IndexBlobEncryption.deriveContentIdFromBlobId(blobId)

        assertEquals("", contentId.toString())
    }

    // ===== IV Bytes Extension Tests =====

    @Test
    fun `toIvBytes should return 16-byte IV`() {
        val blobId = BlobId("n1234567890abcdef1234567890abcdef")

        val ivBytes = blobId.toIvBytes()

        assertEquals(16, ivBytes.size)
    }

    @Test
    fun `toIvBytes should return all zeros for short IDs`() {
        val blobId = BlobId("n1234")

        val ivBytes = blobId.toIvBytes()

        assertEquals(16, ivBytes.size)
        // Short IDs (< 32 hex chars) produce all-zero IV bytes
        for (i in 0 until 16) {
            assertEquals(0.toByte(), ivBytes[i], "Byte at index $i should be zero")
        }
    }

    @Test
    fun `toIvBytes should use last 16 bytes for long IDs`() {
        val blobId = BlobId("naaaa1111222233334444555566667777888899990000")

        val ivBytes = blobId.toIvBytes()

        assertEquals(16, ivBytes.size)
        // Should be last 32 hex chars = 16 bytes: "33334444555566667777888899990000"
        val expected = byteArrayOf(
            0x33, 0x33, 0x44, 0x44, 0x55, 0x55, 0x66, 0x66,
            0x77, 0x77, 0x88.toByte(), 0x88.toByte(),
            0x99.toByte(), 0x99.toByte(), 0x00, 0x00,
        )
        assertArrayEquals(expected, ivBytes)
    }

    // ===== Unencrypted Instance Tests =====

    @Test
    fun `unencrypted should return instance with encryption disabled`() {
        val encryption = IndexBlobEncryption.unencrypted()

        assertFalse(encryption.isEncryptionEnabled)
        assertEquals(0, encryption.overhead)
    }

    @Test
    fun `unencrypted encrypt should return data unchanged`() = kotlinx.coroutines.runBlocking {
        val encryption = IndexBlobEncryption.unencrypted()
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val blobId = BlobId("ntest")

        val result = encryption.encrypt(data, blobId)

        assertArrayEquals(data, result)
    }

    @Test
    fun `unencrypted decrypt should return data unchanged`() = kotlinx.coroutines.runBlocking {
        val encryption = IndexBlobEncryption.unencrypted()
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val blobId = BlobId("ntest")

        val result = encryption.decrypt(data, blobId)

        assertArrayEquals(data, result)
    }

    // ===== Encryption with Encryptor Tests =====

    @Test
    fun `isEncryptionEnabled should return true when encryptor provided`() {
        val mockEncryptor = MockEncryptor()
        val encryption = IndexBlobEncryption(mockEncryptor)

        assertTrue(encryption.isEncryptionEnabled)
    }

    @Test
    fun `overhead should return encryptor overhead`() {
        val mockEncryptor = MockEncryptor(overhead = 28)
        val encryption = IndexBlobEncryption(mockEncryptor)

        assertEquals(28, encryption.overhead)
    }

    @Test
    fun `encrypt should use encryptor when provided`() = kotlinx.coroutines.runBlocking {
        val mockEncryptor = MockEncryptor()
        val encryption = IndexBlobEncryption(mockEncryptor)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        // Must have >= 32 hex chars after prefix removal so IV derivation works
        val blobId = BlobId("naaaa1111222233334444555566667777")

        encryption.encrypt(data, blobId)

        assertTrue(mockEncryptor.encryptCalled)
        assertEquals("aaaa1111222233334444555566667777", mockEncryptor.lastContentId?.toString())
    }

    @Test
    fun `decrypt should use encryptor decryptWithRawId when provided`() = kotlinx.coroutines.runBlocking {
        val mockEncryptor = MockEncryptor()
        val encryption = IndexBlobEncryption(mockEncryptor)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        // Must have >= 32 hex chars so IV derivation produces non-zero bytes
        val blobId = BlobId("nbbbb6666777788889999000011112222")

        encryption.decrypt(data, blobId)

        // decrypt() uses decryptWithRawId (not decrypt with ContentId)
        assertTrue(mockEncryptor.decryptCalled)
        // Verify the raw IV bytes were passed correctly
        val expectedIvHex = "bbbb6666777788889999000011112222"
        assertArrayEquals(
            expectedIvHex.hexToByteArray(),
            mockEncryptor.lastRawIdBytes,
        )
    }

    // ===== Mock Encryptor =====

    private class MockEncryptor(
        override val overhead: Int = 16,
    ) : org.kopiaKt.core.encryption.Encryptor {
        override val algorithm = org.kopiaKt.core.encryption.EncryptionAlgorithm.AES256_GCM_HMAC_SHA256

        var encryptCalled = false
        var decryptCalled = false
        var lastContentId: org.kopiaKt.core.content.ContentId? = null
        var lastRawIdBytes: ByteArray? = null

        override suspend fun encrypt(
            plaintext: ByteArray,
            contentId: org.kopiaKt.core.content.ContentId,
        ): ByteArray {
            encryptCalled = true
            lastContentId = contentId
            return plaintext + ByteArray(overhead) // Append mock overhead
        }

        override suspend fun decrypt(
            ciphertext: ByteArray,
            contentId: org.kopiaKt.core.content.ContentId,
        ): ByteArray {
            decryptCalled = true
            lastContentId = contentId
            // Remove mock overhead
            return if (ciphertext.size > overhead) {
                ciphertext.copyOfRange(0, ciphertext.size - overhead)
            } else {
                ciphertext
            }
        }

        override suspend fun decryptWithRawId(
            ciphertext: ByteArray,
            contentIdBytes: ByteArray,
        ): ByteArray {
            decryptCalled = true
            lastRawIdBytes = contentIdBytes
            // Remove mock overhead
            return if (ciphertext.size > overhead) {
                ciphertext.copyOfRange(0, ciphertext.size - overhead)
            } else {
                ciphertext
            }
        }

        override suspend fun encryptWithRawId(
            plaintext: ByteArray,
            contentIdBytes: ByteArray,
        ): ByteArray {
            encryptCalled = true
            lastRawIdBytes = contentIdBytes
            return plaintext + ByteArray(overhead) // Append mock overhead
        }
    }
}
