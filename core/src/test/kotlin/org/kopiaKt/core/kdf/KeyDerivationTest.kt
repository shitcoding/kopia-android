package org.kopiaKt.core.kdf

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.kopiaKt.core.testvectors.HkdfTestCase
import org.kopiaKt.core.testvectors.Pbkdf2TestCase
import org.kopiaKt.core.testvectors.ScryptTestCase
import org.kopiaKt.core.testvectors.TestVectorLoader
import org.kopiaKt.core.testvectors.TestVectors
import org.kopiaKt.core.testvectors.toHexString

/**
 * Tests for key derivation functions using Go-generated test vectors.
 *
 * These tests verify byte-exact compatibility with Go Kopia's
 * key derivation implementations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyDerivationTest {

    private lateinit var vectors: TestVectors

    @BeforeAll
    fun loadTestVectors() {
        vectors = TestVectorLoader.load()
    }

    @Nested
    @DisplayName("PBKDF2-HMAC-SHA256")
    inner class Pbkdf2Tests {

        @Test
        @DisplayName("simple password derives correct key")
        fun simplePassword() {
            val testCase = findPbkdf2TestCase("simple_password")
            assertPbkdf2Matches(testCase)
        }

        @Test
        @DisplayName("empty password derives correct key")
        fun emptyPassword() {
            val testCase = findPbkdf2TestCase("empty_password")
            assertPbkdf2Matches(testCase)
        }

        @Test
        @DisplayName("unicode password derives correct key")
        fun unicodePassword() {
            val testCase = findPbkdf2TestCase("unicode_password")
            assertPbkdf2Matches(testCase)
        }

        @Test
        @DisplayName("long password derives correct key")
        fun longPassword() {
            val testCase = findPbkdf2TestCase("long_password")
            assertPbkdf2Matches(testCase)
        }

        @Test
        @DisplayName("1000 iterations derives correct key")
        fun lowIterations() {
            val testCase = findPbkdf2TestCase("simple_password_1000iter")
            assertPbkdf2Matches(testCase)
        }

        private fun findPbkdf2TestCase(name: String): Pbkdf2TestCase {
            val testCase = vectors.keyDerivation.pbkdf2.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in vectors")
            return testCase!!
        }

        private fun assertPbkdf2Matches(testCase: Pbkdf2TestCase) {
            val result = KeyDerivation.pbkdf2Sha256(
                password = testCase.password,
                salt = testCase.salt,
                iterations = testCase.iterations,
                keyLength = testCase.keyLen
            )

            assertEquals(
                testCase.outputHex,
                result.toHexString(),
                "PBKDF2 output mismatch for test case '${testCase.name}'"
            )
        }
    }

    @Nested
    @DisplayName("Scrypt")
    inner class ScryptTests {

        @Test
        @DisplayName("simple password derives correct key")
        fun simplePassword() {
            val testCase = findScryptTestCase("simple_password")
            assertScryptMatches(testCase)
        }

        @Test
        @DisplayName("empty password derives correct key")
        fun emptyPassword() {
            val testCase = findScryptTestCase("empty_password")
            assertScryptMatches(testCase)
        }

        @Test
        @DisplayName("low N parameter derives correct key")
        fun lowN() {
            val testCase = findScryptTestCase("simple_password_lowN")
            assertScryptMatches(testCase)
        }

        private fun findScryptTestCase(name: String): ScryptTestCase {
            val testCase = vectors.keyDerivation.scrypt.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in vectors")
            return testCase!!
        }

        private fun assertScryptMatches(testCase: ScryptTestCase) {
            val result = KeyDerivation.scrypt(
                password = testCase.password,
                salt = testCase.salt,
                n = testCase.n,
                r = testCase.r,
                p = testCase.p,
                keyLength = testCase.keyLen
            )

            assertEquals(
                testCase.outputHex,
                result.toHexString(),
                "Scrypt output mismatch for test case '${testCase.name}'"
            )
        }
    }

    @Nested
    @DisplayName("HKDF-SHA256")
    inner class HkdfTests {

        @Test
        @DisplayName("AES key derivation produces correct key")
        fun aesKeyDerivation() {
            val testCase = findHkdfTestCase("aes_key_derivation")
            assertHkdfMatches(testCase)
        }

        @Test
        @DisplayName("checksum derivation produces correct key")
        fun checksumDerivation() {
            val testCase = findHkdfTestCase("checksum_derivation")
            assertHkdfMatches(testCase)
        }

        @Test
        @DisplayName("encryption derivation produces correct key")
        fun encryptionDerivation() {
            val testCase = findHkdfTestCase("encryption_derivation")
            assertHkdfMatches(testCase)
        }

        private fun findHkdfTestCase(name: String): HkdfTestCase {
            val testCase = vectors.keyDerivation.hkdf.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in vectors")
            return testCase!!
        }

        private fun assertHkdfMatches(testCase: HkdfTestCase) {
            val result = KeyDerivation.hkdfSha256(
                secret = testCase.master,
                salt = testCase.salt,
                info = testCase.info,
                length = testCase.length
            )

            assertEquals(
                testCase.outputHex,
                result.toHexString(),
                "HKDF output mismatch for test case '${testCase.name}'"
            )
        }
    }

    @Nested
    @DisplayName("Basic Properties")
    inner class BasicPropertyTests {

        @Test
        @DisplayName("PBKDF2 produces correct length output")
        fun pbkdf2ProducesCorrectLength() {
            val result = KeyDerivation.pbkdf2Sha256(
                password = "test",
                salt = "salt".toByteArray(),
                iterations = 1000,
                keyLength = 64
            )
            assertEquals(64, result.size)
        }

        @Test
        @DisplayName("HKDF produces correct length output")
        fun hkdfProducesCorrectLength() {
            val result = KeyDerivation.hkdfSha256(
                secret = "secret".toByteArray(),
                salt = "salt".toByteArray(),
                info = "info",
                length = 64
            )
            assertEquals(64, result.size)
        }

        @Test
        @DisplayName("Scrypt produces correct length output")
        fun scryptProducesCorrectLength() {
            val result = KeyDerivation.scrypt(
                password = "test",
                salt = "salt".toByteArray(),
                n = 1024,
                r = 8,
                p = 1,
                keyLength = 64
            )
            assertEquals(64, result.size)
        }

        @Test
        @DisplayName("Different passwords produce different keys")
        fun differentPasswordsDifferentKeys() {
            val salt = "salt".toByteArray()
            val key1 = KeyDerivation.pbkdf2Sha256("password1", salt, 1000, 32)
            val key2 = KeyDerivation.pbkdf2Sha256("password2", salt, 1000, 32)
            assert(!key1.contentEquals(key2)) { "Different passwords should produce different keys" }
        }

        @Test
        @DisplayName("Different salts produce different keys")
        fun differentSaltsDifferentKeys() {
            val key1 = KeyDerivation.pbkdf2Sha256("password", "salt1".toByteArray(), 1000, 32)
            val key2 = KeyDerivation.pbkdf2Sha256("password", "salt2".toByteArray(), 1000, 32)
            assert(!key1.contentEquals(key2)) { "Different salts should produce different keys" }
        }

        @Test
        @DisplayName("Same inputs produce same output (deterministic)")
        fun deterministicOutput() {
            val salt = "salt".toByteArray()
            val key1 = KeyDerivation.pbkdf2Sha256("password", salt, 1000, 32)
            val key2 = KeyDerivation.pbkdf2Sha256("password", salt, 1000, 32)
            assertArrayEquals(key1, key2, "Same inputs should produce same output")
        }
    }
}
