package org.kopiaKt.core.pack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId

/**
 * Tests for PackBlobBuilder - builds pack blobs from content entries.
 *
 * Pack blob structure:
 * [PREAMBLE] [CONTENT BLOCKS] [LOCAL INDEX (encrypted)] [POSTAMBLE]
 *
 * The builder:
 * 1. Generates a random preamble
 * 2. Writes content blocks sequentially (already encrypted)
 * 3. Builds and encrypts a local index
 * 4. Appends a postamble with recovery info
 */
class PackBlobBuilderTest {

    @Nested
    @DisplayName("Basic Building")
    inner class BasicBuildingTests {

        @Test
        fun `should build empty pack blob with only preamble and postamble`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            val (packData, contentInfos) = builder.build()

            // Should have preamble + local index + postamble
            assertTrue(packData.size >= 32, "Pack should have at least preamble")
            assertTrue(contentInfos.isEmpty(), "Empty pack should have no content infos")

            // Should have valid postamble
            val postamble = PackBlobPostamble.findPostamble(packData)
            assertNotNull(postamble)
        }

        @Test
        fun `should build pack blob with single content`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            val contentId = ContentId.parse("0123456789abcdef0123456789abcdef")
            val encryptedData = ByteArray(100) { it.toByte() }

            builder.addContent(contentId, encryptedData, originalLength = 72u)

            val (packData, contentInfos) = builder.build()

