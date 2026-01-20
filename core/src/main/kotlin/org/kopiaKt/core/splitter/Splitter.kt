package org.kopiaKt.core.splitter

/**
 * Supported splitter algorithms for content-defined chunking.
 *
 * The chunking algorithm and boundaries must match the Go implementation
 * exactly for cross-compatibility and deduplication.
 */
enum class SplitterAlgorithm(val id: String, val minSize: Int, val avgSize: Int, val maxSize: Int) {
    // Fixed-size splitters
    FIXED_128K("FIXED-128k", 128 * 1024, 128 * 1024, 128 * 1024),
    FIXED_256K("FIXED-256k", 256 * 1024, 256 * 1024, 256 * 1024),
    FIXED_512K("FIXED-512k", 512 * 1024, 512 * 1024, 512 * 1024),
    FIXED_1M("FIXED-1M", 1024 * 1024, 1024 * 1024, 1024 * 1024),
    FIXED_2M("FIXED-2M", 2 * 1024 * 1024, 2 * 1024 * 1024, 2 * 1024 * 1024),
    FIXED_4M("FIXED-4M", 4 * 1024 * 1024, 4 * 1024 * 1024, 4 * 1024 * 1024),
    FIXED_8M("FIXED-8M", 8 * 1024 * 1024, 8 * 1024 * 1024, 8 * 1024 * 1024),

    // Dynamic splitters using Buzhash
    DYNAMIC_1M_BUZHASH("DYNAMIC-1M-BUZHASH", 512 * 1024, 1024 * 1024, 2 * 1024 * 1024),
    DYNAMIC_2M_BUZHASH("DYNAMIC-2M-BUZHASH", 1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024),
    DYNAMIC_4M_BUZHASH("DYNAMIC-4M-BUZHASH", 2 * 1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024),

    // Dynamic splitters using Rabin-Karp
    DYNAMIC_1M_RABINKARP("DYNAMIC-1M-RABINKARP", 512 * 1024, 1024 * 1024, 2 * 1024 * 1024),
    DYNAMIC_2M_RABINKARP("DYNAMIC-2M-RABINKARP", 1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024),
    DYNAMIC_4M_RABINKARP("DYNAMIC-4M-RABINKARP", 2 * 1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024);

    val isFixed: Boolean
        get() = id.startsWith("FIXED")

    val isDynamic: Boolean
        get() = id.startsWith("DYNAMIC")

    val usesBuilzhash: Boolean
        get() = id.contains("BUZHASH")

    val usesRabinKarp: Boolean
        get() = id.contains("RABINKARP")

    companion object {
        /**
         * Default splitter algorithm for new repositories.
         */
        val DEFAULT = DYNAMIC_4M_BUZHASH

        /**
         * Finds algorithm by ID string.
         */
        fun fromId(id: String): SplitterAlgorithm? =
            entries.find { it.id == id }
    }
}

/**
 * Interface for content splitters that divide data into chunks.
 *
 * Implementations must produce chunk boundaries identical to the Go implementation
 * for cross-compatibility and deduplication.
 */
interface Splitter {
    /**
     * The splitter algorithm used by this splitter.
     */
    val algorithm: SplitterAlgorithm

    /**
     * Splits data into chunks.
     *
     * @param data The data to split
     * @return Sequence of chunk data
     */
    fun split(data: ByteArray): Sequence<ByteArray>

    /**
     * Returns chunk boundaries without copying data.
     *
     * @param data The data to analyze
     * @return Sequence of (offset, length) pairs for each chunk
     */
    fun findBoundaries(data: ByteArray): Sequence<ChunkBoundary>
}

/**
 * Represents a chunk boundary in the source data.
 */
data class ChunkBoundary(
    val offset: Long,
    val length: Int
)

/**
 * Factory for creating splitters.
 */
interface SplitterFactory {
    /**
     * Creates a splitter for the given algorithm.
     *
     * @param algorithm The splitter algorithm to use
     * @return A Splitter instance
     */
    fun create(algorithm: SplitterAlgorithm): Splitter
}
