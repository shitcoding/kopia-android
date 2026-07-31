package org.kopiaKt.e2e

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.testutil.LargeDataGenerator
import org.kopiaKt.core.testutil.TestRepositoryFactory

/**
 * Verifies that binary files with known byte patterns survive write/read
 * through the repository without any modification.
 *
 * Addresses Go Kopia issue #5049 (CR2 image corruption) by testing that
 * specific binary signatures, edge-case byte values, and problematic
 * patterns are preserved exactly through the content-addressable pipeline.
 */
class BinaryFormatPreservationTest {

    private var repo: DirectRepositoryImpl? = null

    @AfterEach
    fun tearDown() {
        repo?.close()
        repo = null
    }

    /**
     * Write a single binary payload through the repository and read it back.
     * Asserts byte-for-byte equality with the original.
     */
    private suspend fun roundTrip(label: String, data: ByteArray): ByteArray {
        val (repository, _, objectIds) = TestRepositoryFactory.createWithObjects(
            objects = mapOf(label to data),
        )
        repo = repository
        return repository.readObject(objectIds.getValue(label))
    }

    @Nested
    @DisplayName("Known File Headers")
    inner class KnownHeaders {

        @Test
        fun `should preserve file starting with JPEG header FF D8 FF`(): Unit = runTest {
            val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val body = LargeDataGenerator.generate(4093, seed = 1L)
            val data = header + body

            val readBack = roundTrip("jpeg", data)

            assertArrayEquals(data, readBack, "JPEG-header data was modified during round-trip")
        }

        @Test
        fun `should preserve file starting with PDF header 25 50 44 46`(): Unit = runTest {
            val header = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
            val body = LargeDataGenerator.generate(4092, seed = 2L)
            val data = header + body

            val readBack = roundTrip("pdf", data)

            assertArrayEquals(data, readBack, "PDF-header data was modified during round-trip")
        }

        @Test
        fun `should preserve file starting with ZIP header 50 4B 03 04`(): Unit = runTest {
            val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
            val body = LargeDataGenerator.generate(4092, seed = 3L)
            val data = header + body

            val readBack = roundTrip("zip", data)

            assertArrayEquals(data, readBack, "ZIP-header data was modified during round-trip")
        }
    }

    @Nested
    @DisplayName("Byte Value Coverage")
    inner class ByteValueCoverage {

        @Test
        fun `should preserve file with all possible byte values`(): Unit = runTest {
            val data = ByteArray(256) { it.toByte() }

            val readBack = roundTrip("all-bytes", data)

            assertArrayEquals(data, readBack, "All-byte-values data was modified during round-trip")
        }
    }

    @Nested
    @DisplayName("Problematic Patterns")
    inner class ProblematicPatterns {

        @Test
        fun `should preserve file with long runs of null bytes`(): Unit = runTest {
            val nullPrefix = ByteArray(4096) // 4096 zero bytes
            val suffix = LargeDataGenerator.generate(4096, seed = 4L)
            val data = nullPrefix + suffix

            val readBack = roundTrip("null-run", data)

            assertArrayEquals(data, readBack, "Null-byte-run data was modified during round-trip")
        }

        @Test
        fun `should preserve file with alternating FF 00 pattern`(): Unit = runTest {
            val data = ByteArray(4096) { i ->
                if (i % 2 == 0) 0xFF.toByte() else 0x00.toByte()
            }

            val readBack = roundTrip("ff-00-alternating", data)

            assertArrayEquals(
                data,
                readBack,
                "Alternating FF/00 (JPEG escape) pattern was modified during round-trip",
            )
        }
    }
}
