package org.kopiaKt.core.splitter

import kotlin.math.max
import kotlin.math.min

/**
 * Content-defined chunking splitter using Buzhash32 rolling hash.
 *
 * This matches Go's buzhash32Splitter implementation exactly.
 * The splitter uses a rolling hash to find split points based on hash value patterns.
 *
 * Split boundaries occur when:
 * - The rolling hash value ANDed with a mask equals 0
 * - The chunk reaches maxSize (forced split)
 *
 * No splits occur until minSize bytes have been processed.
 *
 * @param avgSize The target average chunk size (must be power of 2)
 */
class Buzhash32Splitter(avgSize: Int) : Splitter {
    private val rh = Buzhash32.new()
    private val mask: UInt = (avgSize - 1).toUInt()
    private val minSize: Int = avgSize / 2
    private val maxSize: Int = avgSize * 2
    private var count: Int = 0

    init {
        require(avgSize > 0 && (avgSize and (avgSize - 1)) == 0) {
            "avgSize must be a power of 2"
        }
        // Initialize the rolling hash window with zeros (same as Go)
        rh.write(ByteArray(SPLITTER_SLIDING_WINDOW_SIZE))
    }

    override fun nextSplitPoint(b: ByteArray): Int {
        var fastPathBytes = 0

        // Until minSize, only hash the last splitterSlidingWindowSize bytes.
        // This is an optimization - we don't need to check for split points until minSize.
        val leftToMin = minSize - count - 1
        if (leftToMin > 0) {
            fastPathBytes = min(leftToMin, b.size)
            var i = max(fastPathBytes - SPLITTER_SLIDING_WINDOW_SIZE, 0)

            while (i < fastPathBytes) {
                rh.roll(b[i])
                i++
            }

            count += fastPathBytes
        }

        // Process remaining bytes after fastPath, checking for split points
        val dataAfterFastPath = if (fastPathBytes < b.size) {
            b.copyOfRange(fastPathBytes, b.size)
        } else {
            ByteArray(0)
        }

        // Until maxSize, check if we have any splitting point
        val leftToMax = maxSize - count
        if (leftToMax > 0) {
            val fp = min(leftToMax, dataAfterFastPath.size)

            for (i in 0 until fp) {
                rh.roll(dataAfterFastPath[i])
                count++

                if (rh.sum32() and mask == 0u) {
                    count = 0
                    return fastPathBytes + i + 1
                }
            }

            fastPathBytes += fp
        }

        // If we're over the max size, split
        if (count >= maxSize) {
            count = 0
            return fastPathBytes
        }

        return -1
    }

    override fun maxSegmentSize(): Int = maxSize

    override fun reset() {
        rh.reset()
        rh.write(ByteArray(SPLITTER_SLIDING_WINDOW_SIZE))
        count = 0
    }

    override fun close() {
        // Nothing to clean up
    }
}

/**
 * Creates a factory for Buzhash32 splitters with the given average size.
 *
 * @param avgSize The target average chunk size (must be power of 2)
 * @return A SplitterFactory that creates Buzhash32Splitter instances
 */
fun buzhash32SplitterFactory(avgSize: Int): SplitterFactory = SplitterFactory {
    Buzhash32Splitter(avgSize)
}
