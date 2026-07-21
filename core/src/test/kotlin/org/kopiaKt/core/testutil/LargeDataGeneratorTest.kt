package org.kopiaKt.core.testutil

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LargeDataGeneratorTest {

    @Nested
    @DisplayName("generate")
    inner class GenerateTests {
        @Test
        fun `should generate deterministic data`() {
            val data1 = LargeDataGenerator.generate(1000, seed = 123L)
            val data2 = LargeDataGenerator.generate(1000, seed = 123L)
            assertArrayEquals(data1, data2)
        }

        @Test
        fun `should generate data of correct size`() {
            val data = LargeDataGenerator.generate(12345)
            assertEquals(12345, data.size)
        }

        @Test
        fun `different seeds produce different data`() {
            val data1 = LargeDataGenerator.generate(100, seed = 1L)
            val data2 = LargeDataGenerator.generate(100, seed = 2L)
            assertFalse(data1.contentEquals(data2))
        }

        @Test
        fun `should generate empty array for size 0`() {
            val data = LargeDataGenerator.generate(0)
            assertEquals(0, data.size)
        }
    }

    @Nested
    @DisplayName("sha256")
    inner class Sha256Tests {
        @Test
        fun `should compute known SHA-256 hash`() {
            // SHA-256 of empty byte array
            val hash = LargeDataGenerator.sha256(ByteArray(0))
            assertEquals(32, hash.size)
            // Known SHA-256 of empty input
            val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            assertEquals(expected, hash.toHexString())
        }

        @Test
        fun `should compute consistent hash for same data`() {
            val data = LargeDataGenerator.generate(10000, seed = 42L)
            val hash1 = LargeDataGenerator.sha256(data)
            val hash2 = LargeDataGenerator.sha256(data)
            assertArrayEquals(hash1, hash2)
        }
    }

    @Nested
    @DisplayName("generateFile and sha256(Path)")
    inner class FileTests {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `should generate file with correct hash`() {
            val path = tempDir.resolve("test.bin")
            val hash = LargeDataGenerator.generateFile(path, 10000, seed = 42L)

            // Verify file exists and has correct size
            assertTrue(Files.exists(path))
            assertEquals(10000L, Files.size(path))

            // Verify hash matches
            val fileHash = LargeDataGenerator.sha256(path)
            assertArrayEquals(hash, fileHash)
        }

        @Test
        fun `should generate file matching in-memory generation`() {
            val path = tempDir.resolve("test.bin")
            LargeDataGenerator.generateFile(path, 5000, seed = 99L)

            val fileData = Files.readAllBytes(path)
            val memData = LargeDataGenerator.generate(5000, seed = 99L)
            assertArrayEquals(memData, fileData)
        }

        @Test
        fun `should handle empty file`() {
            val path = tempDir.resolve("empty.bin")
            val hash = LargeDataGenerator.generateFile(path, 0, seed = 42L)
            assertEquals(0L, Files.size(path))

            val expectedHash = LargeDataGenerator.sha256(ByteArray(0))
            assertArrayEquals(expectedHash, hash)
        }
    }

    @Nested
    @DisplayName("compareFiles")
    inner class CompareFilesTests {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `should return -1 for identical files`() {
            val file1 = tempDir.resolve("a.bin")
            val file2 = tempDir.resolve("b.bin")
            LargeDataGenerator.generateFile(file1, 1000, seed = 42L)
            LargeDataGenerator.generateFile(file2, 1000, seed = 42L)

            assertEquals(-1L, LargeDataGenerator.compareFiles(file1, file2))
        }

        @Test
        fun `should detect mismatch from different seeds`() {
            val file1 = tempDir.resolve("a.bin")
            val file2 = tempDir.resolve("b.bin")
            LargeDataGenerator.generateFile(file1, 1000, seed = 1L)
            LargeDataGenerator.generateFile(file2, 1000, seed = 2L)

            val offset = LargeDataGenerator.compareFiles(file1, file2)
            assertTrue(offset >= 0)
        }

        @Test
        fun `should detect size difference`() {
            val file1 = tempDir.resolve("a.bin")
            val file2 = tempDir.resolve("b.bin")
            LargeDataGenerator.generateFile(file1, 1000, seed = 42L)
            LargeDataGenerator.generateFile(file2, 500, seed = 42L)

            val offset = LargeDataGenerator.compareFiles(file1, file2)
            assertEquals(500L, offset)
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
