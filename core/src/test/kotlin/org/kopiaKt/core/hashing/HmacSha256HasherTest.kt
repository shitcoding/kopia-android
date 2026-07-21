package org.kopiaKt.core.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kopiaKt.core.testvectors.HmacTestCase
import org.kopiaKt.core.testvectors.TestVectorLoader
import org.kopiaKt.core.testvectors.TestVectors
import org.kopiaKt.core.testvectors.toHexString

/**
 * Tests for HMAC-SHA256 hash implementation using Go-generated test vectors.
 *
 * These tests verify byte-exact compatibility with the Go Kopia implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HmacSha256HasherTest {

    private lateinit var vectors: TestVectors

    @BeforeAll
    fun loadTestVectors() {
        vectors = TestVectorLoader.load()
    }

    @Test
    fun `hasher should report correct algorithm`() {
        val factory = DefaultContentHasherFactory()
        val hasher = factory.create(HashAlgorithm.HMAC_SHA256_128, "secret".toByteArray())
        assertEquals(HashAlgorithm.HMAC_SHA256_128, hasher.algorithm)
    }

    @Test
    fun `hasher should produce 16 byte output for truncated variant`() {
        val factory = DefaultContentHasherFactory()
        val hasher = factory.create(HashAlgorithm.HMAC_SHA256_128, "secret".toByteArray())
        assertEquals(16, hasher.hashSize)
        val result = hasher.hashContent("test".toByteArray())
        assertEquals(16, result.size)
    }

    @Test
    @DisplayName("single byte with short secret produces correct hash")
    fun singleByteWithShortSecret() {
        val testCase = findHmacSha256TestCase("single_byte_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("single byte with 32byte secret produces correct hash")
    fun singleByteWith32ByteSecret() {
        val testCase = findHmacSha256TestCase("single_byte_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("hello world with short secret produces correct hash")
    fun helloWorldWithShortSecret() {
        val testCase = findHmacSha256TestCase("hello_world_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("hello world with 32byte secret produces correct hash")
    fun helloWorldWith32ByteSecret() {
        val testCase = findHmacSha256TestCase("hello_world_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("binary data with short secret produces correct hash")
    fun binaryDataWithShortSecret() {
        val testCase = findHmacSha256TestCase("binary_data_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("binary data with 32byte secret produces correct hash")
    fun binaryDataWith32ByteSecret() {
        val testCase = findHmacSha256TestCase("binary_data_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("kilobyte data with short secret produces correct hash")
    fun kilobyteDataWithShortSecret() {
        val testCase = findHmacSha256TestCase("kilobyte_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("kilobyte data with 32byte secret produces correct hash")
    fun kilobyteDataWith32ByteSecret() {
        val testCase = findHmacSha256TestCase("kilobyte_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("repeated pattern with short secret produces correct hash")
    fun repeatedPatternWithShortSecret() {
        val testCase = findHmacSha256TestCase("repeated_pattern_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("repeated pattern with 32byte secret produces correct hash")
    fun repeatedPatternWith32ByteSecret() {
        val testCase = findHmacSha256TestCase("repeated_pattern_with_32byte_secret")
        assertHashMatches(testCase)
    }

    private fun findHmacSha256TestCase(name: String): HmacTestCase {
        val testCase = vectors.hash.hmacSha256.find { it.name == name }
        assertNotNull(testCase, "Test case '$name' not found in vectors")
        return testCase!!
    }

    private fun assertHashMatches(testCase: HmacTestCase) {
        val factory = DefaultContentHasherFactory()
        val hasher = factory.create(HashAlgorithm.HMAC_SHA256_128, testCase.key)

        val result = hasher.hashContent(testCase.input)

        // Note: Test vectors have full 32-byte HMAC, we truncate to 16 bytes
        val expectedTruncated = testCase.output.copyOf(16)

        assertEquals(
            expectedTruncated.toHexString(),
            result.toHexString(),
            "Hash mismatch for test case '${testCase.name}'",
        )
    }
}
