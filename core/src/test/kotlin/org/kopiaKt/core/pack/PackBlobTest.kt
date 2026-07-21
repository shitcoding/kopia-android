package org.kopiaKt.core.pack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Tests for Pack Blob format parsing and building.
 *
 * Pack blob structure:
 * [PREAMBLE] [CONTENT BLOCKS] [LOCAL INDEX] [POSTAMBLE]
 *
 * Postamble format (at the very end):
 * - Version varint (always 1)
 * - IV length varint
 * - Local index IV (variable)
 * - Local index offset varint
 * - Local index length varint
 * - CRC32 checksum (4 bytes, big-endian)
 * - Postamble length (1 byte)
 */
class PackBlobTest {

    // ===== Postamble Parsing Tests =====

    @Nested
    @DisplayName("Postamble Parsing")
    inner class PostambleParsingTests {

        @Test
        fun `findPostamble should detect valid postamble at end of data`() {
            val postamble = buildTestPostamble(
                localIndexIV = ByteArray(16) { it.toByte() },
                localIndexOffset = 1000u,
                localIndexLength = 500u,
            )

            // Add some random data before the postamble
            val data = ByteArray(2000) { (it % 256).toByte() } + postamble

            val found = PackBlobPostamble.findPostamble(data)

            assertNotNull(found)
            assertEquals(16, found!!.localIndexIV.size)
            assertEquals(1000u, found.localIndexOffset)
            assertEquals(500u, found.localIndexLength)
        }

        @Test
        fun `findPostamble should return null for empty data`() {
            val found = PackBlobPostamble.findPostamble(ByteArray(0))
            assertNull(found)
        }

        @Test
        fun `findPostamble should return null for data too short`() {
            // Minimum postamble is 5 bytes
            val found = PackBlobPostamble.findPostamble(ByteArray(4))
            assertNull(found)
        }

        @Test
        fun `findPostamble should return null for invalid checksum`() {
            val postamble = buildTestPostamble(
                localIndexIV = ByteArray(16),
                localIndexOffset = 100u,
                localIndexLength = 50u,
            )

            // Corrupt the checksum
            val corrupted = postamble.copyOf()
            corrupted[corrupted.size - 3] = (corrupted[corrupted.size - 3] + 1).toByte()

            val found = PackBlobPostamble.findPostamble(corrupted)
            assertNull(found)
        }

        @Test
        fun `findPostamble should return null for invalid version flag`() {
            // Build a postamble with version 2 (unsupported)
            val buffer = mutableListOf<Byte>()

            // Version 2 (unsupported)
            buffer.addAll(encodeVarint(2u))
            // IV length
            buffer.addAll(encodeVarint(16u))
            // IV
            buffer.addAll(ByteArray(16).toList())
            // Offset
            buffer.addAll(encodeVarint(100u))
            // Length
            buffer.addAll(encodeVarint(50u))

            val payload = buffer.toByteArray()
            val checksum = computeCRC32(payload)

            val result = mutableListOf<Byte>()
            result.addAll(payload.toList())
            result.addAll(checksum.toList())

            val length = result.size
            result.add(length.toByte())

            val found = PackBlobPostamble.findPostamble(result.toByteArray())
            assertNull(found)
        }

        @Test
        fun `findPostamble should handle various IV lengths`() {
            for (ivLength in listOf(12, 16, 24, 32)) {
                val postamble = buildTestPostamble(
                    localIndexIV = ByteArray(ivLength) { it.toByte() },
                    localIndexOffset = 500u,
                    localIndexLength = 200u,
                )

                val found = PackBlobPostamble.findPostamble(postamble)

                assertNotNull(found, "Should find postamble with IV length $ivLength")
                assertEquals(ivLength, found!!.localIndexIV.size)
            }
        }

        @Test
        fun `findPostamble should handle large offsets and lengths`() {
            val largeOffset = 1_000_000u
            val largeLength = 500_000u

            val postamble = buildTestPostamble(
                localIndexIV = ByteArray(16),
                localIndexOffset = largeOffset,
                localIndexLength = largeLength,
            )

            val found = PackBlobPostamble.findPostamble(postamble)

            assertNotNull(found)
            assertEquals(largeOffset, found!!.localIndexOffset)
            assertEquals(largeLength, found.localIndexLength)
        }
    }

    // ===== Postamble Building Tests =====

