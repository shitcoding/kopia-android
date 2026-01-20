package org.kopiaKt.core.hashing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kopiaKt.core.testvectors.HashTestCase
import org.kopiaKt.core.testvectors.TestVectorLoader
import org.kopiaKt.core.testvectors.TestVectors
import org.kopiaKt.core.testvectors.toHexString

/**
 * Tests for BLAKE2B hash implementations using Go-generated test vectors.
 *
 * These tests verify byte-exact compatibility with the Go Kopia implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Blake2bHasherTest {

    private lateinit var vectors: TestVectors

    @BeforeAll
    fun loadTestVectors() {
        vectors = TestVectorLoader.load()
    }

    @Nested
    @DisplayName("BLAKE2B-256-128 (truncated to 128 bits)")
    inner class Blake2b256128Tests {

        @Test
        fun `hasher should report correct algorithm`() {
            val factory = DefaultContentHasherFactory()
            val hasher = factory.create(HashAlgorithm.BLAKE2B_256_128, ByteArray(0))
            assertEquals(HashAlgorithm.BLAKE2B_256_128, hasher.algorithm)
        }

        @Test
        fun `hasher should produce 16 byte output`() {
            val factory = DefaultContentHasherFactory()
            val hasher = factory.create(HashAlgorithm.BLAKE2B_256_128, ByteArray(0))
            assertEquals(16, hasher.hashSize)
            val result = hasher.hashContent("test".toByteArray())
            assertEquals(16, result.size)
        }

        @Test
        fun `empty input with empty secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("empty_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `empty input with short secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("empty_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `empty input with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("empty_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `single byte with empty secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("single_byte_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `single byte with short secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("single_byte_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `single byte with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("single_byte_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `hello world with empty secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("hello_world_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `hello world with short secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("hello_world_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `hello world with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("hello_world_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `binary data with empty secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("binary_data_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `binary data with short secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("binary_data_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `binary data with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("binary_data_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `kilobyte data with empty secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("kilobyte_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `kilobyte data with short secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("kilobyte_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `kilobyte data with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("kilobyte_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `repeated pattern with empty secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("repeated_pattern_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `repeated pattern with short secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("repeated_pattern_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        @Test
        fun `repeated pattern with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256128TestCase("repeated_pattern_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_128, testCase)
        }

        private fun findBlake2b256128TestCase(name: String): HashTestCase {
            val testCase = vectors.hash.blake2b256128.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in vectors")
            return testCase!!
        }
    }

    @Nested
    @DisplayName("BLAKE2B-256 (full 256 bits)")
    inner class Blake2b256Tests {

        @Test
        fun `hasher should report correct algorithm`() {
            val factory = DefaultContentHasherFactory()
            val hasher = factory.create(HashAlgorithm.BLAKE2B_256_256, ByteArray(0))
            assertEquals(HashAlgorithm.BLAKE2B_256_256, hasher.algorithm)
        }

        @Test
        fun `hasher should produce 32 byte output`() {
            val factory = DefaultContentHasherFactory()
            val hasher = factory.create(HashAlgorithm.BLAKE2B_256_256, ByteArray(0))
            assertEquals(32, hasher.hashSize)
            val result = hasher.hashContent("test".toByteArray())
            assertEquals(32, result.size)
        }

        @Test
        fun `empty input with empty secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("empty_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `empty input with short secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("empty_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `empty input with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("empty_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `single byte with empty secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("single_byte_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `single byte with short secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("single_byte_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `single byte with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("single_byte_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `hello world with empty secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("hello_world_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `hello world with short secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("hello_world_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `hello world with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("hello_world_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `binary data with empty secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("binary_data_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `binary data with short secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("binary_data_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `binary data with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("binary_data_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `kilobyte data with empty secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("kilobyte_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `kilobyte data with short secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("kilobyte_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `kilobyte data with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("kilobyte_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `repeated pattern with empty secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("repeated_pattern_with_empty_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `repeated pattern with short secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("repeated_pattern_with_short_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        @Test
        fun `repeated pattern with 32byte secret produces correct hash`() {
            val testCase = findBlake2b256TestCase("repeated_pattern_with_32byte_secret")
            assertHashMatches(HashAlgorithm.BLAKE2B_256_256, testCase)
        }

        private fun findBlake2b256TestCase(name: String): HashTestCase {
            val testCase = vectors.hash.blake2b256.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in vectors")
            return testCase!!
        }
    }

    private fun assertHashMatches(algorithm: HashAlgorithm, testCase: HashTestCase) {
        val factory = DefaultContentHasherFactory()
        val secret = testCase.secretBytes ?: ByteArray(0)
        val hasher = factory.create(algorithm, secret)

        val result = hasher.hashContent(testCase.input)

        assertEquals(
            testCase.outputHex,
            result.toHexString(),
            "Hash mismatch for test case '${testCase.name}'"
        )
    }
}
