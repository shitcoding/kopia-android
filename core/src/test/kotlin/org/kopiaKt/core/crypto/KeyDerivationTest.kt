package org.kopiaKt.core.crypto

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
 * Tests for key derivation implementations using Go-generated test vectors.
 *
 * These tests verify byte-exact compatibility with the Go Kopia implementation for:
 * - PBKDF2-HMAC-SHA256 (password-based key derivation)
 * - Scrypt (memory-hard password-based key derivation)
 * - HKDF-SHA256 (key expansion/derivation)
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
        fun `simple password derives correct key`() {
            val testCase = findPbkdf2TestCase("simple_password")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `empty password derives correct key`() {
            val testCase = findPbkdf2TestCase("empty_password")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `unicode password derives correct key`() {
            val testCase = findPbkdf2TestCase("unicode_password")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `long password derives correct key`() {
            val testCase = findPbkdf2TestCase("long_password")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `simple password with 1000 iterations derives correct key`() {
            val testCase = findPbkdf2TestCase("simple_password_1000iter")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `derive returns key of correct length`() {
            val kdf = Pbkdf2KeyDerivation()
            val result = kdf.derive(
                password = "test".toByteArray(),
                salt = ByteArray(16) { 0x42 },
                iterations = 1000,
                keyLength = 32,
            )
            assertEquals(32, result.size)
        }

        @Test
        fun `derive with different key lengths works`() {
            val kdf = Pbkdf2KeyDerivation()
            val result16 = kdf.derive(
                password = "test".toByteArray(),
                salt = ByteArray(16) { 0x42 },
                iterations = 1000,
                keyLength = 16,
            )
            val result64 = kdf.derive(
                password = "test".toByteArray(),
                salt = ByteArray(16) { 0x42 },
                iterations = 1000,
                keyLength = 64,
            )
            assertEquals(16, result16.size)
            assertEquals(64, result64.size)
        }

        private fun findPbkdf2TestCase(name: String): Pbkdf2TestCase {
            val testCase = vectors.keyDerivation.pbkdf2.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in PBKDF2 vectors")
            return testCase!!
        }

        private fun assertKeyDerivation(testCase: Pbkdf2TestCase) {
            val kdf = Pbkdf2KeyDerivation()
            val result = kdf.derive(
                password = testCase.password.toByteArray(Charsets.UTF_8),
                salt = testCase.salt,
                iterations = testCase.iterations,
                keyLength = testCase.keyLen,
            )

            assertEquals(
                testCase.outputHex,
                result.toHexString(),
                "PBKDF2 key mismatch for test case '${testCase.name}'",
            )
        }
    }

    @Nested
    @DisplayName("Scrypt")
    inner class ScryptTests {

        @Test
        fun `simple password derives correct key`() {
            val testCase = findScryptTestCase("simple_password")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `empty password derives correct key`() {
            val testCase = findScryptTestCase("empty_password")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `simple password with low N derives correct key`() {
            val testCase = findScryptTestCase("simple_password_lowN")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `derive returns key of correct length`() {
            val kdf = ScryptKeyDerivation()
            val result = kdf.derive(
                password = "test".toByteArray(),
                salt = ByteArray(16) { 0x42 },
                n = 1024,
                r = 8,
                p = 1,
                keyLength = 32,
            )
            assertEquals(32, result.size)
        }

        @Test
        fun `derive with different key lengths works`() {
            val kdf = ScryptKeyDerivation()
            val result16 = kdf.derive(
                password = "test".toByteArray(),
                salt = ByteArray(16) { 0x42 },
                n = 1024,
                r = 8,
                p = 1,
                keyLength = 16,
            )
            val result64 = kdf.derive(
                password = "test".toByteArray(),
                salt = ByteArray(16) { 0x42 },
                n = 1024,
                r = 8,
                p = 1,
                keyLength = 64,
            )
            assertEquals(16, result16.size)
            assertEquals(64, result64.size)
        }

        private fun findScryptTestCase(name: String): ScryptTestCase {
            val testCase = vectors.keyDerivation.scrypt.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in Scrypt vectors")
            return testCase!!
        }

        private fun assertKeyDerivation(testCase: ScryptTestCase) {
            val kdf = ScryptKeyDerivation()
            val result = kdf.derive(
                password = testCase.password.toByteArray(Charsets.UTF_8),
                salt = testCase.salt,
                n = testCase.n,
                r = testCase.r,
                p = testCase.p,
                keyLength = testCase.keyLen,
            )

            assertEquals(
                testCase.outputHex,
                result.toHexString(),
                "Scrypt key mismatch for test case '${testCase.name}'",
            )
        }
    }

    @Nested
    @DisplayName("HKDF-SHA256")
    inner class HkdfTests {

        @Test
        fun `aes key derivation produces correct output`() {
            val testCase = findHkdfTestCase("aes_key_derivation")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `checksum derivation produces correct output`() {
            val testCase = findHkdfTestCase("checksum_derivation")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `encryption derivation produces correct output`() {
            val testCase = findHkdfTestCase("encryption_derivation")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `empty salt produces correct output`() {
            val testCase = findHkdfTestCase("empty_salt")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `empty info produces correct output`() {
            val testCase = findHkdfTestCase("empty_info")
            assertKeyDerivation(testCase)
        }

        @Test
        fun `derive returns key of correct length`() {
            val kdf = HkdfSha256KeyDerivation()
            val result = kdf.derive(
                masterKey = ByteArray(32) { it.toByte() },
                salt = ByteArray(16) { 0x42 },
                info = "test".toByteArray(),
                length = 32,
            )
            assertEquals(32, result.size)
        }

        @Test
        fun `derive with different key lengths works`() {
            val kdf = HkdfSha256KeyDerivation()
            val master = ByteArray(32) { it.toByte() }
            val salt = ByteArray(16) { 0x42 }
            val info = "test".toByteArray()

            val result16 = kdf.derive(master, salt, info, 16)
            val result64 = kdf.derive(master, salt, info, 64)

            assertEquals(16, result16.size)
            assertEquals(64, result64.size)
        }

        @Test
        fun `same inputs produce same output`() {
            val kdf = HkdfSha256KeyDerivation()
            val master = ByteArray(32) { it.toByte() }
            val salt = ByteArray(16) { 0x42 }
            val info = "test".toByteArray()

            val result1 = kdf.derive(master, salt, info, 32)
            val result2 = kdf.derive(master, salt, info, 32)

            assertArrayEquals(result1, result2)
        }

        private fun findHkdfTestCase(name: String): HkdfTestCase {
            val testCase = vectors.keyDerivation.hkdf.find { it.name == name }
            assertNotNull(testCase, "Test case '$name' not found in HKDF vectors")
            return testCase!!
        }

        private fun assertKeyDerivation(testCase: HkdfTestCase) {
            val kdf = HkdfSha256KeyDerivation()
            val result = kdf.derive(
                masterKey = testCase.master,
                salt = testCase.salt,
                info = testCase.infoBytes,
                length = testCase.length,
            )

            assertEquals(
                testCase.outputHex,
                result.toHexString(),
                "HKDF key mismatch for test case '${testCase.name}'",
            )
        }
    }

    @Nested
    @DisplayName("KeyDerivationFactory")
    inner class FactoryTests {

        @Test
        fun `factory creates PBKDF2 instance`() {
            val factory = DefaultKeyDerivationFactory()
            val kdf = factory.createPbkdf2()
            assertNotNull(kdf)
        }

        @Test
        fun `factory creates Scrypt instance`() {
            val factory = DefaultKeyDerivationFactory()
            val kdf = factory.createScrypt()
            assertNotNull(kdf)
        }

        @Test
        fun `factory creates HKDF instance`() {
            val factory = DefaultKeyDerivationFactory()
            val kdf = factory.createHkdf()
            assertNotNull(kdf)
        }
    }
}