    @Nested
    @DisplayName("Postamble Building")
    inner class PostambleBuildingTests {

        @Test
        fun `toBytes should create valid postamble`() {
            val iv = ByteArray(16) { (it * 2).toByte() }
            val postamble = PackBlobPostamble(
                localIndexIV = iv,
                localIndexOffset = 1234u,
                localIndexLength = 567u,
            )

            val bytes = postamble.toBytes()

            // Should be parseable
            val parsed = PackBlobPostamble.findPostamble(bytes)
            assertNotNull(parsed)
            assertEquals(iv.toList(), parsed!!.localIndexIV.toList())
            assertEquals(1234u, parsed.localIndexOffset)
            assertEquals(567u, parsed.localIndexLength)
        }

        @Test
        fun `toBytes should include valid CRC32 checksum`() {
            val postamble = PackBlobPostamble(
                localIndexIV = ByteArray(16),
                localIndexOffset = 100u,
                localIndexLength = 50u,
            )

            val bytes = postamble.toBytes()

            // Verify checksum by parsing
            val parsed = PackBlobPostamble.findPostamble(bytes)
            assertNotNull(parsed, "Checksum should be valid")
        }

        @Test
        fun `toBytes result should have length in last byte`() {
            val postamble = PackBlobPostamble(
                localIndexIV = ByteArray(16),
                localIndexOffset = 100u,
                localIndexLength = 50u,
            )

            val bytes = postamble.toBytes()

            // Last byte is the length
            val length = bytes.last().toInt() and 0xFF
            assertEquals(bytes.size - 1, length)
        }

        @Test
        fun `toBytes should throw if postamble too long`() {
            // IV of 200 bytes would make postamble > 255 bytes
            val postamble = PackBlobPostamble(
                localIndexIV = ByteArray(240),
                localIndexOffset = UInt.MAX_VALUE,
                localIndexLength = UInt.MAX_VALUE,
            )

            assertThrows<IllegalArgumentException> {
                postamble.toBytes()
            }
        }
    }

    // ===== Postamble Round-trip Tests =====

    @Nested
    @DisplayName("Postamble Round-trip")
    inner class PostambleRoundtripTests {

        @Test
        fun `postamble should round-trip correctly`() {
            val original = PackBlobPostamble(
                localIndexIV = ByteArray(16) { it.toByte() },
                localIndexOffset = 12345u,
                localIndexLength = 6789u,
            )

            val bytes = original.toBytes()
            val parsed = PackBlobPostamble.findPostamble(bytes)

            assertNotNull(parsed)
            assertEquals(original.localIndexIV.toList(), parsed!!.localIndexIV.toList())
            assertEquals(original.localIndexOffset, parsed.localIndexOffset)
            assertEquals(original.localIndexLength, parsed.localIndexLength)
        }

        @Test
        fun `postamble with embedded data should round-trip`() {
            val original = PackBlobPostamble(
                localIndexIV = ByteArray(16) { 0xFF.toByte() },
                localIndexOffset = 999999u,
                localIndexLength = 888888u,
            )

            // Embed in larger data
            val prefix = ByteArray(5000) { (it % 256).toByte() }
            val postambleBytes = original.toBytes()
            val fullData = prefix + postambleBytes

            val parsed = PackBlobPostamble.findPostamble(fullData)

            assertNotNull(parsed)
            assertEquals(original.localIndexOffset, parsed!!.localIndexOffset)
            assertEquals(original.localIndexLength, parsed.localIndexLength)
        }
    }

    // ===== Pack Blob Structure Tests =====

    @Nested
    @DisplayName("Pack Blob Structure")
    inner class PackBlobStructureTests {

        @Test
        fun `should extract local index from pack blob`() {
            // Simulate a pack blob with:
            // - Some preamble (32 bytes)
            // - Content data (100 bytes)
            // - Local index (50 bytes encrypted)
            // - Postamble

            val preamble = ByteArray(32) { 0xAA.toByte() }
            val contentData = ByteArray(100) { (it % 256).toByte() }
            val localIndex = ByteArray(50) { it.toByte() }

            val localIndexOffset = preamble.size + contentData.size

            val postamble = PackBlobPostamble(
                localIndexIV = ByteArray(16) { it.toByte() },
                localIndexOffset = localIndexOffset.toUInt(),
                localIndexLength = localIndex.size.toUInt(),
            )

            val packBlob = preamble + contentData + localIndex + postamble.toBytes()

            // Parse the postamble
            val parsedPostamble = PackBlobPostamble.findPostamble(packBlob)
            assertNotNull(parsedPostamble)

            // Extract local index
            val extractedIndex = packBlob.copyOfRange(
                parsedPostamble!!.localIndexOffset.toInt(),
                parsedPostamble.localIndexOffset.toInt() + parsedPostamble.localIndexLength.toInt(),
            )

            assertEquals(localIndex.toList(), extractedIndex.toList())
        }
    }

