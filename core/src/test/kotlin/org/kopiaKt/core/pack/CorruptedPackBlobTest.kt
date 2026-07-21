package org.kopiaKt.core.pack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.testutil.CorruptionHelpers

/**
 * Tests that corrupted pack blobs are detected and handled correctly.
 *
 * PackBlobReader.recoverIndex() only reads the postamble and local index,
 * so only corruption in those areas is detectable through that method.
 * Content area corruption is silently returned by extractContent(), which
 * demonstrates why higher-level hash verification is necessary.
 */
class CorruptedPackBlobTest {

    private lateinit var validPackData: ByteArray
    private lateinit var originalInfos: List<ContentInfo>

    private val testContents = listOf(
        ContentId.parse("1111000022223333") to ByteArray(100) { (it * 7).toByte() },
        ContentId.parse("4444555566667777") to ByteArray(200) { (it * 13).toByte() },
        ContentId.parse("8888999900001111") to ByteArray(50) { (it * 3).toByte() },
    )

    @BeforeEach
    fun setup() {
        val builder = PackBlobBuilder(
            packBlobId = BlobId.packBlob("test123456789012"),
            preambleLength = 32,
            encryptionOverhead = 0,
        )
        for ((contentId, data) in testContents) {
            builder.addContent(contentId, data, originalLength = data.size.toUInt())
        }
        val result = builder.build()
        validPackData = result.first
        originalInfos = result.second
    }

    @Test
    fun `valid pack data baseline - recoverIndex succeeds`() {
        val recovered = PackBlobReader.recoverIndex(validPackData)
        assertNotNull(recovered)
        assertEquals(testContents.size, recovered!!.size)
    }

    @Nested
    @DisplayName("Truncation Corruption")
    inner class TruncationTests {

        @Test
        fun `should return null for empty data`() {
            assertNull(PackBlobReader.recoverIndex(ByteArray(0)))
        }

        @Test
        fun `should return null for truncation at 4 bytes`() {
            val truncated = CorruptionHelpers.truncate(validPackData, 4)
            assertNull(PackBlobReader.recoverIndex(truncated))
        }

        @Test
        fun `should return null for truncation at 8 bytes`() {
            val truncated = CorruptionHelpers.truncate(validPackData, 8)
            assertNull(PackBlobReader.recoverIndex(truncated))
        }

        @Test
        fun `should return null for truncation at midpoint`() {
            val truncated = CorruptionHelpers.truncate(validPackData, validPackData.size / 2)
            assertNull(PackBlobReader.recoverIndex(truncated))
        }

        @Test
        fun `should return null for truncation missing last byte`() {
            val truncated = CorruptionHelpers.truncate(validPackData, validPackData.size - 1)
            assertNull(PackBlobReader.recoverIndex(truncated))
        }

        @Test
        fun `should return null for all standard truncation points`() {
            val truncations = CorruptionHelpers.standardTruncations(validPackData)
            for ((description, truncated) in truncations) {
                assertNull(
                    PackBlobReader.recoverIndex(truncated),
                    "recoverIndex should return null for truncation at: $description",
                )
            }
        }
    }

