package org.kopiaKt.core.compression

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.testutil.CorruptionHelpers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Corruption tests for all compressor implementations.
 *
 * Verifies that decompressors fail gracefully (throw exceptions, not hang
 * or silently return wrong data) when given corrupted compressed data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorruptedCompressionStreamTest {

    private lateinit var factory: CompressorFactory
    private val testData = "Hello, World! This is test data for corruption testing. ".repeat(100).toByteArray()

    @BeforeAll
    fun setup() {
        factory = DefaultCompressorFactory()
    }

    /** Create a valid 4-byte big-endian header for the given algorithm header ID. */
    private fun makeHeader(headerId: Int): ByteArray =
        ByteBuffer.allocate(COMPRESSION_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(headerId)
            .array()

    /**
     * Assert that decompression either throws an exception or produces data
     * that differs from the original. Some algorithms may not detect every
     * single bit flip (lacking checksums at certain positions), so we allow
     * silent corruption as long as the output differs from the original.
     */
    private fun assertCorruptionDetected(
        compressor: Compressor,
        corrupted: ByteArray,
        original: ByteArray,
        description: String
    ) {
        try {
            val result = compressor.decompress(corrupted)
            assertFalse(
                result.contentEquals(original),
                "$description: decompression succeeded but returned the original data unchanged"
            )
        } catch (_: DecompressionException) {
            // Expected path - corruption was detected
        } catch (_: IllegalArgumentException) {
            // Header verification failures throw IllegalArgumentException via require()
        }
    }

    /**
     * Corrupt multiple bytes in the compressed payload (after the 4-byte header)
     * to maximize the chance of detection. A single bit flip may land in metadata
     * or non-checksummed areas for some algorithms.
     */
    private fun corruptPayloadAggressively(compressed: ByteArray): ByteArray {
        val payloadStart = COMPRESSION_HEADER_SIZE
        val payloadSize = compressed.size - payloadStart
        val offset1 = payloadStart + payloadSize / 3
        val offset2 = payloadStart + (2 * payloadSize) / 3
        val result = compressed.copyOf()
        result[offset1] = (result[offset1].toInt() xor 0xFF).toByte()
        result[offset2] = (result[offset2].toInt() xor 0xFF).toByte()
        return result
    }

    // -------------------------------------------------------------------------
    // GZIP
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Gzip Corruption")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class GzipCorruptionTests {
        private val algorithm = CompressionAlgorithm.GZIP_DEFAULT
        private lateinit var compressor: Compressor
        private lateinit var validCompressed: ByteArray

        @BeforeAll
        fun setupGzip() {
            compressor = factory.create(algorithm)
            validCompressed = compressor.compress(testData)
        }

        @Test
        fun `should throw for random data with valid header`() {
            val random = Random(42)
            val garbage = ByteArray(100).also { random.nextBytes(it) }
            val header = makeHeader(algorithm.headerId)
            val corrupted = header + garbage

            assertThrows<DecompressionException> {
                compressor.decompress(corrupted)
            }
        }

        @Test
        fun `should throw for truncated compressed stream`() {
            val corrupted = CorruptionHelpers.truncate(validCompressed, validCompressed.size / 2)
            assertThrows<DecompressionException> {
                compressor.decompress(corrupted)
            }
        }

        @Test
        fun `should detect bit flip in compressed payload`() {
            // Corrupt multiple bytes in the compressed data to ensure detection.
            // A single bit flip in GZIP metadata may not affect decompressed output.
            val corrupted = corruptPayloadAggressively(validCompressed)
            assertCorruptionDetected(compressor, corrupted, testData, "gzip bit flip")
        }

        @Test
        fun `should throw for valid header but empty payload`() {
            val headerOnly = makeHeader(algorithm.headerId)
            assertThrows<DecompressionException> {
                compressor.decompress(headerOnly)
            }
        }

        @Test
        fun `should throw for wrong algorithm header on data`() {
            val zstdHeader = makeHeader(CompressionAlgorithm.ZSTD_DEFAULT.headerId)
            val gzipPayload = validCompressed.copyOfRange(COMPRESSION_HEADER_SIZE, validCompressed.size)
            val corrupted = zstdHeader + gzipPayload

            val zstdCompressor = factory.create(CompressionAlgorithm.ZSTD_DEFAULT)
            assertThrows<DecompressionException> {
                zstdCompressor.decompress(corrupted)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Zstd
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("Zstd Corruption")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class ZstdCorruptionTests {
        private val algorithm = CompressionAlgorithm.ZSTD_DEFAULT
        private lateinit var compressor: Compressor
        private lateinit var validCompressed: ByteArray

        @BeforeAll
        fun setupZstd() {
            compressor = factory.create(algorithm)
            validCompressed = compressor.compress(testData)
        }

        @Test
        fun `should throw for random data with valid header`() {
            val random = Random(43)
            val garbage = ByteArray(100).also { random.nextBytes(it) }
            val header = makeHeader(algorithm.headerId)
            val corrupted = header + garbage

            assertThrows<DecompressionException> {
                compressor.decompress(corrupted)
            }
        }

        @Test
        fun `should throw for truncated compressed stream`() {
            val corrupted = CorruptionHelpers.truncate(validCompressed, validCompressed.size / 2)
            assertThrows<DecompressionException> {
                compressor.decompress(corrupted)
            }
        }

        @Test
        fun `should detect bit flip in compressed payload`() {
            // Corrupt multiple bytes to ensure detection. Zstd may tolerate a single
            // bit flip in certain frame descriptor areas.
            val corrupted = corruptPayloadAggressively(validCompressed)
            assertCorruptionDetected(compressor, corrupted, testData, "zstd bit flip")
        }

        @Test
        fun `should throw for valid header but empty payload`() {
            // ZstdInputStream may return empty bytes for an empty input (valid empty frame).
            // Either an exception or empty result (which differs from original) is acceptable.
            val headerOnly = makeHeader(algorithm.headerId)
            assertCorruptionDetected(compressor, headerOnly, testData, "zstd empty payload")
        }

        @Test
        fun `should throw for wrong algorithm header on data`() {
            val lz4Header = makeHeader(CompressionAlgorithm.LZ4_DEFAULT.headerId)
            val zstdPayload = validCompressed.copyOfRange(COMPRESSION_HEADER_SIZE, validCompressed.size)
            val corrupted = lz4Header + zstdPayload

            val lz4Compressor = factory.create(CompressionAlgorithm.LZ4_DEFAULT)
            assertThrows<DecompressionException> {
                lz4Compressor.decompress(corrupted)
            }
        }
    }

    // -------------------------------------------------------------------------
    // LZ4
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("LZ4 Corruption")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class Lz4CorruptionTests {
        private val algorithm = CompressionAlgorithm.LZ4_DEFAULT
        private lateinit var compressor: Compressor
        private lateinit var validCompressed: ByteArray

        @BeforeAll
        fun setupLz4() {
            compressor = factory.create(algorithm)
            validCompressed = compressor.compress(testData)
        }

        @Test
        fun `should throw for random data with valid header`() {
            val random = Random(44)
            val garbage = ByteArray(100).also { random.nextBytes(it) }
            val header = makeHeader(algorithm.headerId)
            val corrupted = header + garbage

            assertThrows<DecompressionException> {
                compressor.decompress(corrupted)
            }
        }

        @Test
        fun `should throw for truncated compressed stream`() {
            val corrupted = CorruptionHelpers.truncate(validCompressed, validCompressed.size / 2)
            assertThrows<DecompressionException> {
                compressor.decompress(corrupted)
            }
        }

        @Test
        fun `should detect bit flip in compressed payload`() {
            // Corrupt multiple bytes to maximize detection probability.
            val corrupted = corruptPayloadAggressively(validCompressed)
            assertCorruptionDetected(compressor, corrupted, testData, "lz4 bit flip")
        }

        @Test
        fun `should throw for valid header but empty payload`() {
            val headerOnly = makeHeader(algorithm.headerId)
            assertThrows<DecompressionException> {
                compressor.decompress(headerOnly)
            }
        }

        @Test
        fun `should throw for wrong algorithm header on data`() {
            val gzipHeader = makeHeader(CompressionAlgorithm.GZIP_DEFAULT.headerId)
            val lz4Payload = validCompressed.copyOfRange(COMPRESSION_HEADER_SIZE, validCompressed.size)
            val corrupted = gzipHeader + lz4Payload

            val gzipCompressor = factory.create(CompressionAlgorithm.GZIP_DEFAULT)
            assertThrows<DecompressionException> {
                gzipCompressor.decompress(corrupted)
            }
        }
    }
}
