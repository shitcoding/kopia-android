package org.kopiaKt.core.compression

/**
 * Default implementation of CompressorFactory.
 *
 * Supports all compression algorithms compatible with Go Kopia:
 * - GZIP (default, best-speed, best-compression)
 * - PGZIP (parallel GZIP - implemented as standard GZIP on mobile)
 * - Deflate (default, best-speed, best-compression)
 * - Zstd (default, fastest, better-compression, best-compression)
 * - LZ4 (default)
 *
 * S2 compression is not supported (Go-specific, decompression only would require
 * porting the S2 algorithm).
 */
class DefaultCompressorFactory : CompressorFactory {

    override fun create(algorithm: CompressionAlgorithm): Compressor = when (algorithm) {
        // No compression
        CompressionAlgorithm.NONE -> NoOpCompressor

        // GZIP variants
        CompressionAlgorithm.GZIP_DEFAULT -> GzipCompressor.default()
        CompressionAlgorithm.GZIP_BEST_SPEED -> GzipCompressor.bestSpeed()
        CompressionAlgorithm.GZIP_BEST_COMPRESSION -> GzipCompressor.bestCompression()

        // PGZIP variants (implemented as standard GZIP - parallel not needed on mobile)
        CompressionAlgorithm.PGZIP_DEFAULT -> GzipCompressor.pgzipDefault()
        CompressionAlgorithm.PGZIP_BEST_SPEED -> GzipCompressor.pgzipBestSpeed()
        CompressionAlgorithm.PGZIP_BEST_COMPRESSION -> GzipCompressor.pgzipBestCompression()

        // Deflate variants
        CompressionAlgorithm.DEFLATE_DEFAULT -> DeflateCompressor.default()
        CompressionAlgorithm.DEFLATE_BEST_SPEED -> DeflateCompressor.bestSpeed()
        CompressionAlgorithm.DEFLATE_BEST_COMPRESSION -> DeflateCompressor.bestCompression()

        // Zstd variants
        CompressionAlgorithm.ZSTD_DEFAULT -> ZstdCompressor.default()
        CompressionAlgorithm.ZSTD_FASTEST -> ZstdCompressor.fastest()
        CompressionAlgorithm.ZSTD_BETTER_COMPRESSION -> ZstdCompressor.betterCompression()
        CompressionAlgorithm.ZSTD_BEST_COMPRESSION -> ZstdCompressor.bestCompression()

        // LZ4
        CompressionAlgorithm.LZ4_DEFAULT -> Lz4Compressor.default()

        // S2 is Go-specific and not supported
        CompressionAlgorithm.S2_DEFAULT,
        CompressionAlgorithm.S2_BETTER,
        CompressionAlgorithm.S2_PARALLEL_4,
        CompressionAlgorithm.S2_PARALLEL_8,
        ->
            throw IllegalArgumentException(
                "S2 compression is not supported (Go-specific algorithm)",
            )
    }

    override fun fromHeaderId(headerId: Int): Compressor {
        val algorithm = CompressionAlgorithm.fromHeaderId(headerId)
            ?: throw IllegalArgumentException(
                "Unknown compression header ID: 0x${headerId.toString(16)}",
            )
        return create(algorithm)
    }

    override fun decompressByHeader(data: ByteArray): ByteArray {
        require(data.size >= COMPRESSION_HEADER_SIZE) {
            "Data too short to contain compression header"
        }

        val headerId = CompressionAlgorithm.readHeaderId(data)

        // Special case: no compression (header ID 0)
        if (headerId == 0) {
            return data.copyOfRange(COMPRESSION_HEADER_SIZE, data.size)
        }

        val compressor = fromHeaderId(headerId)
        return compressor.decompress(data, withHeader = true)
    }
}

/**
 * No-op compressor that passes data through unchanged.
 *
 * Used when compression is disabled. Still writes the header (0x00000000).
 */
object NoOpCompressor : Compressor {
    override val algorithm: CompressionAlgorithm = CompressionAlgorithm.NONE

    override fun compress(data: ByteArray): ByteArray {
        // Write header + original data
        val output = ByteArray(COMPRESSION_HEADER_SIZE + data.size)
        algorithm.header.copyInto(output, 0)
        data.copyInto(output, COMPRESSION_HEADER_SIZE)
        return output
    }

    override fun decompress(data: ByteArray, withHeader: Boolean): ByteArray = if (withHeader) {
        require(data.size >= COMPRESSION_HEADER_SIZE) {
            "Data too short for compression header"
        }
        val headerId = CompressionAlgorithm.readHeaderId(data)
        require(headerId == 0) {
            "Expected no-compression header (0), got 0x${headerId.toString(16)}"
        }
        data.copyOfRange(COMPRESSION_HEADER_SIZE, data.size)
    } else {
        data.copyOf()
    }
}