    @Nested
    @DisplayName("Postamble Corruption")
    inner class PostambleCorruption {

        @Test
        fun `should return null when postamble length byte is corrupted`() {
            // The very last byte is the postamble length byte
            val corrupted = CorruptionHelpers.bitFlip(validPackData, validPackData.size - 1)
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should return null when postamble CRC32 area is corrupted`() {
            // CRC32 is 4 bytes before the length byte (bytes at -5, -4, -3, -2)
            val corrupted = CorruptionHelpers.bitFlip(validPackData, validPackData.size - 3)
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should return null when postamble payload is corrupted`() {
            // Corrupt a byte in the postamble payload (version/IV/offsets area)
            // Postamble is at the very end; length byte tells us how long it is
            val postambleLength = validPackData.last().toInt() and 0xFF
            // Payload starts at (size - 1 - postambleLength)
            val payloadStart = validPackData.size - 1 - postambleLength
            val corrupted = CorruptionHelpers.bitFlip(validPackData, payloadStart + 1)
            // CRC check should fail because payload changed but CRC didn't
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should return null when entire postamble area is zeroed`() {
            val postambleLength = validPackData.last().toInt() and 0xFF
            // Zero out the entire postamble + length byte
            val postambleStart = validPackData.size - 1 - postambleLength
            val corrupted = CorruptionHelpers.zeroRange(
                validPackData,
                postambleStart,
                postambleLength + 1,
            )
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should return null when postamble length byte is set to zero`() {
            val corrupted = validPackData.copyOf()
            corrupted[corrupted.size - 1] = 0
            // findPostamble reads the last byte as length; 0 < MIN_POSTAMBLE_SIZE (5)
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should return null when postamble length byte is set to 0xFF`() {
            val corrupted = validPackData.copyOf()
            corrupted[corrupted.size - 1] = 0xFF.toByte()
            // 0xFF = 255, would extend past the start of the data
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }
    }

    @Nested
    @DisplayName("Local Index Corruption")
    inner class LocalIndexCorruption {

        @Test
        fun `should return null when local index version byte is corrupted`() {
            val info = PackBlobReader.getPackInfo(validPackData)
            assertNotNull(info)

            // First byte of local index is the version byte (V1 = 0x01)
            val indexOffset = info!!.localIndexOffset
            val corrupted = validPackData.copyOf()
            corrupted[indexOffset] = 0xFF.toByte()
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should return null when local index header is zeroed`() {
            val info = PackBlobReader.getPackInfo(validPackData)
            assertNotNull(info)

            // Zero out the 8-byte header of the local index
            val corrupted = CorruptionHelpers.zeroRange(
                validPackData,
                info!!.localIndexOffset,
                minOf(8, info.localIndexLength),
            )
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should return null or wrong entries when local index data has bit flips`() {
            val info = PackBlobReader.getPackInfo(validPackData)
            assertNotNull(info)

            // Flip a bit in the middle of the local index (entry data area)
            val indexMidpoint = info!!.localIndexOffset + info.localIndexLength / 2
            if (indexMidpoint < validPackData.size) {
                val corrupted = CorruptionHelpers.bitFlip(validPackData, indexMidpoint)
                val recovered = PackBlobReader.recoverIndex(corrupted)
                // Bit flip in the local index entry data should either:
                // - prevent recovery (null), or
                // - produce entries that differ from the originals
                if (recovered != null) {
                    val differs = recovered.size != originalInfos.size ||
                        recovered.zip(originalInfos).any { (a, b) ->
                            a.contentId != b.contentId ||
                                a.packBlobId != b.packBlobId ||
                                a.packOffset != b.packOffset ||
                                a.packedLength != b.packedLength
                        }
                    assertTrue(
                        differs,
                        "Bit flip in local index should produce entries different from originals",
                    )
                }
                // If recovered is null, the corruption was detected - test passes
            }
        }

        @Test
        fun `should return null when entire local index is zeroed`() {
            val info = PackBlobReader.getPackInfo(validPackData)
            assertNotNull(info)

            val corrupted = CorruptionHelpers.zeroRange(
                validPackData,
                info!!.localIndexOffset,
                info.localIndexLength,
            )
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }
    }

    @Nested
    @DisplayName("Content Area Corruption")
    inner class ContentAreaCorruption {

        @Test
        fun `recoverIndex should succeed despite content area corruption`() {
            // Flip a bit in the first content's data area
            val firstInfo = originalInfos[0]
            val contentOffset = firstInfo.packOffset.toInt() + 5
            val corrupted = CorruptionHelpers.bitFlip(validPackData, contentOffset)

            // recoverIndex only reads postamble + local index, not content area
            val recovered = PackBlobReader.recoverIndex(corrupted)
            assertNotNull(recovered, "Index recovery should succeed despite content corruption")
            assertEquals(originalInfos.size, recovered!!.size)
        }

        @Test
        fun `extractContent should return wrong data when content area is bit-flipped`() {
            val firstInfo = originalInfos[0]
            val contentOffset = firstInfo.packOffset.toInt() + 5
            val corrupted = CorruptionHelpers.bitFlip(validPackData, contentOffset)

            val extractedOriginal = PackBlobReader.extractContent(validPackData, firstInfo)
            val extractedCorrupted = PackBlobReader.extractContent(corrupted, firstInfo)

            assertFalse(
                extractedOriginal.contentEquals(extractedCorrupted),
                "Corrupted content should differ from original",
            )
        }

        @Test
        fun `recoverIndex should succeed despite preamble corruption`() {
            // Corrupt preamble area (first 32 bytes)
            val corrupted = CorruptionHelpers.bitFlip(validPackData, 10)

            val recovered = PackBlobReader.recoverIndex(corrupted)
            assertNotNull(recovered, "Index recovery should succeed despite preamble corruption")
            assertEquals(originalInfos.size, recovered!!.size)
        }

        @Test
        fun `recoverIndex should succeed when content area is zeroed`() {
            // Zero out the content data area (between preamble and local index)
            val info = PackBlobReader.getPackInfo(validPackData)
            assertNotNull(info)

            // Content area is from preamble (32) to localIndexOffset
            val contentAreaStart = 32
            val contentAreaLength = info!!.localIndexOffset - contentAreaStart
            if (contentAreaLength > 0) {
                val corrupted = CorruptionHelpers.zeroRange(
                    validPackData,
                    contentAreaStart,
                    contentAreaLength,
                )
                val recovered = PackBlobReader.recoverIndex(corrupted)
                assertNotNull(recovered, "Index recovery should succeed with zeroed content area")
            }
        }

        @Test
        fun `extracted content should differ when content area is zeroed`() {
            val info = PackBlobReader.getPackInfo(validPackData)
            assertNotNull(info)

            val contentAreaStart = 32
            val contentAreaLength = info!!.localIndexOffset - contentAreaStart
            if (contentAreaLength > 0) {
                val corrupted = CorruptionHelpers.zeroRange(
                    validPackData,
                    contentAreaStart,
                    contentAreaLength,
                )

                for (contentInfo in originalInfos) {
                    val original = PackBlobReader.extractContent(validPackData, contentInfo)
                    val fromCorrupted = PackBlobReader.extractContent(corrupted, contentInfo)

                    // Original data was non-zero patterns, so zeroed area should differ
                    assertFalse(
                        original.contentEquals(fromCorrupted),
                        "Content ${contentInfo.contentId} should differ after zeroing",
                    )
                }
            }
        }
    }

    @Nested
    @DisplayName("Garbage Data")
    inner class GarbageDataTests {

        @Test
        fun `should return null for completely random data`() {
            val garbage = ByteArray(500) { it.toByte() }
            assertNull(PackBlobReader.recoverIndex(garbage))
        }

        @Test
        fun `should return null for single byte`() {
            assertNull(PackBlobReader.recoverIndex(ByteArray(1) { 0x42 }))
        }

        @Test
        fun `should return null for data with inserted garbage at midpoint`() {
            val corrupted = CorruptionHelpers.insertGarbage(
                validPackData,
                validPackData.size / 2,
                50,
            )
            // Insertion shifts all offsets, making the postamble unreadable
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should return null for data with garbage appended`() {
            val corrupted = CorruptionHelpers.appendBytes(
                validPackData,
                ByteArray(100) { 0xAB.toByte() },
            )
            // Appended bytes shift where findPostamble looks for the length byte
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `parsePostamble should return null for garbage data`() {
            val garbage = ByteArray(200) { (it * 17).toByte() }
            assertNull(PackBlobReader.parsePostamble(garbage))
        }

        @Test
        fun `getPackInfo should return null for garbage data`() {
            val garbage = ByteArray(200) { (it * 17).toByte() }
            assertNull(PackBlobReader.getPackInfo(garbage))
        }
    }

    @Nested
    @DisplayName("Boundary Cases")
    inner class BoundaryCases {

        @Test
        fun `should handle pack with single small content`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("single1234567890"),
                preambleLength = 32,
                encryptionOverhead = 0,
            )
            val singleContent = ByteArray(1) { 0x42 }
            builder.addContent(
                ContentId.parse("aabbccdd11223344"),
                singleContent,
                originalLength = 1u,
            )
            val (packData, _) = builder.build()

            // Verify it works uncorrupted
            val recovered = PackBlobReader.recoverIndex(packData)
            assertNotNull(recovered)
            assertEquals(1, recovered!!.size)

            // Corrupt the last byte
            val corrupted = CorruptionHelpers.bitFlip(packData, packData.size - 1)
            assertNull(PackBlobReader.recoverIndex(corrupted))
        }

        @Test
        fun `should handle corruption at exact boundary between content and index`() {
            val info = PackBlobReader.getPackInfo(validPackData)
            assertNotNull(info)

            // Corrupt the last byte of the content area (just before local index)
            val lastContentByte = info!!.localIndexOffset - 1
            if (lastContentByte > 0) {
                val corrupted = CorruptionHelpers.bitFlip(validPackData, lastContentByte)
                // This is still in the content area, so index recovery should succeed
                val recovered = PackBlobReader.recoverIndex(corrupted)
                assertNotNull(recovered, "Corrupting last content byte should not affect index recovery")
            }
        }

        @Test
        fun `should handle corruption at exact boundary between index and postamble`() {
            val info = PackBlobReader.getPackInfo(validPackData)
            assertNotNull(info)

            // Corrupt the first byte of the postamble
            val postambleStart = info!!.localIndexOffset + info.localIndexLength
            if (postambleStart < validPackData.size) {
                val corrupted = CorruptionHelpers.bitFlip(validPackData, postambleStart)
                // Postamble CRC check should fail
                assertNull(
                    PackBlobReader.recoverIndex(corrupted),
                    "Corrupting first byte of postamble should cause recovery failure",
                )
            }
        }
    }
}
