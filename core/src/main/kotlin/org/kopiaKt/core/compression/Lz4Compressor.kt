package org.kopiaKt.core.compression

import net.jpountz.lz4.LZ4FrameInputStream
import net.jpountz.lz4.LZ4FrameOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private const val BUFFER_SIZE = 8192

/**
 * LZ4 compressor compatible with Go's github.com/pierrec/lz4 package.
 *
 * Writes a 4-byte header followed by standard LZ4 Frame format data.
 * This uses the LZ4 Frame format (streaming format) which is compatible
 * across different LZ4 implementations (Go, Java, C, etc.).
 *
 * Note: LZ4 is marked as deprecated in Go Kopia in favor of Zstd,
 * but we support it for reading existing backups.
 */
class Lz4Compressor(
    override val algorithm: CompressionAlgorithm = CompressionAlgorithm.LZ4_DEFAULT,
) : Compressor {

    init {
        require(algorithm == CompressionAlgorithm.LZ4_DEFAULT) {
            "Lz4Compressor only supports LZ4_DEFAULT algorithm, got: $algorithm"
        }
    }

    override fun compress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()

        // Write 4-byte header
        output.write(algorithm.header)

        // Write LZ4 Frame compressed data
        LZ4FrameOutputStream(output).use { lz4 ->
            lz4.write(data)
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
            LZ4FrameInputStream(input).use { lz4 ->
                // Note: We can't use readBytes() directly because LZ4FrameInputStream.available()
                // has a bug that returns null buffer. Instead, we read manually.
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (lz4.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.toByteArray()
            }
        } catch (e: Exception) {
            throw DecompressionException("LZ4 decompression failed", e)
        }
    }

    private fun verifyHeader(data: ByteArray) {
        require(data.size >= COMPRESSION_HEADER_SIZE) {
            "Data too short for compression header"
        }
        val headerId = CompressionAlgorithm.readHeaderId(data)
        require(headerId == CompressionAlgorithm.LZ4_DEFAULT.headerId) {
            "Invalid LZ4 header: expected 0x${CompressionAlgorithm.LZ4_DEFAULT.headerId.toString(16)}, got 0x${headerId.toString(16)}"
        }
    }

    companion object {
        fun default() = Lz4Compressor(CompressionAlgorithm.LZ4_DEFAULT)
    }
}
