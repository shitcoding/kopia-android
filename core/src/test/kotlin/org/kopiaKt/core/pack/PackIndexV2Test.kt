package org.kopiaKt.core.pack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo

/**
 * Tests for Pack Index V2 format parsing and building.
 *
 * V2 index format:
 * - 17-byte header: [version=2][keySize][entrySize(2b)][entryCount(4b)]
 *                   [packCount(4b)][formatCount(1b)][baseTimestamp(4b)]
 * - Sorted entries: [key(keySize)][entry(16-19b variable)]
 * - Pack infos: [length(1b)][offset(4b)] per unique pack blob
 * - Format infos: [compressionId(4b)][formatVersion(1b)][encryptionKeyId(1b)] per unique format
 * - Extra data: pack blob ID strings
 *
 * Entry format (16-19 bytes):
 * - Bytes 0-3: timestamp offset (relative to base, big-endian)
 * - Bytes 4-7: deleted flag (bit 31) + pack offset (bits 0-30)
 * - Bytes 8-10: original length (24-bit big-endian)
 * - Bytes 11-13: packed length (24-bit big-endian)
 * - Bytes 14-15: pack blob ID index (16-bit big-endian)
 * Optional:
 * - Byte 16: format ID index (if > 1 unique format)
 * - Byte 17: pack blob ID bits 16-23 (if > 65536 packs)
 * - Byte 18: high-order length bits (if any length >= 16 MiB)
 */
class PackIndexV2Test {

    // ===== Header Parsing Tests =====

    @Test
    fun `parseHeader should read valid V2 header`() {
        // 1700000000 = 0x6553F100 in hex (big-endian)
        // Header: version=2, keySize=17, entrySize=16, entryCount=5, packCount=2, formatCount=1, baseTimestamp=1700000000
        val header = byteArrayOf(
            0x02,                   // version
            0x11,                   // keySize = 17
            0x00, 0x10,             // entrySize = 16 (minimum)
            0x00, 0x00, 0x00, 0x05, // entryCount = 5
            0x00, 0x00, 0x00, 0x02, // packCount = 2
            0x01,                   // formatCount = 1
            0x65, 0x53, 0xF1.toByte(), 0x00 // baseTimestamp = 1700000000 (0x6553F100)
        )

        val info = PackIndexV2.parseHeader(header)

        assertEquals(2, info.version)
        assertEquals(17, info.keySize)
        assertEquals(16, info.entrySize)
        assertEquals(5, info.entryCount)
        assertEquals(2u, info.packCount)
        assertEquals(1, info.formatCount)
        assertEquals(1700000000u, info.baseTimestamp)
    }

    @Test
    fun `parseHeader should reject wrong version`() {
        val header = byteArrayOf(
            0x01, // wrong version (V1)
            0x11, 0x00, 0x10, 0x00, 0x00, 0x00, 0x05,
            0x00, 0x00, 0x00, 0x02, 0x01, 0x65, 0x54, 0xEC.toByte(), 0x00
        )

        assertThrows<IllegalArgumentException> {
            PackIndexV2.parseHeader(header)
        }
    }

    @Test
    fun `parseHeader should reject too short header`() {
        val header = byteArrayOf(0x02, 0x11, 0x00, 0x10, 0x00, 0x00)

        assertThrows<IllegalArgumentException> {
            PackIndexV2.parseHeader(header)
        }
    }

    // ===== Index Building Tests =====

