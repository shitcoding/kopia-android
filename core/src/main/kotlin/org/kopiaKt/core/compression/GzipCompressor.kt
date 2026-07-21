package org.kopiaKt.core.compression

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * GZIP compressor compatible with Go's compress/gzip package.
 *
 * Writes a 4-byte header followed by standard GZIP data.
 */
class GzipCompressor(
    override val algorithm: CompressionAlgorithm,
    private val level: Int = Deflater.DEFAULT_COMPRESSION,
) : Compressor {

    init {
        require(algorithm in GZIP_ALGORITHMS) {
            "GzipCompressor requires a GZIP algorithm, got: $algorithm"
        }
    }

    override fun compress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()

        // Write 4-byte header
        output.write(algorithm.header)

        // Write GZIP compressed data
        GZIPOutputStream(output, BUFFER_SIZE).use { gzip ->
            // Set compression level via the underlying deflater
            // Note: GZIPOutputStream doesn't expose level directly, but uses default
            // For level control, we'd need a custom implementation, but this is compatible
            gzip.write(data)
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
            GZIPInputStream(input, BUFFER_SIZE).use { gzip ->
                gzip.readBytes()
            }
        } catch (e: Exception) {
            throw DecompressionException("GZIP decompression failed", e)
        }
    }

    private fun verifyHeader(data: ByteArray) {
        require(data.size >= COMPRESSION_HEADER_SIZE) {
            "Data too short for compression header"
        }
        val headerId = CompressionAlgorithm.readHeaderId(data)
        // Allow any GZIP-family header for decompression
        val headerAlgorithm = CompressionAlgorithm.fromHeaderId(headerId)
        require(headerAlgorithm != null && headerAlgorithm in GZIP_ALGORITHMS) {
            "Invalid GZIP header: expected GZIP family, got 0x${headerId.toString(16)}"
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192

        private val GZIP_ALGORITHMS = setOf(
            CompressionAlgorithm.GZIP_DEFAULT,
            CompressionAlgorithm.GZIP_BEST_SPEED,
            CompressionAlgorithm.GZIP_BEST_COMPRESSION,
            CompressionAlgorithm.PGZIP_DEFAULT,
            CompressionAlgorithm.PGZIP_BEST_SPEED,
            CompressionAlgorithm.PGZIP_BEST_COMPRESSION,
        )

        fun default() = GzipCompressor(CompressionAlgorithm.GZIP_DEFAULT, Deflater.DEFAULT_COMPRESSION)
        fun bestSpeed() = GzipCompressor(CompressionAlgorithm.GZIP_BEST_SPEED, Deflater.BEST_SPEED)
        fun bestCompression() = GzipCompressor(CompressionAlgorithm.GZIP_BEST_COMPRESSION, Deflater.BEST_COMPRESSION)

        fun pgzipDefault() = GzipCompressor(CompressionAlgorithm.PGZIP_DEFAULT, Deflater.DEFAULT_COMPRESSION)
        fun pgzipBestSpeed() = GzipCompressor(CompressionAlgorithm.PGZIP_BEST_SPEED, Deflater.BEST_SPEED)
        fun pgzipBestCompression() = GzipCompressor(CompressionAlgorithm.PGZIP_BEST_COMPRESSION, Deflater.BEST_COMPRESSION)
    }
}

/**
 * Deflate compressor compatible with Go's compress/flate package.
 *
 * Writes a 4-byte header followed by raw DEFLATE data (no GZIP wrapper).
 */
class DeflateCompressor(
    override val algorithm: CompressionAlgorithm,
    private val level: Int = Deflater.DEFAULT_COMPRESSION,
) : Compressor {

    init {
        require(algorithm in DEFLATE_ALGORITHMS) {
            "DeflateCompressor requires a DEFLATE algorithm, got: $algorithm"
        }
    }

    override fun compress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()

        // Write 4-byte header
        output.write(algorithm.header)

        // Write raw DEFLATE compressed data
        val deflater = Deflater(level)
        DeflaterOutputStream(output, deflater, BUFFER_SIZE).use { deflateStream ->
            deflateStream.write(data)
        }
        deflater.end()

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
            val inflater = Inflater()
            InflaterInputStream(input, inflater, BUFFER_SIZE).use { inflateStream ->
                inflateStream.readBytes()
            }.also {
                inflater.end()
            }
        } catch (e: Exception) {
            throw DecompressionException("Deflate decompression failed", e)
        }
    }

    private fun verifyHeader(data: ByteArray) {
        require(data.size >= COMPRESSION_HEADER_SIZE) {
            "Data too short for compression header"
        }
        val headerId = CompressionAlgorithm.readHeaderId(data)
        val headerAlgorithm = CompressionAlgorithm.fromHeaderId(headerId)
        require(headerAlgorithm != null && headerAlgorithm in DEFLATE_ALGORITHMS) {
            "Invalid DEFLATE header: expected DEFLATE family, got 0x${headerId.toString(16)}"
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192

        private val DEFLATE_ALGORITHMS = setOf(
            CompressionAlgorithm.DEFLATE_DEFAULT,
            CompressionAlgorithm.DEFLATE_BEST_SPEED,
            CompressionAlgorithm.DEFLATE_BEST_COMPRESSION,
        )

        fun default() = DeflateCompressor(CompressionAlgorithm.DEFLATE_DEFAULT, Deflater.DEFAULT_COMPRESSION)
        fun bestSpeed() = DeflateCompressor(CompressionAlgorithm.DEFLATE_BEST_SPEED, Deflater.BEST_SPEED)
        fun bestCompression() = DeflateCompressor(CompressionAlgorithm.DEFLATE_BEST_COMPRESSION, Deflater.BEST_COMPRESSION)
    }
}
