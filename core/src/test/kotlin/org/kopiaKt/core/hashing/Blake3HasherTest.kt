package org.kopiaKt.core.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kopiaKt.core.testvectors.HashTestCase
import org.kopiaKt.core.testvectors.TestVectorLoader
import org.kopiaKt.core.testvectors.TestVectors
import org.kopiaKt.core.testvectors.toHexString

/**
 * Tests for BLAKE3 hash implementation using Go-generated test vectors.
 *
 * These tests verify byte-exact compatibility with the Go Kopia implementation.
 * Note: BLAKE3 in Kopia uses key derivation for short keys (< 32 bytes).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Blake3HasherTest {

    private lateinit var vectors: TestVectors

    @BeforeAll
    fun loadTestVectors() {
        vectors = TestVectorLoader.load()
    }

    @Test
    fun `hasher should report correct algorithm`() {
        val factory = DefaultContentHasherFactory()
        val hasher = factory.create(HashAlgorithm.BLAKE3_256, ByteArray(32))
        assertEquals(HashAlgorithm.BLAKE3_256, hasher.algorithm)
    }

    @Test
    fun `hasher should produce 32 byte output`() {
        val factory = DefaultContentHasherFactory()
        val hasher = factory.create(HashAlgorithm.BLAKE3_256, ByteArray(32))
        assertEquals(32, hasher.hashSize)
        val result = hasher.hashContent("test".toByteArray())
        assertEquals(32, result.size)
    }

    @Test
    @DisplayName("empty input with empty secret produces correct hash")
    fun emptyInputWithEmptySecret() {
        val testCase = findBlake3TestCase("empty_with_empty_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("empty input with short secret produces correct hash")
    fun emptyInputWithShortSecret() {
        val testCase = findBlake3TestCase("empty_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("empty input with 32byte secret produces correct hash")
    fun emptyInputWith32ByteSecret() {
        val testCase = findBlake3TestCase("empty_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("single byte with empty secret produces correct hash")
    fun singleByteWithEmptySecret() {
        val testCase = findBlake3TestCase("single_byte_with_empty_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("single byte with short secret produces correct hash")
    fun singleByteWithShortSecret() {
        val testCase = findBlake3TestCase("single_byte_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("single byte with 32byte secret produces correct hash")
    fun singleByteWith32ByteSecret() {
        val testCase = findBlake3TestCase("single_byte_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("hello world with empty secret produces correct hash")
    fun helloWorldWithEmptySecret() {
        val testCase = findBlake3TestCase("hello_world_with_empty_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("hello world with short secret produces correct hash")
    fun helloWorldWithShortSecret() {
        val testCase = findBlake3TestCase("hello_world_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("hello world with 32byte secret produces correct hash")
    fun helloWorldWith32ByteSecret() {
        val testCase = findBlake3TestCase("hello_world_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("binary data with empty secret produces correct hash")
    fun binaryDataWithEmptySecret() {
        val testCase = findBlake3TestCase("binary_data_with_empty_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("binary data with short secret produces correct hash")
    fun binaryDataWithShortSecret() {
        val testCase = findBlake3TestCase("binary_data_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("binary data with 32byte secret produces correct hash")
    fun binaryDataWith32ByteSecret() {
        val testCase = findBlake3TestCase("binary_data_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("kilobyte data with empty secret produces correct hash")
    fun kilobyteDataWithEmptySecret() {
        val testCase = findBlake3TestCase("kilobyte_with_empty_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("kilobyte data with short secret produces correct hash")
    fun kilobyteDataWithShortSecret() {
        val testCase = findBlake3TestCase("kilobyte_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("kilobyte data with 32byte secret produces correct hash")
    fun kilobyteDataWith32ByteSecret() {
        val testCase = findBlake3TestCase("kilobyte_with_32byte_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("repeated pattern with empty secret produces correct hash")
    fun repeatedPatternWithEmptySecret() {
        val testCase = findBlake3TestCase("repeated_pattern_with_empty_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("repeated pattern with short secret produces correct hash")
    fun repeatedPatternWithShortSecret() {
        val testCase = findBlake3TestCase("repeated_pattern_with_short_secret")
        assertHashMatches(testCase)
    }

    @Test
    @DisplayName("repeated pattern with 32byte secret produces correct hash")
    fun repeatedPatternWith32ByteSecret() {
        val testCase = findBlake3TestCase("repeated_pattern_with_32byte_secret")
        assertHashMatches(testCase)
    }

    private fun findBlake3TestCase(name: String): HashTestCase {
        val testCase = vectors.hash.blake3256.find { it.name == name }
        assertNotNull(testCase, "Test case '$name' not found in vectors")
        return testCase!!
    }

    private fun assertHashMatches(testCase: HashTestCase) {
        val factory = DefaultContentHasherFactory()
        val secret = testCase.secretBytes ?: ByteArray(0)
        val hasher = factory.create(HashAlgorithm.BLAKE3_256, secret)

        val result = hasher.hashContent(testCase.input)

        assertEquals(
            testCase.outputHex,
            result.toHexString(),
            "Hash mismatch for test case '${testCase.name}'"
        )
    }
}