            assertEquals(1, contentInfos.size)
            val info = contentInfos[0]
            assertEquals(contentId, info.contentId)
            assertEquals(100u, info.packedLength)
            assertEquals(72u, info.originalLength)
            assertEquals(32u, info.packOffset) // After preamble
        }

        @Test
        fun `should build pack blob with multiple contents`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            val contents = listOf(
                ContentId.parse("aaaa000011112222") to ByteArray(50) { 0xAA.toByte() },
                ContentId.parse("bbbb333344445555") to ByteArray(75) { 0xBB.toByte() },
                ContentId.parse("cccc666677778888") to ByteArray(100) { 0xCC.toByte() }
            )

            for ((contentId, data) in contents) {
                builder.addContent(contentId, data, originalLength = (data.size - 28).toUInt())
            }

            val (packData, contentInfos) = builder.build()

            assertEquals(3, contentInfos.size)

            // Verify offsets are sequential
            var expectedOffset = 32u // After preamble
            for ((index, info) in contentInfos.withIndex()) {
                assertEquals(contents[index].first, info.contentId)
                assertEquals(expectedOffset, info.packOffset)
                expectedOffset += info.packedLength
            }
        }

        @Test
        fun `should extract content data from built pack`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            val contentId = ContentId.parse("1234567890abcdef")
            val originalData = ByteArray(100) { (it * 3).toByte() }

            builder.addContent(contentId, originalData, originalLength = 72u)

            val (packData, contentInfos) = builder.build()
            val info = contentInfos[0]

            // Extract content from pack
            val extractedData = packData.copyOfRange(
                info.packOffset.toInt(),
                info.packOffset.toInt() + info.packedLength.toInt()
            )

            assertEquals(originalData.toList(), extractedData.toList())
        }
    }

    @Nested
    @DisplayName("Preamble Handling")
    inner class PreambleTests {

        @Test
        fun `should generate random preamble of specified length`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 64,
                encryptionOverhead = 28
            )

            val (packData1, _) = builder.build()

            val builder2 = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 64,
                encryptionOverhead = 28
            )

            val (packData2, _) = builder2.build()

            // Preambles should be different (random)
            val preamble1 = packData1.copyOfRange(0, 64)
            val preamble2 = packData2.copyOfRange(0, 64)

            // Very unlikely to be equal if truly random
            assertTrue(
                !preamble1.contentEquals(preamble2),
                "Preambles should be randomly generated"
            )
        }

        @Test
        fun `should use custom preamble if provided`() {
            val customPreamble = ByteArray(32) { 0x42.toByte() }

            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28,
                preamble = customPreamble
            )

            val (packData, _) = builder.build()

            val preamble = packData.copyOfRange(0, 32)
            assertEquals(customPreamble.toList(), preamble.toList())
        }
    }

    @Nested
    @DisplayName("Local Index")
    inner class LocalIndexTests {

        @Test
        fun `should include local index in pack blob`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                ByteArray(100),
                originalLength = 72u
            )

            val (packData, _) = builder.build()

            // Should have postamble pointing to local index
            val postamble = PackBlobPostamble.findPostamble(packData)
            assertNotNull(postamble)
            assertTrue(postamble!!.localIndexLength > 0u)
            assertTrue(postamble.localIndexOffset > 0u)
        }

        @Test
        fun `should be able to recover content infos from local index`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            val testContents = listOf(
                ContentId.parse("aaaa000011112222") to ByteArray(50),
                ContentId.parse("bbbb333344445555") to ByteArray(75),
                ContentId.parse("cccc666677778888") to ByteArray(100)
            )

            for ((contentId, data) in testContents) {
                builder.addContent(contentId, data, originalLength = (data.size - 28).toUInt())
            }

            val (packData, originalInfos) = builder.build()

            // Use PackBlobReader to recover
            val recoveredInfos = PackBlobReader.recoverIndex(packData, encryptionOverhead = 28u)

            assertNotNull(recoveredInfos)
            assertEquals(originalInfos.size, recoveredInfos!!.size)

            // Verify content IDs match
            val originalIds = originalInfos.map { it.contentId }.toSet()
            val recoveredIds = recoveredInfos.map { it.contentId }.toSet()
            assertEquals(originalIds, recoveredIds)
        }
    }

    @Nested
    @DisplayName("Timestamps")
    inner class TimestampTests {

        @Test
        fun `should set timestamp on content infos`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28,
                timestampSeconds = 1700000000L
            )

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                ByteArray(100),
                originalLength = 72u
            )

            val (_, contentInfos) = builder.build()

            assertEquals(1700000000L, contentInfos[0].timestampSeconds)
        }

        @Test
        fun `should use current time if not specified`() {
            val beforeTime = System.currentTimeMillis() / 1000

            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                ByteArray(100),
                originalLength = 72u
            )

            val (_, contentInfos) = builder.build()

            val afterTime = System.currentTimeMillis() / 1000

            assertTrue(contentInfos[0].timestampSeconds >= beforeTime)
            assertTrue(contentInfos[0].timestampSeconds <= afterTime)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `should handle empty content`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            // Empty encrypted content (just encryption overhead)
            val emptyEncrypted = ByteArray(28)

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                emptyEncrypted,
                originalLength = 0u
            )

            val (packData, contentInfos) = builder.build()

            assertEquals(1, contentInfos.size)
            assertEquals(28u, contentInfos[0].packedLength)
            assertEquals(0u, contentInfos[0].originalLength)
        }

        @Test
        fun `should handle large content`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            // 1MB content
            val largeContent = ByteArray(1024 * 1024) { (it % 256).toByte() }

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                largeContent,
                originalLength = (largeContent.size - 28).toUInt()
            )

            val (packData, contentInfos) = builder.build()

            assertEquals(1, contentInfos.size)
            assertEquals(largeContent.size.toUInt(), contentInfos[0].packedLength)
        }

        @Test
        fun `should track current size`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            assertEquals(32, builder.currentSize()) // Just preamble

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                ByteArray(100),
                originalLength = 72u
            )

            assertEquals(132, builder.currentSize()) // Preamble + content
        }

        @Test
        fun `should reject adding content after build`() {
            val builder = PackBlobBuilder(
                packBlobId = BlobId.packBlob("test123456789012"),
                preambleLength = 32,
                encryptionOverhead = 28
            )

            builder.build()

            assertThrows<IllegalStateException> {
                builder.addContent(
                    ContentId.parse("1234567890abcdef"),
                    ByteArray(100),
                    originalLength = 72u
                )
            }
        }
    }

    @Nested
    @DisplayName("Pack Blob ID")
    inner class PackBlobIdTests {

        @Test
        fun `should use provided pack blob ID in content infos`() {
            val packBlobId = BlobId.packBlob("mypack1234567890")

            val builder = PackBlobBuilder(
                packBlobId = packBlobId,
                preambleLength = 32,
                encryptionOverhead = 28
            )

            builder.addContent(
                ContentId.parse("1234567890abcdef"),
                ByteArray(100),
                originalLength = 72u
            )

            val (_, contentInfos) = builder.build()

            assertEquals(packBlobId, contentInfos[0].packBlobId)
        }
    }
}
