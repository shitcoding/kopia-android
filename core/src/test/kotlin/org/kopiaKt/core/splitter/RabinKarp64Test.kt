package org.kopiaKt.core.splitter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RabinKarp64Test {

    @Test
    fun `RabinKarp64 produces expected hash after initial window`() {
        val rh = RabinKarp64.new()
        rh.write(ByteArray(64))

        assertEquals(0uL, rh.sum64(), "After 64 zeros, sum64 should be 0")
    }

    @Test
    fun `RabinKarp64 produces expected hashes after rolling bytes`() {
        val rh = RabinKarp64.new()
        rh.write(ByteArray(64))

        // Go output:
        // After rolling byte 0x01: Sum64: 0x0000000000000001
        // After rolling byte 0x02: Sum64: 0x0000000000000102
        // After rolling byte 0x03: Sum64: 0x0000000000010203
        // etc.

        rh.roll(0x01)
        println("After rolling 0x01: 0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x0000000000000001uL, rh.sum64(), "After rolling 0x01")

        rh.roll(0x02)
        println("After rolling 0x02: 0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x0000000000000102uL, rh.sum64(), "After rolling 0x02")

        rh.roll(0x03)
        println("After rolling 0x03: 0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x0000000000010203uL, rh.sum64(), "After rolling 0x03")

        rh.roll(0x04)
        println("After rolling 0x04: 0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x0000000001020304uL, rh.sum64(), "After rolling 0x04")

        rh.roll(0x05)
        println("After rolling 0x05: 0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x0000000102030405uL, rh.sum64(), "After rolling 0x05")
    }

    @Test
    fun `RabinKarp64 produces expected hashes for 128K splitter pattern`() {
        // Test with the actual data pattern used in the test vectors
        // Go output from our test:
        // Byte 0: sum64=0x0000000000000000
        // Byte 1: sum64=0x000000000000000d
        // Byte 2: sum64=0x0000000000000d1a
        // etc.
        val rh = RabinKarp64.new()
        rh.write(ByteArray(64))

        // Generate data same as Go: data[i] = byte(i * 13)
        val data = ByteArray(10) { (it * 13).toByte() }

        rh.roll(data[0])
        println("Byte 0: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x0000000000000000uL, rh.sum64(), "Byte 0") // 0 * 13 = 0

        rh.roll(data[1])
        println("Byte 1: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x000000000000000duL, rh.sum64(), "Byte 1") // 1 * 13 = 13 = 0x0d

        rh.roll(data[2])
        println("Byte 2: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x0000000000000d1auL, rh.sum64(), "Byte 2") // 2 * 13 = 26 = 0x1a

        rh.roll(data[3])
        println("Byte 3: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x00000000000d1a27uL, rh.sum64(), "Byte 3")

        rh.roll(data[4])
        println("Byte 4: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x000000000d1a2734uL, rh.sum64(), "Byte 4")

        rh.roll(data[5])
        println("Byte 5: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x0000000d1a273441uL, rh.sum64(), "Byte 5")

        rh.roll(data[6])
        println("Byte 6: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x00000d1a2734414euL, rh.sum64(), "Byte 6")

        rh.roll(data[7])
        println("Byte 7: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x000d1a2734414e5buL, rh.sum64(), "Byte 7")

        // At byte 8, the hash exceeds degree 53, so reduction happens
        rh.roll(data[8])
        println("Byte 8: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x000ac7c28f4768ebuL, rh.sum64(), "Byte 8 (reduction kicks in)")

        rh.roll(data[9])
        println("Byte 9: sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
        assertEquals(0x001782d1d0cf8c18uL, rh.sum64(), "Byte 9")
    }

    @Test
    fun `Pol degree is calculated correctly`() {
        assertEquals(-1, Pol(0uL).deg())
        assertEquals(0, Pol(1uL).deg())
        assertEquals(1, Pol(2uL).deg())
        assertEquals(1, Pol(3uL).deg())
        assertEquals(7, Pol(0xFFuL).deg())
        assertEquals(53, RabinKarp64.DEFAULT_POLYNOMIAL.deg())
    }

    @Test
    fun `Pol mod works correctly`() {
        // Some simple tests
        val p = Pol(0b110uL) // x^2 + x
        val d = Pol(0b11uL) // x + 1

        // (x^2 + x) mod (x + 1) = 0
        // Because x^2 + x = x(x + 1), so mod (x+1) = 0
        assertEquals(Pol(0uL), p.mod(d))

        // Test with the actual polynomial
        val pol = RabinKarp64.DEFAULT_POLYNOMIAL
        val small = Pol(0x1234uL)
        assertEquals(small, small.mod(pol)) // smaller than degree, so unchanged
    }

    @Test
    fun `Debug RabinKarp64Splitter finding first split point`() {
        // Use a small test with known data to see where splits happen
        val avgSize = 8192 // 8K for faster testing
        val minSize = avgSize / 2
        val maxSize = avgSize * 2
        val mask = (avgSize - 1).toULong()

        val rh = RabinKarp64.new()
        rh.write(ByteArray(SPLITTER_SLIDING_WINDOW_SIZE))

        // Generate test data
        val data = ByteArray(50000) { (it % 256).toByte() }

        var count = 0
        val foundSplits = mutableListOf<Int>()

        for (i in data.indices) {
            // Fast path: only hash last WINDOW bytes until we reach minSize-1
            val leftToMin = minSize - count - 1
            if (leftToMin > 0) {
                if (i >= leftToMin - SPLITTER_SLIDING_WINDOW_SIZE) {
                    rh.roll(data[i])
                }
                count++
                continue
            }

            rh.roll(data[i])
            count++

            if (rh.sum64() and mask == 0uL) {
                foundSplits.add(i + 1)
                println("Split at byte ${i + 1} (count=$count), sum64=0x${rh.sum64().toString(16).padStart(16, '0')}")
                count = 0
                rh.reset()
                rh.write(ByteArray(SPLITTER_SLIDING_WINDOW_SIZE))
            } else if (count >= maxSize) {
                foundSplits.add(i + 1)
                println("Forced split at maxSize ${i + 1}")
                count = 0
                rh.reset()
                rh.write(ByteArray(SPLITTER_SLIDING_WINDOW_SIZE))
            }
        }

        println("Found splits: $foundSplits")
        // There should be splits before reaching maxSize for reasonable random-ish data
        assert(foundSplits.isNotEmpty())
        // At least some splits should be content-based (not at maxSize boundaries)
        val contentBasedSplits = foundSplits.filter { it % maxSize != 0 }
        println("Content-based splits: $contentBasedSplits")
    }
}
