package org.kopiaKt.core.splitter

/**
 * Sliding window size used by dynamic splitters.
 * Must match Go's splitterSlidingWindowSize constant.
 */
const val SPLITTER_SLIDING_WINDOW_SIZE = 64

/**
 * Interface for content splitters that divide data into chunks.
 *
 * Implementations must produce chunk boundaries identical to the Go implementation
 * for cross-compatibility and deduplication.
 *
 * The API is designed to match Go's Splitter interface:
 * - nextSplitPoint() determines the location of the next split point
 * - Returns value n between 1..len(b) if a split point happens AFTER byte n
 * - Returns -1 if there is no split point (all bytes consumed)
 */
interface Splitter {
    /**
     * Determines the location of the next split point in the given byte array.
     *
     * @param b The bytes to examine for a split point
     * @return A value n between 1..b.size if a split point happens AFTER byte n,
     *         or -1 if there is no split point and all bytes are consumed.
     */
    fun nextSplitPoint(b: ByteArray): Int

    /**
     * Returns the maximum segment size this splitter will produce.
     */
    fun maxSegmentSize(): Int

    /**
     * Resets the splitter state for a new file/stream.
     */
    fun reset()

    /**
     * Releases resources associated with this splitter.
     */
    fun close()
}

/**
 * Represents a chunk boundary in the source data.
 */
data class ChunkBoundary(
    val offset: Long,
    val length: Int,
)

/**
 * Factory for creating splitters.
 */
fun interface SplitterFactory {
    /**
     * Creates a new Splitter instance.
     * Each call should return a fresh instance.
     */
    fun create(): Splitter
}

/**
 * Supported splitter algorithm names matching Go's implementation.
 */
object SplitterAlgorithms {
    // Size constants
    const val SIZE_128K = 128 * 1024
    const val SIZE_256K = 256 * 1024
    const val SIZE_512K = 512 * 1024
    const val SIZE_1M = 1024 * 1024
    const val SIZE_2M = 2 * 1024 * 1024
    const val SIZE_4M = 4 * 1024 * 1024
    const val SIZE_8M = 8 * 1024 * 1024

    // Fixed splitter names
    const val FIXED_128K = "FIXED-128K"
    const val FIXED_256K = "FIXED-256K"
    const val FIXED_512K = "FIXED-512K"
    const val FIXED_1M = "FIXED-1M"
    const val FIXED_2M = "FIXED-2M"
    const val FIXED_4M = "FIXED-4M"
    const val FIXED_8M = "FIXED-8M"

    // Dynamic Buzhash splitter names
    const val DYNAMIC_128K_BUZHASH = "DYNAMIC-128K-BUZHASH"
    const val DYNAMIC_256K_BUZHASH = "DYNAMIC-256K-BUZHASH"
    const val DYNAMIC_512K_BUZHASH = "DYNAMIC-512K-BUZHASH"
    const val DYNAMIC_1M_BUZHASH = "DYNAMIC-1M-BUZHASH"
    const val DYNAMIC_2M_BUZHASH = "DYNAMIC-2M-BUZHASH"
    const val DYNAMIC_4M_BUZHASH = "DYNAMIC-4M-BUZHASH"
    const val DYNAMIC_8M_BUZHASH = "DYNAMIC-8M-BUZHASH"

    // Dynamic Rabin-Karp splitter names
    const val DYNAMIC_128K_RABINKARP = "DYNAMIC-128K-RABINKARP"
    const val DYNAMIC_256K_RABINKARP = "DYNAMIC-256K-RABINKARP"
    const val DYNAMIC_512K_RABINKARP = "DYNAMIC-512K-RABINKARP"
    const val DYNAMIC_1M_RABINKARP = "DYNAMIC-1M-RABINKARP"
    const val DYNAMIC_2M_RABINKARP = "DYNAMIC-2M-RABINKARP"
    const val DYNAMIC_4M_RABINKARP = "DYNAMIC-4M-RABINKARP"
    const val DYNAMIC_8M_RABINKARP = "DYNAMIC-8M-RABINKARP"

    // Default algorithm for new repositories
    const val DEFAULT_ALGORITHM = DYNAMIC_4M_BUZHASH

    /**
     * Returns list of all supported algorithm names.
     */
    fun supportedAlgorithms(): List<String> = listOf(
        FIXED_128K, FIXED_256K, FIXED_512K, FIXED_1M, FIXED_2M, FIXED_4M, FIXED_8M,
        DYNAMIC_128K_BUZHASH, DYNAMIC_256K_BUZHASH, DYNAMIC_512K_BUZHASH,
        DYNAMIC_1M_BUZHASH, DYNAMIC_2M_BUZHASH, DYNAMIC_4M_BUZHASH, DYNAMIC_8M_BUZHASH,
        DYNAMIC_128K_RABINKARP, DYNAMIC_256K_RABINKARP, DYNAMIC_512K_RABINKARP,
        DYNAMIC_1M_RABINKARP, DYNAMIC_2M_RABINKARP, DYNAMIC_4M_RABINKARP, DYNAMIC_8M_RABINKARP,
    ).sorted()
}
