package org.kopiaKt.core.compression

/**
 * Supported compression algorithms.
 *
 * Header IDs must match the Go implementation exactly for cross-compatibility.
 */
enum class CompressionAlgorithm(val id: String, val headerId: Int) {
    /**
     * No compression.
     */
    NONE("none", 0),

    /**
     * GZIP/Deflate compression.
     */
    GZIP("gzip", 1),

    /**
     * Parallel GZIP (single-threaded implementation on mobile).
     */
    PGZIP("pgzip", 2),

    /**
     * Zstandard compression.
     */
    ZSTD("zstd", 3),

    /**
     * LZ4 compression.
     */
    LZ4("lz4", 4),

    /**
     * S2 compression (Go-specific, limited support).
     */
    S2("s2", 5);

    companion object {
        /**
         * Default compression algorithm for new repositories.
         */
        val DEFAULT = ZSTD

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
    }
}

/**
 * Interface for compression/decompression operations.
 *
 * Implementations must produce output compatible with the Go implementation
 * (decompression must work cross-platform, compression may vary).
 */
interface Compressor {
    /**
     * The compression algorithm used by this compressor.
     */
    val algorithm: CompressionAlgorithm

    /**
     * Compresses data.
     *
     * @param data The data to compress
     * @return The compressed data (may be larger than input for incompressible data)
     */
    fun compress(data: ByteArray): ByteArray

    /**
     * Decompresses data.
     *
     * @param data The compressed data
     * @return The original uncompressed data
     * @throws DecompressionException if decompression fails
     */
    fun decompress(data: ByteArray): ByteArray
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
}

/**
 * Exception thrown when decompression fails.
 */
class DecompressionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