    // ===== Go Compatibility Tests =====

    @Nested
    @DisplayName("Go Compatibility")
    inner class GoCompatibilityTests {

        @Test
        fun `postamble format should match Go implementation`() {
            // Go postamble format:
            // - Version varint (1)
            // - IV length varint
            // - IV bytes
            // - Offset varint
            // - Length varint
            // - CRC32 (4 bytes big-endian)
            // - Total length (1 byte)

            val postamble = PackBlobPostamble(
                localIndexIV = ByteArray(16),
                localIndexOffset = 1000u,
                localIndexLength = 500u,
            )

            val bytes = postamble.toBytes()

            // Verify structure by decoding varints manually
            var offset = 0

            // Version should be 1
            val (version, versionLen) = decodeVarint(bytes, offset)
            assertEquals(1uL, version)
            offset += versionLen

            // IV length should be 16
            val (ivLen, ivLenLen) = decodeVarint(bytes, offset)
            assertEquals(16uL, ivLen)
            offset += ivLenLen

            // Skip IV
            offset += 16

            // Offset should be 1000
            val (indexOffset, offsetLen) = decodeVarint(bytes, offset)
            assertEquals(1000uL, indexOffset)
            offset += offsetLen

            // Length should be 500
            val (indexLength, lengthLen) = decodeVarint(bytes, offset)
            assertEquals(500uL, indexLength)
        }

        @Test
        fun `should handle Go-style varint encoding`() {
            // Test various values that exercise varint encoding
            val testCases = listOf(
                0u to 1, // 0 -> 1 byte
                127u to 1, // 127 -> 1 byte
                128u to 2, // 128 -> 2 bytes
                16383u to 2, // Max 2-byte value
                16384u to 3, // Min 3-byte value
                2097151u to 3, // Max 3-byte value
            )

            for ((value, expectedBytes) in testCases) {
                val encoded = encodeVarint(value)
                assertEquals(
                    expectedBytes,
                    encoded.size,
                    "Value $value should encode to $expectedBytes bytes",
                )

                // Verify decode
                val (decoded, len) = decodeVarint(encoded.toByteArray(), 0)
                assertEquals(value.toULong(), decoded)
                assertEquals(expectedBytes, len)
            }
        }
    }

    // ===== Helper Methods =====

    private fun buildTestPostamble(
        localIndexIV: ByteArray,
        localIndexOffset: UInt,
        localIndexLength: UInt,
    ): ByteArray {
        val buffer = mutableListOf<Byte>()

        // Version 1
        buffer.addAll(encodeVarint(1u))
        // IV length
        buffer.addAll(encodeVarint(localIndexIV.size.toUInt()))
        // IV
        buffer.addAll(localIndexIV.toList())
        // Offset
        buffer.addAll(encodeVarint(localIndexOffset))
        // Length
        buffer.addAll(encodeVarint(localIndexLength))

        val payload = buffer.toByteArray()
        val checksum = computeCRC32(payload)

        val result = mutableListOf<Byte>()
        result.addAll(payload.toList())
        result.addAll(checksum.toList())

        val length = result.size
        result.add(length.toByte())

        return result.toByteArray()
    }

    private fun encodeVarint(value: UInt): List<Byte> = encodeVarint(value.toULong())

    private fun encodeVarint(value: ULong): List<Byte> {
        val result = mutableListOf<Byte>()
        var v = value
        while (v >= 0x80uL) {
            result.add(((v and 0x7FuL) or 0x80uL).toByte())
            v = v shr 7
        }
        result.add(v.toByte())
        return result
    }

    private fun decodeVarint(data: ByteArray, startOffset: Int): Pair<ULong, Int> {
        var result = 0uL
        var shift = 0
        var offset = startOffset

        while (offset < data.size) {
            val b = data[offset].toInt() and 0xFF
            result = result or ((b.toULong() and 0x7FuL) shl shift)
            offset++

            if ((b and 0x80) == 0) {
                break
            }
            shift += 7
        }

        return result to (offset - startOffset)
    }

    private fun computeCRC32(data: ByteArray): ByteArray {
        val crc = CRC32()
        crc.update(data)
        val checksum = crc.value.toInt()

        return ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(checksum)
            .array()
    }
}
