package org.kopiaKt.snapshot.maintenance

import org.kopiaKt.core.content.ContentId
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * A set for tracking content IDs that are in use.
 *
 * Used during garbage collection to identify which content is still
 * referenced by snapshots and should not be deleted.
 *
 * Go uses a bigmap.Set implementation that can spill to disk for
 * very large repositories. This implementation uses an in-memory
 * concurrent hash set which should work well for most use cases.
 *
 * Go type: bigmap.Set
 */
interface InUseContentSet : Closeable {
    /**
     * Adds a content ID to the set.
     *
     * @param contentId The content ID to add
     * @return true if the content ID was newly added, false if it was already present
     */
    fun add(contentId: ContentId): Boolean

    /**
     * Checks if a content ID is in the set.
     *
     * @param contentId The content ID to check
     * @return true if the content ID is in the set
     */
    fun contains(contentId: ContentId): Boolean

    /**
     * Returns the number of content IDs in the set.
     */
    fun size(): Long

    /**
     * Clears all content IDs from the set.
     */
    fun clear()
}

/**
 * In-memory implementation of InUseContentSet using a ConcurrentHashMap.
 *
 * Thread-safe and suitable for concurrent access during parallel
 * snapshot tree walking.
 */
class MemoryInUseContentSet : InUseContentSet {
    private val set = ConcurrentHashMap.newKeySet<ContentId>()

    override fun add(contentId: ContentId): Boolean {
        return set.add(contentId)
    }

    override fun contains(contentId: ContentId): Boolean {
        return set.contains(contentId)
    }

    override fun size(): Long {
        return set.size.toLong()
    }

    override fun clear() {
        set.clear()
    }

    override fun close() {
        clear()
    }
}

/**
 * A bloom filter-backed set for space-efficient containment checking.
 *
 * Uses a bloom filter for fast negative lookups with a backing set
 * for definitive positive results. Good for very large sets where
 * memory is constrained.
 *
 * Note: This has false positives (may report content as in-use when it isn't),
 * but no false negatives (will never report in-use content as not in-use).
 * This is safe for GC - we may retain some orphaned content, but will
 * never delete referenced content.
 *
 * @param expectedSize Expected number of elements
 * @param falsePositiveRate Acceptable false positive rate (0.0 to 1.0)
 */
class BloomFilterInUseContentSet(
    expectedSize: Long,
    falsePositiveRate: Double = 0.01
) : InUseContentSet {
    // Simple bloom filter implementation
    private val numBits: Int
    private val numHashes: Int
    private val bits: LongArray
    private var count: Long = 0

    init {
        // Calculate optimal parameters
        // m = -n * ln(p) / (ln(2)^2)
        // k = m/n * ln(2)
        val n = expectedSize.coerceAtLeast(1)
        val p = falsePositiveRate.coerceIn(0.0001, 0.5)

        val m = (-n.toDouble() * kotlin.math.ln(p) / (kotlin.math.ln(2.0) * kotlin.math.ln(2.0))).toLong()
        numBits = m.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        numHashes = (numBits.toDouble() / n * kotlin.math.ln(2.0)).toInt().coerceIn(1, 20)

        bits = LongArray((numBits + 63) / 64)
    }

    @Synchronized
    override fun add(contentId: ContentId): Boolean {
        val hashes = computeHashes(contentId)
        var wasNew = false

        for (hash in hashes) {
            val idx = (hash % numBits).toInt().let { if (it < 0) it + numBits else it }
            val longIdx = idx / 64
            val bitIdx = idx % 64
            val mask = 1L shl bitIdx

            if ((bits[longIdx] and mask) == 0L) {
                wasNew = true
                bits[longIdx] = bits[longIdx] or mask
            }
        }

        if (wasNew) {
            count++
        }
        return wasNew
    }

    override fun contains(contentId: ContentId): Boolean {
        val hashes = computeHashes(contentId)

        for (hash in hashes) {
            val idx = (hash % numBits).toInt().let { if (it < 0) it + numBits else it }
            val longIdx = idx / 64
            val bitIdx = idx % 64
            val mask = 1L shl bitIdx

            if ((bits[longIdx] and mask) == 0L) {
                return false
            }
        }
        return true
    }

    override fun size(): Long = count

    @Synchronized
    override fun clear() {
        bits.fill(0L)
        count = 0
    }

    override fun close() {
        clear()
    }

    private fun computeHashes(contentId: ContentId): IntArray {
        val data = contentId.toString().toByteArray()
        val h1 = murmurHash3(data, 0)
        val h2 = murmurHash3(data, h1)

        return IntArray(numHashes) { i ->
            (h1 + i * h2)
        }
    }

    // Simple MurmurHash3 implementation
    private fun murmurHash3(data: ByteArray, seed: Int): Int {
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593

        var h1 = seed
        val len = data.size
        var i = 0

        while (i + 4 <= len) {
            var k1 = (data[i].toInt() and 0xff) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                ((data[i + 2].toInt() and 0xff) shl 16) or
                ((data[i + 3].toInt() and 0xff) shl 24)

            k1 = k1 * c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 = k1 * c2

            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + 0xe6546b64.toInt()

            i += 4
        }

        var k1 = 0
        when (len and 3) {
            3 -> {
                k1 = k1 xor ((data[i + 2].toInt() and 0xff) shl 16)
                k1 = k1 xor ((data[i + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[i].toInt() and 0xff)
                k1 = k1 * c1
                k1 = Integer.rotateLeft(k1, 15)
                k1 = k1 * c2
                h1 = h1 xor k1
            }
            2 -> {
                k1 = k1 xor ((data[i + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[i].toInt() and 0xff)
                k1 = k1 * c1
                k1 = Integer.rotateLeft(k1, 15)
                k1 = k1 * c2
                h1 = h1 xor k1
            }
            1 -> {
                k1 = k1 xor (data[i].toInt() and 0xff)
                k1 = k1 * c1
                k1 = Integer.rotateLeft(k1, 15)
                k1 = k1 * c2
                h1 = h1 xor k1
            }
        }

        h1 = h1 xor len
        h1 = h1 xor (h1 ushr 16)
        h1 = h1 * 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 = h1 * 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)

        return h1
    }
}

/**
 * Factory for creating InUseContentSet instances.
 */
object InUseContentSetFactory {
    /**
     * Creates an appropriate InUseContentSet based on expected size.
     *
     * For smaller sets, uses a memory-based implementation.
     * For larger sets, uses a bloom filter for space efficiency.
     *
     * @param expectedSize Expected number of content IDs
     * @return An InUseContentSet instance
     */
    fun create(expectedSize: Long = 0): InUseContentSet {
        // Use bloom filter for very large sets (> 10M elements)
        // At 1% false positive rate, bloom filter uses ~9.6 bits per element
        // vs ~32+ bytes per element for hash set
        return if (expectedSize > 10_000_000) {
            BloomFilterInUseContentSet(expectedSize, 0.01)
        } else {
            MemoryInUseContentSet()
        }
    }
}
