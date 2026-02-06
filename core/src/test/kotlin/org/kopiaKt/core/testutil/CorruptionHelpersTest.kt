package org.kopiaKt.core.testutil

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CorruptionHelpersTest {

    @Nested
    @DisplayName("bitFlip")
    inner class BitFlipTests {
        @Test
        fun `should flip specified bit`() {
            val data = byteArrayOf(0x00)
            val result = CorruptionHelpers.bitFlip(data, 0, 0)
            assertEquals(0x01.toByte(), result[0])
        }

        @Test
        fun `should flip high bit`() {
            val data = byteArrayOf(0x00)
            val result = CorruptionHelpers.bitFlip(data, 0, 7)
            assertEquals(0x80.toByte(), result[0])
        }

        @Test
        fun `should not modify original`() {
            val data = byteArrayOf(0x00, 0x01, 0x02)
            CorruptionHelpers.bitFlip(data, 1, 0)
            assertEquals(0x01.toByte(), data[1])
        }

        @Test
        fun `should produce different data`() {
            val data = byteArrayOf(0x55, 0xAA.toByte(), 0xFF.toByte())
            val result = CorruptionHelpers.bitFlip(data, 1, 3)
            assertFalse(data.contentEquals(result))
        }
    }

    @Nested
    @DisplayName("truncate")
    inner class TruncateTests {
        @Test
        fun `should truncate to specified length`() {
            val data = byteArrayOf(1, 2, 3, 4, 5)
            val result = CorruptionHelpers.truncate(data, 3)
            assertArrayEquals(byteArrayOf(1, 2, 3), result)
        }

        @Test
        fun `should return empty for length 0`() {
            val data = byteArrayOf(1, 2, 3)
            val result = CorruptionHelpers.truncate(data, 0)
            assertEquals(0, result.size)
        }
    }

    @Nested
    @DisplayName("standardTruncations")
    inner class StandardTruncationsTests {
        @Test
        fun `should produce standard truncation points`() {
            val data = ByteArray(100) { it.toByte() }
            val truncations = CorruptionHelpers.standardTruncations(data)
            assertTrue(truncations.size >= 4)
            assertEquals("empty", truncations[0].first)
            assertEquals(0, truncations[0].second.size)
        }
    }

    @Nested
    @DisplayName("insertGarbage")
    inner class InsertGarbageTests {
        @Test
        fun `should increase data size by count`() {
            val data = byteArrayOf(1, 2, 3)
            val result = CorruptionHelpers.insertGarbage(data, 1, 5)
            assertEquals(8, result.size)
        }

        @Test
        fun `should preserve data before and after insertion point`() {
            val data = byteArrayOf(1, 2, 3, 4)
            val result = CorruptionHelpers.insertGarbage(data, 2, 3, Random(42))
            assertEquals(1.toByte(), result[0])
            assertEquals(2.toByte(), result[1])
            assertEquals(3.toByte(), result[5])
            assertEquals(4.toByte(), result[6])
        }
    }

    @Nested
    @DisplayName("zeroRange")
    inner class ZeroRangeTests {
        @Test
        fun `should zero specified range`() {
            val data = byteArrayOf(1, 2, 3, 4, 5)
            val result = CorruptionHelpers.zeroRange(data, 1, 3)
            assertArrayEquals(byteArrayOf(1, 0, 0, 0, 5), result)
        }
    }

    @Nested
    @DisplayName("randomBitFlip")
    inner class RandomBitFlipTests {
        @Test
        fun `should produce different data`() {
            val data = ByteArray(100) { it.toByte() }
            val result = CorruptionHelpers.randomBitFlip(data, Random(42))
            assertFalse(data.contentEquals(result))
        }

        @Test
        fun `should differ in exactly one bit`() {
            val data = ByteArray(100) { it.toByte() }
            val result = CorruptionHelpers.randomBitFlip(data, Random(42))
            var diffCount = 0
            for (i in data.indices) {
                val xor = data[i].toInt() xor result[i].toInt()
                diffCount += Integer.bitCount(xor and 0xFF)
            }
            assertEquals(1, diffCount)
        }
    }

    @Nested
    @DisplayName("replaceRange")
    inner class ReplaceRangeTests {
        @Test
        fun `should replace bytes at offset`() {
            val data = byteArrayOf(1, 2, 3, 4, 5)
            val result = CorruptionHelpers.replaceRange(data, 1, byteArrayOf(10, 20))
            assertArrayEquals(byteArrayOf(1, 10, 20, 4, 5), result)
        }
    }

    @Nested
    @DisplayName("appendBytes")
    inner class AppendBytesTests {
        @Test
        fun `should append extra bytes`() {
            val data = byteArrayOf(1, 2, 3)
            val result = CorruptionHelpers.appendBytes(data, byteArrayOf(4, 5))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result)
        }
    }
}
