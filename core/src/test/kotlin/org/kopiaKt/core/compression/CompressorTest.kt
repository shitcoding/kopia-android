package org.kopiaKt.core.compression

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.testvectors.TestVectorLoader
import org.kopiaKt.core.testvectors.TestVectors
import org.kopiaKt.core.testvectors.toHexString

/**
 * Tests for compression implementations using Go-generated test vectors.
 *
 * These tests verify:
 * 1. Header IDs match Go implementation exactly
 * 2. Data can be compressed and decompressed round-trip
 * 3. Go-compressed data can be decompressed by Kotlin
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompressorTest {

    private lateinit var vectors: TestVectors
    private lateinit var factory: CompressorFactory

    @BeforeAll
    fun setup() {
        vectors = TestVectorLoader.load()
        factory = DefaultCompressorFactory()
    }

    @Nested
    @DisplayName("Compression Header IDs")
    inner class HeaderIdTests {

        @Test
        @DisplayName("gzip-default header matches Go")
        fun gzipDefaultHeader() {
            assertHeaderMatches("gzip-default", CompressionAlgorithm.GZIP_DEFAULT)
        }

        @Test
        @DisplayName("gzip-best-speed header matches Go")
        fun gzipBestSpeedHeader() {
            assertHeaderMatches("gzip-best-speed", CompressionAlgorithm.GZIP_BEST_SPEED)
        }

        @Test
        @DisplayName("gzip-best-compression header matches Go")
        fun gzipBestCompressionHeader() {
            assertHeaderMatches("gzip-best-compression", CompressionAlgorithm.GZIP_BEST_COMPRESSION)
        }

        @Test
        @DisplayName("zstd-default header matches Go")
        fun zstdDefaultHeader() {
            assertHeaderMatches("zstd-default", CompressionAlgorithm.ZSTD_DEFAULT)
        }

        @Test
        @DisplayName("zstd-fastest header matches Go")
        fun zstdFastestHeader() {
            assertHeaderMatches("zstd-fastest", CompressionAlgorithm.ZSTD_FASTEST)
        }

        @Test
        @DisplayName("zstd-better-compression header matches Go")
        fun zstdBetterCompressionHeader() {
            assertHeaderMatches("zstd-better-compression", CompressionAlgorithm.ZSTD_BETTER_COMPRESSION)
        }

        @Test
        @DisplayName("zstd-best-compression header matches Go")
        fun zstdBestCompressionHeader() {
            assertHeaderMatches("zstd-best-compression", CompressionAlgorithm.ZSTD_BEST_COMPRESSION)
        }

        @Test
        @DisplayName("lz4-default header matches Go")
        fun lz4DefaultHeader() {
            assertHeaderMatches("lz4-default", CompressionAlgorithm.LZ4_DEFAULT)
        }

        @Test
        @DisplayName("deflate-default header matches Go")
        fun deflateDefaultHeader() {
            assertHeaderMatches("deflate-default", CompressionAlgorithm.DEFLATE_DEFAULT)
        }

        @Test
        @DisplayName("deflate-best-speed header matches Go")
        fun deflateBestSpeedHeader() {
            assertHeaderMatches("deflate-best-speed", CompressionAlgorithm.DEFLATE_BEST_SPEED)
        }

        @Test
        @DisplayName("deflate-best-compression header matches Go")
        fun deflateBestCompressionHeader() {
            assertHeaderMatches("deflate-best-compression", CompressionAlgorithm.DEFLATE_BEST_COMPRESSION)
        }

        @Test
        @DisplayName("pgzip-default header matches Go")
        fun pgzipDefaultHeader() {
            assertHeaderMatches("pgzip-default", CompressionAlgorithm.PGZIP_DEFAULT)
        }

        @Test
        @DisplayName("pgzip-best-speed header matches Go")
        fun pgzipBestSpeedHeader() {
            assertHeaderMatches("pgzip-best-speed", CompressionAlgorithm.PGZIP_BEST_SPEED)
        }

        @Test
        @DisplayName("pgzip-best-compression header matches Go")
        fun pgzipBestCompressionHeader() {
            assertHeaderMatches("pgzip-best-compression", CompressionAlgorithm.PGZIP_BEST_COMPRESSION)
        }

        private fun assertHeaderMatches(algorithmName: String, algorithm: CompressionAlgorithm) {
            val testCase = vectors.compression.headers.find { it.algorithm == algorithmName }
            assertNotNull(testCase, "Test case '$algorithmName' not found in vectors")

            assertEquals(
                testCase!!.headerId,
                algorithm.headerId,
                "Header ID mismatch for $algorithmName"
            )

            assertArrayEquals(
                testCase.header,
                algorithm.header,
                "Header bytes mismatch for $algorithmName"
            )
        }
    }

    @Nested
    @DisplayName("GZIP Compression")
    inner class GzipTests {

        @Test
        @DisplayName("GZIP compresses and decompresses round-trip")
        fun gzipRoundTrip() {
            val compressor = factory.create(CompressionAlgorithm.GZIP_DEFAULT)
            assertRoundTrip(compressor, "Hello, World!".toByteArray())
        }

        @Test
        @DisplayName("GZIP handles empty input")
        fun gzipEmptyInput() {
            val compressor = factory.create(CompressionAlgorithm.GZIP_DEFAULT)
            assertRoundTrip(compressor, ByteArray(0))
        }

        @Test
        @DisplayName("GZIP handles binary data")
        fun gzipBinaryData() {
            val compressor = factory.create(CompressionAlgorithm.GZIP_DEFAULT)
            val data = ByteArray(1024) { it.toByte() }
            assertRoundTrip(compressor, data)
        }

        @Test
        @DisplayName("GZIP handles large compressible data")
        fun gzipLargeCompressible() {
            val compressor = factory.create(CompressionAlgorithm.GZIP_DEFAULT)
            // Highly compressible - repeated pattern
            val data = "AAAA".repeat(10000).toByteArray()
            val compressed = compressor.compress(data)
            // Should compress significantly
            assertTrue(compressed.size < data.size / 2, "Compression should reduce size significantly")
            assertRoundTrip(compressor, data)
        }

        @Test
        @DisplayName("GZIP best-speed is faster but larger")
        fun gzipBestSpeed() {
            val compressor = factory.create(CompressionAlgorithm.GZIP_BEST_SPEED)
            val data = "AAAA".repeat(10000).toByteArray()
            assertRoundTrip(compressor, data)
        }

        @Test
        @DisplayName("GZIP best-compression produces smallest output")
        fun gzipBestCompression() {
            val compressor = factory.create(CompressionAlgorithm.GZIP_BEST_COMPRESSION)
            val data = "AAAA".repeat(10000).toByteArray()
            assertRoundTrip(compressor, data)
        }

        @Test
        @DisplayName("GZIP compressed data has correct header")
        fun gzipCorrectHeader() {
            val compressor = factory.create(CompressionAlgorithm.GZIP_DEFAULT)
            val data = "Test".toByteArray()
            val compressed = compressor.compress(data)

            assertEquals(
                CompressionAlgorithm.GZIP_DEFAULT.headerId,
                CompressionAlgorithm.readHeaderId(compressed),
                "Compressed data should have correct header"
            )
        }
    }

    @Nested
    @DisplayName("Deflate Compression")
    inner class DeflateTests {

        @Test
        @DisplayName("Deflate compresses and decompresses round-trip")
        fun deflateRoundTrip() {
            val compressor = factory.create(CompressionAlgorithm.DEFLATE_DEFAULT)
            assertRoundTrip(compressor, "Hello, World!".toByteArray())
        }

        @Test
        @DisplayName("Deflate handles empty input")
        fun deflateEmptyInput() {
            val compressor = factory.create(CompressionAlgorithm.DEFLATE_DEFAULT)
            assertRoundTrip(compressor, ByteArray(0))
        }

        @Test
        @DisplayName("Deflate handles binary data")
        fun deflateBinaryData() {
            val compressor = factory.create(CompressionAlgorithm.DEFLATE_DEFAULT)
            val data = ByteArray(1024) { it.toByte() }
            assertRoundTrip(compressor, data)
        }
    }

    @Nested
    @DisplayName("Zstd Compression")
    inner class ZstdTests {

        @Test
        @DisplayName("Zstd compresses and decompresses round-trip")
        fun zstdRoundTrip() {
            val compressor = factory.create(CompressionAlgorithm.ZSTD_DEFAULT)
            assertRoundTrip(compressor, "Hello, World!".toByteArray())
        }

        @Test
        @DisplayName("Zstd handles empty input")
        fun zstdEmptyInput() {
            val compressor = factory.create(CompressionAlgorithm.ZSTD_DEFAULT)
            assertRoundTrip(compressor, ByteArray(0))
        }

        @Test
        @DisplayName("Zstd handles binary data")
        fun zstdBinaryData() {
            val compressor = factory.create(CompressionAlgorithm.ZSTD_DEFAULT)
            val data = ByteArray(1024) { it.toByte() }
            assertRoundTrip(compressor, data)
        }

        @Test
        @DisplayName("Zstd handles large compressible data")
        fun zstdLargeCompressible() {
            val compressor = factory.create(CompressionAlgorithm.ZSTD_DEFAULT)
            val data = "AAAA".repeat(10000).toByteArray()
            val compressed = compressor.compress(data)
            assertTrue(compressed.size < data.size / 2, "Compression should reduce size significantly")
            assertRoundTrip(compressor, data)
        }

        @Test
        @DisplayName("Zstd fastest variant works")
        fun zstdFastest() {
            val compressor = factory.create(CompressionAlgorithm.ZSTD_FASTEST)
            assertRoundTrip(compressor, "Test data".toByteArray())
        }

        @Test
        @DisplayName("Zstd better-compression variant works")
        fun zstdBetterCompression() {
            val compressor = factory.create(CompressionAlgorithm.ZSTD_BETTER_COMPRESSION)
            assertRoundTrip(compressor, "Test data".toByteArray())
        }

        @Test
        @DisplayName("Zstd compressed data has correct header")
        fun zstdCorrectHeader() {
            val compressor = factory.create(CompressionAlgorithm.ZSTD_DEFAULT)
            val data = "Test".toByteArray()
            val compressed = compressor.compress(data)

            assertEquals(
                CompressionAlgorithm.ZSTD_DEFAULT.headerId,
                CompressionAlgorithm.readHeaderId(compressed),
                "Compressed data should have correct header"
            )
        }
    }

    @Nested
    @DisplayName("LZ4 Compression")
    inner class Lz4Tests {

        @Test
        @DisplayName("LZ4 compresses and decompresses round-trip")
        fun lz4RoundTrip() {
            val compressor = factory.create(CompressionAlgorithm.LZ4_DEFAULT)
            assertRoundTrip(compressor, "Hello, World!".toByteArray())
        }

        @Test
        @DisplayName("LZ4 handles empty input")
        fun lz4EmptyInput() {
            val compressor = factory.create(CompressionAlgorithm.LZ4_DEFAULT)
            assertRoundTrip(compressor, ByteArray(0))
        }

        @Test
        @DisplayName("LZ4 handles binary data")
        fun lz4BinaryData() {
            val compressor = factory.create(CompressionAlgorithm.LZ4_DEFAULT)
            val data = ByteArray(1024) { it.toByte() }
            assertRoundTrip(compressor, data)
        }

        @Test
        @DisplayName("LZ4 handles large compressible data")
        fun lz4LargeCompressible() {
            val compressor = factory.create(CompressionAlgorithm.LZ4_DEFAULT)
            val data = "AAAA".repeat(10000).toByteArray()
            assertRoundTrip(compressor, data)
        }

        @Test
        @DisplayName("LZ4 compressed data has correct header")
        fun lz4CorrectHeader() {
            val compressor = factory.create(CompressionAlgorithm.LZ4_DEFAULT)
            val data = "Test".toByteArray()
            val compressed = compressor.compress(data)

            assertEquals(
                CompressionAlgorithm.LZ4_DEFAULT.headerId,
                CompressionAlgorithm.readHeaderId(compressed),
                "Compressed data should have correct header"
            )
        }
    }

    @Nested
    @DisplayName("Factory Tests")
    inner class FactoryTests {

        @Test
        @DisplayName("Factory creates all supported compressors")
        fun factoryCreatesAll() {
            val supportedAlgorithms = listOf(
                CompressionAlgorithm.GZIP_DEFAULT,
                CompressionAlgorithm.GZIP_BEST_SPEED,
                CompressionAlgorithm.GZIP_BEST_COMPRESSION,
                CompressionAlgorithm.DEFLATE_DEFAULT,
                CompressionAlgorithm.DEFLATE_BEST_SPEED,
                CompressionAlgorithm.DEFLATE_BEST_COMPRESSION,
                CompressionAlgorithm.ZSTD_DEFAULT,
                CompressionAlgorithm.ZSTD_FASTEST,
                CompressionAlgorithm.ZSTD_BETTER_COMPRESSION,
                CompressionAlgorithm.LZ4_DEFAULT,
                CompressionAlgorithm.PGZIP_DEFAULT,
                CompressionAlgorithm.PGZIP_BEST_SPEED,
                CompressionAlgorithm.PGZIP_BEST_COMPRESSION
            )

            for (algorithm in supportedAlgorithms) {
                val compressor = factory.create(algorithm)
                assertEquals(algorithm, compressor.algorithm, "Compressor algorithm mismatch")
            }
        }

        @Test
        @DisplayName("Factory creates compressor from header ID")
        fun factoryFromHeaderId() {
            val compressor = factory.fromHeaderId(0x1100)  // zstd-default
            assertEquals(CompressionAlgorithm.ZSTD_DEFAULT, compressor.algorithm)
        }

        @Test
        @DisplayName("Factory throws for unknown header ID")
        fun factoryUnknownHeaderId() {
            assertThrows<IllegalArgumentException> {
                factory.fromHeaderId(0xFFFF)
            }
        }

        @Test
        @DisplayName("decompressByHeader dispatches correctly")
        fun decompressByHeader() {
            val original = "Test data for decompressByHeader".toByteArray()

            // Compress with GZIP
            val gzipCompressor = factory.create(CompressionAlgorithm.GZIP_DEFAULT)
            val gzipCompressed = gzipCompressor.compress(original)

            // Decompress using generic method
            val decompressed = factory.decompressByHeader(gzipCompressed)
            assertArrayEquals(original, decompressed)

            // Same test with Zstd
            val zstdCompressor = factory.create(CompressionAlgorithm.ZSTD_DEFAULT)
            val zstdCompressed = zstdCompressor.compress(original)
            val zstdDecompressed = factory.decompressByHeader(zstdCompressed)
            assertArrayEquals(original, zstdDecompressed)
        }
    }

    @Nested
    @DisplayName("Cross-compression compatibility")
    inner class CrossCompatibilityTests {

        @Test
        @DisplayName("Different compression levels produce compatible output")
        fun differentLevelsCompatible() {
            val data = "Test data for compression levels".toByteArray()

            // Compress with best-speed
            val fastCompressor = factory.create(CompressionAlgorithm.GZIP_BEST_SPEED)
            val fastCompressed = fastCompressor.compress(data)

            // Decompress with best-compression compressor
            val slowCompressor = factory.create(CompressionAlgorithm.GZIP_BEST_COMPRESSION)
            val decompressed = slowCompressor.decompress(fastCompressed, withHeader = true)

            assertArrayEquals(data, decompressed)
        }
    }

    private fun assertRoundTrip(compressor: Compressor, original: ByteArray) {
        val compressed = compressor.compress(original)
        val decompressed = compressor.decompress(compressed, withHeader = true)

        assertArrayEquals(
            original,
            decompressed,
            "Round-trip failed for ${compressor.algorithm}: " +
                "original=${original.toHexString()}, " +
                "compressed=${compressed.toHexString()}, " +
                "decompressed=${decompressed.toHexString()}"
        )
    }
}