    @Test
    fun `build should create valid V2 index with single entry`() {
        val contentId = ContentId.parse("0123456789abcdef0123456789abcdef")
        val info = ContentInfo(
            contentId = contentId,
            packBlobId = BlobId("p1234567890abcdef"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 800u,
            packOffset = 0u,
            compressionHeaderId = 0x1100, // ZSTD
            deleted = false,
            formatVersion = 1,
            encryptionKeyId = 0
        )

        val indexData = PackIndexV2.build(listOf(info))

        // Parse back and verify
        val index = PackIndexV2.open(indexData)
        assertNotNull(index)

        val retrieved = index.getInfo(contentId)
        assertNotNull(retrieved)
        assertEquals(info.packBlobId.value, retrieved!!.packBlobId.value)
        assertEquals(info.timestampSeconds, retrieved.timestampSeconds)
        assertEquals(info.originalLength, retrieved.originalLength)
        assertEquals(info.packedLength, retrieved.packedLength)
        assertEquals(info.packOffset, retrieved.packOffset)
        assertEquals(info.compressionHeaderId, retrieved.compressionHeaderId)
        assertEquals(info.deleted, retrieved.deleted)
    }

    @Test
    fun `build should create valid V2 index with compression support`() {
        val entries = listOf(
            ContentInfo(
                contentId = ContentId.parse("1111111111111111"),
                packBlobId = BlobId("p1234567890"),
                timestampSeconds = 1700000000L,
                originalLength = 2000u,
                packedLength = 1500u,
                packOffset = 0u,
                compressionHeaderId = 0x1000 // GZIP
            ),
            ContentInfo(
                contentId = ContentId.parse("2222222222222222"),
                packBlobId = BlobId("p1234567890"),
                timestampSeconds = 1700000000L,
                originalLength = 3000u,
                packedLength = 2000u,
                packOffset = 1500u,
                compressionHeaderId = 0x1100 // ZSTD
            )
        )

        val indexData = PackIndexV2.build(entries)
        val index = PackIndexV2.open(indexData)

        val e1 = index.getInfo(ContentId.parse("1111111111111111"))
        assertNotNull(e1)
        assertEquals(0x1000, e1!!.compressionHeaderId)
        assertEquals(2000u, e1.originalLength)

        val e2 = index.getInfo(ContentId.parse("2222222222222222"))
        assertNotNull(e2)
        assertEquals(0x1100, e2!!.compressionHeaderId)
        assertEquals(3000u, e2.originalLength)
    }

    @Test
    fun `build should handle multiple entries sorted`() {
        val entries = listOf(
            createTestContentInfo("3333333333333333", 3),
            createTestContentInfo("1111111111111111", 1),
            createTestContentInfo("2222222222222222", 2)
        )

        val indexData = PackIndexV2.build(entries)
        val index = PackIndexV2.open(indexData)

        // Verify entries are sorted lexicographically
        val allEntries = index.iterate().toList()
        assertEquals(3, allEntries.size)
        assertEquals("1111111111111111", allEntries[0].contentId.toString())
        assertEquals("2222222222222222", allEntries[1].contentId.toString())
        assertEquals("3333333333333333", allEntries[2].contentId.toString())
    }

    @Test
    fun `build should support encryption key ID`() {
        val info = ContentInfo(
            contentId = ContentId.parse("0123456789abcdef"),
            packBlobId = BlobId("p1234567890"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 800u,
            packOffset = 0u,
            compressionHeaderId = 0,
            deleted = false,
            formatVersion = 1,
            encryptionKeyId = 5
        )

        val indexData = PackIndexV2.build(listOf(info))
        val index = PackIndexV2.open(indexData)

        val retrieved = index.getInfo(ContentId.parse("0123456789abcdef"))
        assertNotNull(retrieved)
        assertEquals(5.toByte(), retrieved!!.encryptionKeyId)
    }

    // ===== Multiple Formats Tests =====

    @Test
    fun `build should handle multiple unique formats`() {
        val entries = listOf(
            ContentInfo(
                contentId = ContentId.parse("1111"),
                packBlobId = BlobId("p123"),
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 90u,
                packOffset = 0u,
                compressionHeaderId = 0x1000, // GZIP
                formatVersion = 1,
                encryptionKeyId = 0
            ),
            ContentInfo(
                contentId = ContentId.parse("2222"),
                packBlobId = BlobId("p123"),
                timestampSeconds = 1700000000L,
                originalLength = 200u,
                packedLength = 180u,
                packOffset = 90u,
                compressionHeaderId = 0x1100, // ZSTD
                formatVersion = 1,
                encryptionKeyId = 0
            ),
            ContentInfo(
                contentId = ContentId.parse("3333"),
                packBlobId = BlobId("p123"),
                timestampSeconds = 1700000000L,
                originalLength = 300u,
                packedLength = 250u,
                packOffset = 270u,
                compressionHeaderId = 0x1000, // GZIP again
                formatVersion = 2, // Different format version
                encryptionKeyId = 1
            )
        )

        val indexData = PackIndexV2.build(entries)
        val index = PackIndexV2.open(indexData)

        // Verify all entries can be retrieved with correct formats
        val e1 = index.getInfo(ContentId.parse("1111"))
        assertNotNull(e1)
        assertEquals(0x1000, e1!!.compressionHeaderId)
        assertEquals(1.toByte(), e1.formatVersion)
        assertEquals(0.toByte(), e1.encryptionKeyId)

        val e2 = index.getInfo(ContentId.parse("2222"))
        assertNotNull(e2)
        assertEquals(0x1100, e2!!.compressionHeaderId)

        val e3 = index.getInfo(ContentId.parse("3333"))
        assertNotNull(e3)
        assertEquals(0x1000, e3!!.compressionHeaderId)
        assertEquals(2.toByte(), e3.formatVersion)
        assertEquals(1.toByte(), e3.encryptionKeyId)
    }

    // ===== Multiple Pack Blobs Tests =====

    @Test
    fun `build should handle multiple pack blob IDs`() {
        val entries = listOf(
            ContentInfo(
                contentId = ContentId.parse("1111"),
                packBlobId = BlobId("p111"),
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 90u,
                packOffset = 0u
            ),
            ContentInfo(
                contentId = ContentId.parse("2222"),
                packBlobId = BlobId("p222"),
                timestampSeconds = 1700000000L,
                originalLength = 200u,
                packedLength = 180u,
                packOffset = 0u
            ),
            ContentInfo(
                contentId = ContentId.parse("3333"),
                packBlobId = BlobId("p111"), // Same as first
                timestampSeconds = 1700000000L,
                originalLength = 300u,
                packedLength = 250u,
                packOffset = 90u
            )
        )

        val indexData = PackIndexV2.build(entries)
        val index = PackIndexV2.open(indexData)

        assertEquals("p111", index.getInfo(ContentId.parse("1111"))!!.packBlobId.value)
        assertEquals("p222", index.getInfo(ContentId.parse("2222"))!!.packBlobId.value)
        assertEquals("p111", index.getInfo(ContentId.parse("3333"))!!.packBlobId.value)
    }

    // ===== Edge Cases =====

    @Test
    fun `build should handle empty entry list`() {
        val indexData = PackIndexV2.build(emptyList())
        val index = PackIndexV2.open(indexData)

        assertEquals(0, index.approximateCount())
        assertNull(index.getInfo(ContentId.parse("0000")))
    }

    @Test
    fun `build should handle deleted entries`() {
        val info = ContentInfo(
            contentId = ContentId.parse("0123456789abcdef"),
            packBlobId = BlobId("p1234"),
            timestampSeconds = 1700000000L,
            originalLength = 100u,
            packedLength = 100u,
            packOffset = 1024u,
            deleted = true
        )

        val indexData = PackIndexV2.build(listOf(info))
        val index = PackIndexV2.open(indexData)

        val retrieved = index.getInfo(ContentId.parse("0123456789abcdef"))
        assertNotNull(retrieved)
        assertTrue(retrieved!!.deleted)
        assertEquals(1024u, retrieved.packOffset)
    }

    @Test
    fun `build should handle large original length`() {
        // Test lengths that require high bits (> 16 MiB)
        val largeLength = (20 * 1024 * 1024).toUInt() // 20 MiB
        val info = ContentInfo(
            contentId = ContentId.parse("0123456789abcdef"),
            packBlobId = BlobId("p1234"),
            timestampSeconds = 1700000000L,
            originalLength = largeLength,
            packedLength = largeLength - 1000u,
            packOffset = 0u
        )

        val indexData = PackIndexV2.build(listOf(info))
        val index = PackIndexV2.open(indexData)

        val retrieved = index.getInfo(ContentId.parse("0123456789abcdef"))
        assertNotNull(retrieved)
        assertEquals(largeLength, retrieved!!.originalLength)
        assertEquals(largeLength - 1000u, retrieved.packedLength)
    }

    @Test
    fun `build should handle prefixed content IDs`() {
        val entries = listOf(
            ContentInfo(
                contentId = ContentId.parse("m1111111122223333"),
                packBlobId = BlobId("q1234567890"),
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 90u,
                packOffset = 0u
            )
        )

        val indexData = PackIndexV2.build(entries)
        val index = PackIndexV2.open(indexData)

        val found = index.getInfo(ContentId.parse("m1111111122223333"))
        assertNotNull(found)
        assertEquals("m1111111122223333", found!!.contentId.toString())
    }

    @Test
    fun `build should handle timestamp relative to base`() {
        val baseTimestamp = 1700000000L
        val entries = listOf(
            ContentInfo(
                contentId = ContentId.parse("1111"),
                packBlobId = BlobId("p123"),
                timestampSeconds = baseTimestamp,
                originalLength = 100u,
                packedLength = 90u,
                packOffset = 0u
            ),
            ContentInfo(
                contentId = ContentId.parse("2222"),
                packBlobId = BlobId("p123"),
                timestampSeconds = baseTimestamp + 3600, // 1 hour later
                originalLength = 200u,
                packedLength = 180u,
                packOffset = 90u
            )
        )

        val indexData = PackIndexV2.build(entries)
        val index = PackIndexV2.open(indexData)

        assertEquals(baseTimestamp, index.getInfo(ContentId.parse("1111"))!!.timestampSeconds)
        assertEquals(baseTimestamp + 3600, index.getInfo(ContentId.parse("2222"))!!.timestampSeconds)
    }

    // ===== Iterate Tests =====

    @Test
    fun `iterate should return all entries in sorted order`() {
        val entries = listOf(
            createTestContentInfo("cccc", 3),
            createTestContentInfo("aaaa", 1),
            createTestContentInfo("bbbb", 2)
        )

        val indexData = PackIndexV2.build(entries)
        val index = PackIndexV2.open(indexData)

        val iterated = index.iterate().toList()
        assertEquals(3, iterated.size)
        assertEquals("aaaa", iterated[0].contentId.toString())
        assertEquals("bbbb", iterated[1].contentId.toString())
        assertEquals("cccc", iterated[2].contentId.toString())
    }

    @Test
    fun `iterate with range should filter entries`() {
        val entries = listOf(
            createTestContentInfo("1111", 1),
            createTestContentInfo("2222", 2),
            createTestContentInfo("3333", 3),
            createTestContentInfo("4444", 4),
            createTestContentInfo("5555", 5)
        )

        val indexData = PackIndexV2.build(entries)
        val index = PackIndexV2.open(indexData)

        val iterated = index.iterate(
            startId = ContentId.parse("2222"),
            endId = ContentId.parse("4444")
        ).toList()

        assertEquals(2, iterated.size)
        assertEquals("2222", iterated[0].contentId.toString())
        assertEquals("3333", iterated[1].contentId.toString())
    }

    // ===== Format Info Tests =====

    @Test
    fun `V2 index should correctly store format version`() {
        val info = ContentInfo(
            contentId = ContentId.parse("0123456789abcdef"),
            packBlobId = BlobId("p1234"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 800u,
            packOffset = 0u,
            compressionHeaderId = 0x1100,
            deleted = false,
            formatVersion = 3,
            encryptionKeyId = 2
        )

        val indexData = PackIndexV2.build(listOf(info))
        val index = PackIndexV2.open(indexData)

        val retrieved = index.getInfo(ContentId.parse("0123456789abcdef"))
        assertNotNull(retrieved)
        assertEquals(3.toByte(), retrieved!!.formatVersion)
        assertEquals(2.toByte(), retrieved.encryptionKeyId)
        assertEquals(0x1100, retrieved.compressionHeaderId)
    }

    // ===== Helper Methods =====

    private fun createTestContentInfo(contentIdHex: String, packOffset: Int): ContentInfo {
        return ContentInfo(
            contentId = ContentId.parse(contentIdHex),
            packBlobId = BlobId("p1234567890"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 800u,
            packOffset = packOffset.toUInt(),
            compressionHeaderId = 0x1100 // ZSTD
        )
    }
}
