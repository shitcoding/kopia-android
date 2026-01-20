package org.kopiaKt.core.splitter

/**
 * Default splitter factory that creates splitters by algorithm name.
 *
 * Supports all algorithm names from Go's splitter package:
 * - Fixed: FIXED-128K, FIXED-256K, FIXED-512K, FIXED-1M, FIXED-2M, FIXED-4M, FIXED-8M
 * - Buzhash: DYNAMIC-{size}-BUZHASH
 * - RabinKarp: DYNAMIC-{size}-RABINKARP
 *
 * Legacy names are also supported:
 * - FIXED -> FIXED-4M
 * - DYNAMIC -> DYNAMIC-4M-BUZHASH
 */
object DefaultSplitterFactory {
    private val factories: Map<String, SplitterFactory> = buildMap {
        // Fixed splitters
        put(SplitterAlgorithms.FIXED_128K, fixedSplitterFactory(SplitterAlgorithms.SIZE_128K))
        put(SplitterAlgorithms.FIXED_256K, fixedSplitterFactory(SplitterAlgorithms.SIZE_256K))
        put(SplitterAlgorithms.FIXED_512K, fixedSplitterFactory(SplitterAlgorithms.SIZE_512K))
        put(SplitterAlgorithms.FIXED_1M, fixedSplitterFactory(SplitterAlgorithms.SIZE_1M))
        put(SplitterAlgorithms.FIXED_2M, fixedSplitterFactory(SplitterAlgorithms.SIZE_2M))
        put(SplitterAlgorithms.FIXED_4M, fixedSplitterFactory(SplitterAlgorithms.SIZE_4M))
        put(SplitterAlgorithms.FIXED_8M, fixedSplitterFactory(SplitterAlgorithms.SIZE_8M))

        // Dynamic Buzhash splitters
        put(SplitterAlgorithms.DYNAMIC_128K_BUZHASH, buzhash32SplitterFactory(SplitterAlgorithms.SIZE_128K))
        put(SplitterAlgorithms.DYNAMIC_256K_BUZHASH, buzhash32SplitterFactory(SplitterAlgorithms.SIZE_256K))
        put(SplitterAlgorithms.DYNAMIC_512K_BUZHASH, buzhash32SplitterFactory(SplitterAlgorithms.SIZE_512K))
        put(SplitterAlgorithms.DYNAMIC_1M_BUZHASH, buzhash32SplitterFactory(SplitterAlgorithms.SIZE_1M))
        put(SplitterAlgorithms.DYNAMIC_2M_BUZHASH, buzhash32SplitterFactory(SplitterAlgorithms.SIZE_2M))
        put(SplitterAlgorithms.DYNAMIC_4M_BUZHASH, buzhash32SplitterFactory(SplitterAlgorithms.SIZE_4M))
        put(SplitterAlgorithms.DYNAMIC_8M_BUZHASH, buzhash32SplitterFactory(SplitterAlgorithms.SIZE_8M))

        // Dynamic RabinKarp splitters
        put(SplitterAlgorithms.DYNAMIC_128K_RABINKARP, rabinKarp64SplitterFactory(SplitterAlgorithms.SIZE_128K))
        put(SplitterAlgorithms.DYNAMIC_256K_RABINKARP, rabinKarp64SplitterFactory(SplitterAlgorithms.SIZE_256K))
        put(SplitterAlgorithms.DYNAMIC_512K_RABINKARP, rabinKarp64SplitterFactory(SplitterAlgorithms.SIZE_512K))
        put(SplitterAlgorithms.DYNAMIC_1M_RABINKARP, rabinKarp64SplitterFactory(SplitterAlgorithms.SIZE_1M))
        put(SplitterAlgorithms.DYNAMIC_2M_RABINKARP, rabinKarp64SplitterFactory(SplitterAlgorithms.SIZE_2M))
        put(SplitterAlgorithms.DYNAMIC_4M_RABINKARP, rabinKarp64SplitterFactory(SplitterAlgorithms.SIZE_4M))
        put(SplitterAlgorithms.DYNAMIC_8M_RABINKARP, rabinKarp64SplitterFactory(SplitterAlgorithms.SIZE_8M))

        // Legacy names
        put("FIXED", fixedSplitterFactory(SplitterAlgorithms.SIZE_4M))
        put("DYNAMIC", buzhash32SplitterFactory(SplitterAlgorithms.SIZE_4M))
    }

    /**
     * Gets a splitter factory by algorithm name.
     *
     * @param name The algorithm name (e.g., "DYNAMIC-4M-BUZHASH")
     * @return The factory, or null if not found
     */
    fun getFactory(name: String): SplitterFactory? = factories[name]

    /**
     * Creates a splitter by algorithm name.
     *
     * @param name The algorithm name
     * @return A new Splitter instance
     * @throws IllegalArgumentException if the algorithm name is not recognized
     */
    fun create(name: String): Splitter {
        val factory = factories[name]
            ?: throw IllegalArgumentException("Unknown splitter algorithm: $name")
        return factory.create()
    }

    /**
     * Creates the default splitter (DYNAMIC-4M-BUZHASH).
     */
    fun createDefault(): Splitter = create(SplitterAlgorithms.DEFAULT_ALGORITHM)

    /**
     * Returns all supported algorithm names.
     */
    fun supportedAlgorithms(): List<String> = factories.keys.sorted()
}

/**
 * Extension function to find all chunk boundaries in a byte array.
 *
 * @param data The data to split
 * @return List of cumulative boundary positions (each marks the end of a chunk)
 */
fun Splitter.findAllBoundaries(data: ByteArray): List<Int> {
    reset()
    val boundaries = mutableListOf<Int>()
    var offset = 0

    while (offset < data.size) {
        val remaining = data.copyOfRange(offset, data.size)
        val splitPoint = nextSplitPoint(remaining)

        if (splitPoint == -1) {
            // No more split points, remaining data is the last chunk
            boundaries.add(data.size)
            break
        } else {
            offset += splitPoint
            boundaries.add(offset)
        }
    }

    return boundaries
}

/**
 * Extension function to split data into chunks.
 *
 * @param data The data to split
 * @return Sequence of chunk byte arrays
 */
fun Splitter.splitIntoChunks(data: ByteArray): Sequence<ByteArray> = sequence {
    reset()
    var offset = 0

    while (offset < data.size) {
        val remaining = data.copyOfRange(offset, data.size)
        val splitPoint = nextSplitPoint(remaining)

        if (splitPoint == -1) {
            // No more split points, remaining data is the last chunk
            yield(remaining)
            break
        } else {
            yield(data.copyOfRange(offset, offset + splitPoint))
            offset += splitPoint
        }
    }
}
