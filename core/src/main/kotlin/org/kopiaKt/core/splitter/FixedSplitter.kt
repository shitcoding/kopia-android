package org.kopiaKt.core.splitter

/**
 * A splitter that creates fixed-size chunks.
 *
 * This matches Go's fixedSplitter implementation.
 * Fixed splitters always split at exact size boundaries (no content-based splitting).
 *
 * @param size The fixed chunk size in bytes
 */
class FixedSplitter(private val size: Int) : Splitter {
    private var count = 0

    override fun nextSplitPoint(b: ByteArray): Int {
        // How many bytes until we hit the fixed size?
        val remaining = size - count

        return if (remaining <= b.size) {
            // We can complete a chunk
            count = 0
            remaining
        } else {
            // Need more data
            count += b.size
            -1
        }
    }

    override fun maxSegmentSize(): Int = size

    override fun reset() {
        count = 0
    }

    override fun close() {
        // Nothing to clean up
    }
}

/**
 * Creates a factory for fixed-size splitters.
 *
 * @param size The fixed chunk size in bytes
 * @return A SplitterFactory that creates FixedSplitter instances
 */
fun fixedSplitterFactory(size: Int): SplitterFactory = SplitterFactory {
    FixedSplitter(size)
}
