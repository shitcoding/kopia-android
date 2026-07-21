package org.kopiaKt.core.compression

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Zstandard compressor compatible with Go's github.com/klauspost/compress/zstd package.
 *
 * Writes a 4-byte header followed by standard Zstd frame data.
 *
 * Compression levels:
 * - ZSTD_FASTEST: level 1 (fastest, lowest compression)
 * - ZSTD_DEFAULT: level 3 (Go's SpeedDefault)
 * - ZSTD_BETTER_COMPRESSION: level 7 (Go's SpeedBetterCompression)
 * - ZSTD_BEST_COMPRESSION: level 19 (Go's SpeedBestCompression, deprecated)
 */
class ZstdCompressor(
    override val algorithm: CompressionAlgorithm,
    private val level: Int = DEFAULT_LEVEL,
) : Compressor {

    init {
        require(algorithm in ZSTD_ALGORITHMS) {
            "ZstdCompressor requires a ZSTD algorithm, got: $algorithm"
        }
    }

    override fun compress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()

        // Write 4-byte header
        output.write(algorithm.header)

        // Write Zstd compressed data
        ZstdOutputStream(output, level).use { zstd ->
            zstd.write(data)
        }

        return output.toByteArray()
    }

    override fun decompress(data: ByteArray, withHeader: Boolean): ByteArray {
        val input = if (withHeader) {
            verifyHeader(data)
            ByteArrayInputStream(data, COMPRESSION_HEADER_SIZE, data.size - COMPRESSION_HEADER_SIZE)
        } else {
            ByteArrayInputStream(data)
        }

        return try {
            ZstdInputStream(input).use { zstd ->
                zstd.readBytes()
            }
        } catch (e: Exception) {
            throw DecompressionException("Zstd decompression failed", e)
        }
    }

    private fun verifyHeader(data: ByteArray) {
        require(data.size >= COMPRESSION_HEADER_SIZE) {
            "Data too short for compression header"
        }
        val headerId = CompressionAlgorithm.readHeaderId(data)
        val headerAlgorithm = CompressionAlgorithm.fromHeaderId(headerId)
        require(headerAlgorithm != null && headerAlgorithm in ZSTD_ALGORITHMS) {
            "Invalid Zstd header: expected ZSTD family, got 0x${headerId.toString(16)}"
        }
    }

    companion object {
        // Zstd compression levels mapping to Go's zstd.EncoderLevel
        private const val LEVEL_FASTEST = 1
        private const val DEFAULT_LEVEL = 3 // SpeedDefault
        private const val LEVEL_BETTER = 7 // SpeedBetterCompression
        private const val LEVEL_BEST = 19 // SpeedBestCompression

        private val ZSTD_ALGORITHMS = setOf(
            CompressionAlgorithm.ZSTD_DEFAULT,
            CompressionAlgorithm.ZSTD_FASTEST,
            CompressionAlgorithm.ZSTD_BETTER_COMPRESSION,
            CompressionAlgorithm.ZSTD_BEST_COMPRESSION,
        )

        fun default() = ZstdCompressor(CompressionAlgorithm.ZSTD_DEFAULT, DEFAULT_LEVEL)
        fun fastest() = ZstdCompressor(CompressionAlgorithm.ZSTD_FASTEST, LEVEL_FASTEST)
        fun betterCompression() = ZstdCompressor(CompressionAlgorithm.ZSTD_BETTER_COMPRESSION, LEVEL_BETTER)
        fun bestCompression() = ZstdCompressor(CompressionAlgorithm.ZSTD_BEST_COMPRESSION, LEVEL_BEST)
    }
}
