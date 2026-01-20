package org.kopiaKt.core.pack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo

/**
 * Tests for PackBlobReader - reads and extracts data from pack blobs.
 */
class PackBlobReaderTest {

    @Nested
    @DisplayName("Index Recovery")
    inner class IndexRecoveryTests {

        @Test
        fun `should recover index from valid pack blob`() {
            // Build a test pack blob
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            val testContents = listOf(
                ContentId.parse("1111000022223333") to ByteArray(50),
                ContentId.parse("4444555566667777") to ByteArray(75),
                ContentId.parse("8888999900001111") to ByteArray(100)
            )

            for ((contentId, data) in testContents) {
                builder.addContent(contentId, data, originalLength = (data.size - 28).toUInt())
            }

            val (packData, originalInfos) = builder.build()

            // Recover index
            val recoveredInfos = PackBlobReader.recoverIndex(packData, encryptionOverhead = 28u)

            assertNotNull(recoveredInfos)
            assertEquals(originalInfos.size, recoveredInfos!!.size)

            // Verify all content IDs recovered
            val originalIds = originalInfos.map { it.contentId.toString() }.sorted()
            val recoveredIds = recoveredInfos.map { it.contentId.toString() }.sorted()
            assertEquals(originalIds, recoveredIds)
        }

        @Test
        fun `should recover offsets and lengths correctly`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 0
            )

            val contentId = ContentId.parse("abcd1234efab5678")
            val contentData = ByteArray(100) { it.toByte() }

            builder.addContent(contentId, contentData, originalLength = 100u)

            val (packData, originalInfos) = builder.build()
            val recoveredInfos = PackBlobReader.recoverIndex(packData)

            assertNotNull(recoveredInfos)
            assertEquals(1, recoveredInfos!!.size)

            val original = originalInfos[0]
            val recovered = recoveredInfos[0]

