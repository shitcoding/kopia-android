package org.kopiaKt.core.compression

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compression header size in bytes (4 bytes, big-endian uint32).
 */
const val COMPRESSION_HEADER_SIZE = 4

/**
 * Supported compression algorithms with their header IDs.
 *
 * Header IDs must match the Go implementation exactly for cross-compatibility.
 * The header is a 4-byte big-endian uint32 written at the start of compressed data.
 */
enum class CompressionAlgorithm(
    val id: String,
    val headerId: Int,
    val deprecated: Boolean = false
) {
    // No compression
    NONE("none", 0),

    // GZIP compression variants
    GZIP_DEFAULT("gzip", 0x1000),
    GZIP_BEST_SPEED("gzip-best-speed", 0x1001),
    GZIP_BEST_COMPRESSION("gzip-best-compression", 0x1002),

    // Zstandard compression variants
    ZSTD_DEFAULT("zstd", 0x1100),
    ZSTD_FASTEST("zstd-fastest", 0x1101),
    ZSTD_BETTER_COMPRESSION("zstd-better-compression", 0x1102),
    ZSTD_BEST_COMPRESSION("zstd-best-compression", 0x1103, deprecated = true),

    // S2 compression variants (Go-specific, decompression only)
    S2_DEFAULT("s2-default", 0x1200, deprecated = true),
    S2_BETTER("s2-better", 0x1201, deprecated = true),
    S2_PARALLEL_4("s2-parallel-4", 0x1202, deprecated = true),
    S2_PARALLEL_8("s2-parallel-8", 0x1203, deprecated = true),

    // Parallel GZIP compression variants
    PGZIP_DEFAULT("pgzip", 0x1300),
    PGZIP_BEST_SPEED("pgzip-best-speed", 0x1301),
    PGZIP_BEST_COMPRESSION("pgzip-best-compression", 0x1302),

    // LZ4 compression (deprecated in Go)
    LZ4_DEFAULT("lz4", 0x1400, deprecated = true),

    // Deflate compression variants (raw deflate without gzip wrapper)
    DEFLATE_DEFAULT("deflate-default", 0x1500),
    DEFLATE_BEST_SPEED("deflate-best-speed", 0x1501),
    DEFLATE_BEST_COMPRESSION("deflate-best-compression", 0x1502);

    /**
     * The 4-byte header for this compression algorithm.
     */
    val header: ByteArray by lazy {
        ByteBuffer.allocate(COMPRESSION_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(headerId)
            .array()
    }

    companion object {
        /**
         * Default compression algorithm for new repositories.
         */
        val DEFAULT = ZSTD_DEFAULT

        /**
         * Finds algorithm by ID string.
         */
        fun fromId(id: String): CompressionAlgorithm? =
            entries.find { it.id == id }

        /**
         * Finds algorithm by header ID.
         */
        fun fromHeaderId(headerId: Int): CompressionAlgorithm? =
            entries.find { it.headerId == headerId }

        /**
         * Reads header ID from a byte array.
         */
        fun readHeaderId(data: ByteArray): Int {
            require(data.size >= COMPRESSION_HEADER_SIZE) {
                "Data too short to contain compression header"
            }
            return ByteBuffer.wrap(data, 0, COMPRESSION_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .int
        }
    }
}

/**
 * Interface for compression/decompression operations.
 *
 * Implementations must produce output compatible with the Go implementation
 * (decompression must work cross-platform, compression may vary).
 *
 * The compressed format is:
 * - 4-byte big-endian header ID
 * - Compressed data in the algorithm's native format
 */
interface Compressor {
    /**
     * The compression algorithm used by this compressor.
     */
    val algorithm: CompressionAlgorithm

    /**
     * Compresses data with header.
     *
     * @param data The data to compress
     * @return The compressed data with 4-byte header prefix
     */
    fun compress(data: ByteArray): ByteArray

    /**
     * Decompresses data.
     *
     * @param data The compressed data (with or without header)
     * @param withHeader If true, expects and verifies 4-byte header; if false, data is raw compressed bytes
     * @return The original uncompressed data
     * @throws DecompressionException if decompression fails
     */
    fun decompress(data: ByteArray, withHeader: Boolean = true): ByteArray
}

/**
 * Factory for creating compressors.
 */
interface CompressorFactory {
    /**
     * Creates a compressor for the given algorithm.
     *
     * @param algorithm The compression algorithm to use
     * @return A Compressor instance
     * @throws IllegalArgumentException if the algorithm is not supported
     */
    fun create(algorithm: CompressionAlgorithm): Compressor

    /**
     * Creates a compressor from a header ID found in content.
     *
     * @param headerId The header ID from content
     * @return A Compressor instance
     * @throws IllegalArgumentException if the header ID is unknown
     */
    fun fromHeaderId(headerId: Int): Compressor

    /**
     * Decompresses data by reading the header and dispatching to the appropriate compressor.
     *
     * @param data The compressed data with header
     * @return The original uncompressed data
     * @throws DecompressionException if decompression fails
     */
    fun decompressByHeader(data: ByteArray): ByteArray
}

/**
 * Exception thrown when decompression fails.
 */
class DecompressionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
