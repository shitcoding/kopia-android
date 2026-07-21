package org.kopiaKt.core.testutil

import kotlin.random.Random

/**
 * Utility object providing methods to corrupt byte arrays in controlled ways
 * for corruption detection tests.
 */
object CorruptionHelpers {
    /** Flip a single bit at the given byte offset and bit position (0-7) */
    fun bitFlip(data: ByteArray, byteOffset: Int, bitPosition: Int = 0): ByteArray {
        require(byteOffset in data.indices) { "byteOffset $byteOffset out of range [0, ${data.size})" }
        require(bitPosition in 0..7) { "bitPosition must be 0-7" }
        val copy = data.copyOf()
        copy[byteOffset] = (copy[byteOffset].toInt() xor (1 shl bitPosition)).toByte()
        return copy
    }

    /** Truncate data to the given length */
    fun truncate(data: ByteArray, length: Int): ByteArray {
        require(length >= 0) { "length must be non-negative" }
        require(length <= data.size) { "length $length exceeds data size ${data.size}" }
        return data.copyOf(length)
    }

    /** Truncate data at standard offsets: [0, 4, 8, mid, end-1], returns list of (description, truncatedData) */
    fun standardTruncations(data: ByteArray): List<Pair<String, ByteArray>> {
        val results = mutableListOf<Pair<String, ByteArray>>()
        val offsets = listOf(
            "empty" to 0,
            "4 bytes" to 4,
            "8 bytes" to 8,
            "midpoint" to data.size / 2,
            "missing last byte" to (data.size - 1),
        )
        for ((desc, length) in offsets) {
            if (length in 0..data.size) {
                results.add(desc to truncate(data, length))
            }
        }
        return results
    }

    /** Insert random bytes at a position */
    fun insertGarbage(data: ByteArray, offset: Int, count: Int, random: Random = Random): ByteArray {
        require(offset in 0..data.size) { "offset $offset out of range [0, ${data.size}]" }
        require(count >= 0) { "count must be non-negative" }
        val garbage = ByteArray(count).also { random.nextBytes(it) }
        val result = ByteArray(data.size + count)
        data.copyInto(result, 0, 0, offset)
        garbage.copyInto(result, offset)
        data.copyInto(result, offset + count, offset, data.size)
        return result
    }

    /** Replace a range with zeros */
    fun zeroRange(data: ByteArray, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= data.size) {
            "Range [$offset, ${offset + length}) out of bounds [0, ${data.size})"
        }
        val copy = data.copyOf()
        for (i in offset until offset + length) {
            copy[i] = 0
        }
        return copy
    }

    /** Flip a random bit somewhere in the data */
    fun randomBitFlip(data: ByteArray, random: Random = Random): ByteArray {
        require(data.isNotEmpty()) { "Cannot flip bit in empty data" }
        val byteOffset = random.nextInt(data.size)
        val bitPosition = random.nextInt(8)
        return bitFlip(data, byteOffset, bitPosition)
    }

    /** Replace bytes at offset with given replacement bytes */
    fun replaceRange(data: ByteArray, offset: Int, replacement: ByteArray): ByteArray {
        require(offset >= 0 && offset + replacement.size <= data.size) {
            "Replacement at [$offset, ${offset + replacement.size}) out of bounds [0, ${data.size})"
        }
        val copy = data.copyOf()
        replacement.copyInto(copy, offset)
        return copy
    }

    /** Append extra bytes to the end of data */
    fun appendBytes(data: ByteArray, extra: ByteArray): ByteArray {
        val result = ByteArray(data.size + extra.size)
        data.copyInto(result, 0)
        extra.copyInto(result, data.size)
        return result
    }
}