            assertEquals(original.packOffset, recovered.packOffset)
            assertEquals(original.packedLength, recovered.packedLength)
        }

        @Test
        fun `should return null for invalid pack blob`() {
            val invalidData = ByteArray(100) { it.toByte() }

            val recovered = PackBlobReader.recoverIndex(invalidData)

            assertNull(recovered)
        }

        @Test
        fun `should return null for empty data`() {
            val recovered = PackBlobReader.recoverIndex(ByteArray(0))
            assertNull(recovered)
        }

        @Test
        fun `should return null for corrupted postamble`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 0
            )

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                ByteArray(50),
                originalLength = 50u
            )

            val (packData, _) = builder.build()

            // Corrupt the last byte (postamble length)
            val corrupted = packData.copyOf()
            corrupted[corrupted.size - 1] = 0xFF.toByte()

            val recovered = PackBlobReader.recoverIndex(corrupted)
            assertNull(recovered)
        }
    }

    @Nested
    @DisplayName("Content Extraction")
    inner class ContentExtractionTests {

        @Test
        fun `should extract content at specified offset`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 0
            )

            val contentData = ByteArray(100) { (it * 3).toByte() }
            val contentId = ContentId.parse("1234567890abcdef")

            builder.addContent(contentId, contentData, originalLength = 100u)

            val (packData, contentInfos) = builder.build()
            val info = contentInfos[0]

            val extracted = PackBlobReader.extractContent(
                packData,
                info.packOffset.toInt(),
                info.packedLength.toInt()
            )

            assertEquals(contentData.toList(), extracted.toList())
        }

        @Test
        fun `should extract content using ContentInfo`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 0
            )

            val contentData = ByteArray(100) { (it * 5).toByte() }
            val contentId = ContentId.parse("fedcba9876543210")

            builder.addContent(contentId, contentData, originalLength = 100u)

            val (packData, contentInfos) = builder.build()
            val info = contentInfos[0]

            val extracted = PackBlobReader.extractContent(packData, info)

            assertEquals(contentData.toList(), extracted.toList())
        }

        @Test
        fun `should extract multiple contents from same pack`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 0
            )

            val contents = listOf(
                ContentId.parse("aaaa000011112222") to ByteArray(50) { 0xAA.toByte() },
                ContentId.parse("bbbb333344445555") to ByteArray(75) { 0xBB.toByte() },
                ContentId.parse("cccc666677778888") to ByteArray(100) { 0xCC.toByte() }
            )

            for ((contentId, data) in contents) {
                builder.addContent(contentId, data, originalLength = data.size.toUInt())
            }

            val (packData, contentInfos) = builder.build()

            // Extract each content and verify
            for ((index, info) in contentInfos.withIndex()) {
                val extracted = PackBlobReader.extractContent(packData, info)
                assertEquals(contents[index].second.toList(), extracted.toList())
            }
        }

        @Test
        fun `should throw for negative offset`() {
            val packData = ByteArray(100)

            assertThrows<IllegalArgumentException> {
                PackBlobReader.extractContent(packData, -1, 10)
            }
        }

        @Test
        fun `should throw for negative length`() {
            val packData = ByteArray(100)

            assertThrows<IllegalArgumentException> {
                PackBlobReader.extractContent(packData, 0, -1)
            }
        }

        @Test
        fun `should throw for out of bounds extraction`() {
            val packData = ByteArray(100)

            assertThrows<IllegalArgumentException> {
                PackBlobReader.extractContent(packData, 90, 20)
            }
        }
    }

    @Nested
    @DisplayName("Postamble Parsing")
    inner class PostambleParsingTests {

        @Test
        fun `should parse postamble from pack blob`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 0
            )

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                ByteArray(100),
                originalLength = 100u
            )

            val (packData, _) = builder.build()

            val postamble = PackBlobReader.parsePostamble(packData)

            assertNotNull(postamble)
            assertTrue(postamble!!.localIndexOffset > 0u)
            assertTrue(postamble.localIndexLength > 0u)
        }

        @Test
        fun `should return null for invalid data`() {
            val invalidData = ByteArray(100)

            val postamble = PackBlobReader.parsePostamble(invalidData)

            assertNull(postamble)
        }
    }

    @Nested
    @DisplayName("Pack Info")
    inner class PackInfoTests {

        @Test
        fun `should get pack info from valid pack blob`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 0
            )

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                ByteArray(100),
                originalLength = 100u
            )

            val (packData, _) = builder.build()

            val info = PackBlobReader.getPackInfo(packData)

            assertNotNull(info)
            assertEquals(packData.size, info!!.totalSize)
            assertTrue(info.localIndexOffset > 0)
            assertTrue(info.localIndexLength > 0)
            assertEquals(16, info.localIndexIV.size) // SHA-256 last 16 bytes
        }

        @Test
        fun `should return null for invalid pack blob`() {
            val invalidData = ByteArray(100)

            val info = PackBlobReader.getPackInfo(invalidData)

            assertNull(info)
        }

        @Test
        fun `contentAreaEnd should point to local index offset`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 0
            )

            val contentData = ByteArray(100)
            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                contentData,
                originalLength = 100u
            )

            val (packData, _) = builder.build()
            val info = PackBlobReader.getPackInfo(packData)

            assertNotNull(info)
            // Content area ends where local index starts
            assertEquals(info!!.localIndexOffset, info.contentAreaEnd)
            // Content area should be preamble (32) + content (100)
            assertEquals(132, info.contentAreaEnd)
        }
    }

    @Nested
    @DisplayName("Round-trip Tests")
    inner class RoundTripTests {

        @Test
        fun `should round-trip multiple contents through builder and reader`() {
            val packBlobId = BlobId.packBlob("roundtrip1234567")
            val builder = PackBlobBuilder(
                packBlobId = packBlobId,
                preambleLength = 32,
                encryptionOverhead = 28,
                timestampSeconds = 1700000000L
            )

            // Add various contents
            val contents = mapOf(
                ContentId.parse("1111222233334444") to Pair(ByteArray(100) { 0x11.toByte() }, 72u),
                ContentId.parse("5555666677778888") to Pair(ByteArray(200) { 0x22.toByte() }, 172u),
                ContentId.parse("9999aaaabbbbcccc") to Pair(ByteArray(50) { 0x33.toByte() }, 22u)
            )

            for ((contentId, pair) in contents) {
                builder.addContent(contentId, pair.first, originalLength = pair.second)
            }

            val (packData, originalInfos) = builder.build()

            // Recover and verify
            val recoveredInfos = PackBlobReader.recoverIndex(packData, encryptionOverhead = 28u)
            assertNotNull(recoveredInfos)

            // Create lookup map
            val recoveredMap = recoveredInfos!!.associateBy { it.contentId }

            for (original in originalInfos) {
                val recovered = recoveredMap[original.contentId]
                assertNotNull(recovered, "Should recover ${original.contentId}")

                assertEquals(original.packBlobId, recovered!!.packBlobId)
                assertEquals(original.packOffset, recovered.packOffset)
                assertEquals(original.packedLength, recovered.packedLength)
                assertEquals(original.timestampSeconds, recovered.timestampSeconds)

                // Extract and verify content
                val extractedData = PackBlobReader.extractContent(packData, recovered)
                val expectedData = contents[original.contentId]!!.first
                assertEquals(expectedData.toList(), extractedData.toList())
            }
        }
    }
}
