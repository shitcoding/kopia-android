package org.kopiaKt.core.testvectors

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Tests that verify the test vectors can be loaded and parsed correctly.
 * This is a foundational test to ensure the test vector infrastructure works.
 */
class TestVectorLoadingTest {

    @Test
    fun `test vectors file can be loaded from resources`() {
        val vectors = assertDoesNotThrow { TestVectorLoader.load() }
        assertThat(vectors.version).isNotEmpty()
        assertThat(vectors.generatedAt).isNotEmpty()
    }

    @Test
    fun `hash vectors are present and parseable`() {
        val vectors = TestVectorLoader.load()

        // BLAKE2B-256-128 vectors
        assertThat(vectors.hash.blake2b256128).isNotEmpty()
        vectors.hash.blake2b256128.forEach { case ->
            assertThat(case.name).isNotEmpty()
            assertThat(case.outputHex).hasLength(32) // 16 bytes = 32 hex chars
            assertDoesNotThrow { case.input }
            assertDoesNotThrow { case.output }
        }

        // BLAKE2B-256 vectors
        assertThat(vectors.hash.blake2b256).isNotEmpty()
        vectors.hash.blake2b256.forEach { case ->
            assertThat(case.outputHex).hasLength(64) // 32 bytes = 64 hex chars
        }

        // BLAKE3-256 vectors
        assertThat(vectors.hash.blake3256).isNotEmpty()
        vectors.hash.blake3256.forEach { case ->
            assertThat(case.outputHex).hasLength(64) // 32 bytes = 64 hex chars
        }

        // BLAKE3-256-128 vectors
        assertThat(vectors.hash.blake3256128).isNotEmpty()
        vectors.hash.blake3256128.forEach { case ->
            assertThat(case.outputHex).hasLength(32) // 16 bytes = 32 hex chars
        }

        // HMAC-SHA256 vectors
        assertThat(vectors.hash.hmacSha256).isNotEmpty()
        vectors.hash.hmacSha256.forEach { case ->
            assertThat(case.outputHex).hasLength(64) // 32 bytes = 64 hex chars
            assertDoesNotThrow { case.key }
        }
    }

    @Test
    fun `key derivation vectors are present and parseable`() {
        val vectors = TestVectorLoader.load()

        // PBKDF2 vectors
        assertThat(vectors.keyDerivation.pbkdf2).isNotEmpty()
        vectors.keyDerivation.pbkdf2.forEach { case ->
            assertThat(case.iterations).isGreaterThan(0)
            assertThat(case.keyLen).isGreaterThan(0)
            assertDoesNotThrow { case.salt }
            assertDoesNotThrow { case.output }
        }

        // Scrypt vectors
        assertThat(vectors.keyDerivation.scrypt).isNotEmpty()
        vectors.keyDerivation.scrypt.forEach { case ->
            assertThat(case.n).isGreaterThan(0)
            assertThat(case.r).isGreaterThan(0)
            assertThat(case.p).isGreaterThan(0)
            assertDoesNotThrow { case.salt }
            assertDoesNotThrow { case.output }
        }

        // HKDF vectors
        assertThat(vectors.keyDerivation.hkdf).isNotEmpty()
        vectors.keyDerivation.hkdf.forEach { case ->
            assertThat(case.length).isGreaterThan(0)
            assertDoesNotThrow { case.master }
            assertDoesNotThrow { case.salt }
            assertDoesNotThrow { case.infoBytes }
            assertDoesNotThrow { case.output }
        }
    }

    @Test
    fun `encryption vectors are present and parseable`() {
        val vectors = TestVectorLoader.load()

        // AES-256-GCM vectors
        assertThat(vectors.encryption.aes256Gcm).isNotEmpty()
        vectors.encryption.aes256Gcm.forEach { case ->
            assertThat(case.keyHex).hasLength(64) // 32 bytes = 64 hex chars
            assertThat(case.nonceHex).hasLength(24) // 12 bytes = 24 hex chars
            assertDoesNotThrow { case.key }
            assertDoesNotThrow { case.nonce }
            assertDoesNotThrow { case.plaintext }
            assertDoesNotThrow { case.ciphertext }
        }
    }

    @Test
    fun `compression vectors are present and parseable`() {
        val vectors = TestVectorLoader.load()

        assertThat(vectors.compression.headers).isNotEmpty()
        vectors.compression.headers.forEach { case ->
            assertThat(case.algorithm).isNotEmpty()
            assertThat(case.headerHex).hasLength(8) // 4 bytes = 8 hex chars
            assertDoesNotThrow { case.header }
        }

        // Verify expected algorithms are present
        val algorithms = vectors.compression.headers.map { it.algorithm }
        assertThat(algorithms).contains("zstd-default")
        assertThat(algorithms).contains("gzip-default")
        assertThat(algorithms).contains("lz4-default")
    }

    @Test
    fun `splitter vectors are present and parseable`() {
        val vectors = TestVectorLoader.load()

        // Buzhash32 vectors
        assertThat(vectors.splitter.buzhash32).isNotEmpty()
        vectors.splitter.buzhash32.forEach { case ->
            assertThat(case.avgSize).isGreaterThan(0)
            assertThat(case.minSize).isGreaterThan(0)
            assertThat(case.maxSize).isGreaterThan(case.minSize)
            assertThat(case.boundaries).isNotEmpty()
            assertDoesNotThrow { case.input }
        }

        // RabinKarp64 vectors
        assertThat(vectors.splitter.rabinkarp64).isNotEmpty()
        vectors.splitter.rabinkarp64.forEach { case ->
            assertThat(case.avgSize).isGreaterThan(0)
            assertDoesNotThrow { case.input }
        }
    }

    @Test
    fun `content ID vectors are present and parseable`() {
        val vectors = TestVectorLoader.load()

        assertThat(vectors.contentId.formation).isNotEmpty()
        vectors.contentId.formation.forEach { case ->
            assertThat(case.contentId).isNotEmpty()
            assertDoesNotThrow { case.hash }
        }
    }

    @Test
    fun `hex conversion works correctly`() {
        // Test empty string
        assertThat("".hexToByteArray()).isEmpty()

        // Test simple conversion
        assertThat("00".hexToByteArray()).isEqualTo(byteArrayOf(0))
        assertThat("ff".hexToByteArray()).isEqualTo(byteArrayOf(-1))
        assertThat("0102".hexToByteArray()).isEqualTo(byteArrayOf(1, 2))

        // Test round-trip
        val original = byteArrayOf(0, 1, 127, -128, -1)
        assertThat(original.toHexString().hexToByteArray()).isEqualTo(original)
    }
}
