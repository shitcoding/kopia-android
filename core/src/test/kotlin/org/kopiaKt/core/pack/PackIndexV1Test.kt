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
 * Tests for Pack Index V1 format parsing and building.
 *
 * V1 index format:
 * - 8-byte header: [version=1][keySize][entrySize(2b)][entryCount(4b)]
 * - Sorted entries: [key(keySize)][entry(20b)]
 * - Extra data: pack blob IDs referenced by entries
 *
 * Entry format (20 bytes):
 * - Bytes 0-5: timestamp (48-bit big-endian, seconds)
 * - Byte 6: format version
 * - Byte 7: pack blob ID length
 * - Bytes 8-11: pack blob ID offset in extra data (big-endian)
 * - Bytes 12-15: deleted flag (MSB) + pack offset (31 bits, big-endian)
 * - Bytes 16-19: packed length (big-endian)
 */
class PackIndexV1Test {

    // ===== Header Parsing Tests =====

    @Test
    fun `parseHeader should read valid V1 header`() {
        // Header: version=1, keySize=17, entrySize=20, entryCount=5
        val header = byteArrayOf(
            0x01,                   // version
            0x11,                   // keySize = 17
            0x00, 0x14,             // entrySize = 20 (big-endian)
            0x00, 0x00, 0x00, 0x05  // entryCount = 5 (big-endian)
        )

        val info = PackIndexV1.parseHeader(header)

        assertEquals(1, info.version)
        assertEquals(17, info.keySize)
        assertEquals(20, info.entrySize)
        assertEquals(5, info.entryCount)
    }

    @Test
    fun `parseHeader should reject wrong version`() {
        val header = byteArrayOf(
            0x02, // wrong version
            0x11, 0x00, 0x14, 0x00, 0x00, 0x00, 0x05
        )

        assertThrows<IllegalArgumentException> {
            PackIndexV1.parseHeader(header)
        }
    }

    @Test
    fun `parseHeader should reject invalid keySize`() {
        // keySize <= 1 is invalid
        val header = byteArrayOf(
            0x01, 0x01, 0x00, 0x14, 0x00, 0x00, 0x00, 0x05
        )

        assertThrows<IllegalArgumentException> {
            PackIndexV1.parseHeader(header)
        }
    }

    @Test
    fun `parseHeader should reject too short header`() {
        val header = byteArrayOf(0x01, 0x11, 0x00, 0x14)

        assertThrows<IllegalArgumentException> {
            PackIndexV1.parseHeader(header)
        }
    }

    // ===== Entry Parsing Tests =====

    @Test
    fun `parseEntry should decode entry correctly`() {
        // Create a test entry with known values
        val timestamp = 1700000000L // Unix timestamp
        val formatVersion: Byte = 1
        val packBlobIdLength: Byte = 10
        val packBlobIdOffset = 100u
        val packOffset = 1024u
        val packedLength = 4096u
        val deleted = false

        val entry = buildTestEntry(
            timestamp = timestamp,
            formatVersion = formatVersion,
            packBlobIdLength = packBlobIdLength,
            packBlobIdOffset = packBlobIdOffset,
            packOffset = packOffset,
            packedLength = packedLength,
            deleted = deleted
        )

        val extraData = "p" + "1234567890".repeat(10) // Pack blob IDs in extra data
        val extraDataOffset = 200 // Where extra data starts in the full index

        val parsed = PackIndexV1.parseEntry(
            entry = entry,
            extraData = extraData.toByteArray(),
            extraDataOffset = extraDataOffset
        )

        assertEquals(timestamp, parsed.timestampSeconds)
        assertEquals(formatVersion, parsed.formatVersion)
        assertEquals(packOffset, parsed.packOffset)
        assertEquals(packedLength, parsed.packedLength)
        assertFalse(parsed.deleted)
    }

    @Test
    fun `parseEntry should handle deleted flag`() {
        val entry = buildTestEntry(
            timestamp = 1700000000L,
            formatVersion = 1,
            packBlobIdLength = 10,
            packBlobIdOffset = 0u,
            packOffset = 1024u,
            packedLength = 4096u,
            deleted = true
        )

        val extraData = "p123456789"
        val parsed = PackIndexV1.parseEntry(entry, extraData.toByteArray(), 0)

        assertTrue(parsed.deleted)
        assertEquals(1024u, parsed.packOffset) // Pack offset should be preserved
    }

    @Test
    fun `parseEntry should extract pack blob ID from extra data`() {
        val packBlobId = "p1234567890abcdef"
        val extraData = packBlobId.toByteArray()

        val entry = buildTestEntry(
            timestamp = 1700000000L,
            formatVersion = 1,
            packBlobIdLength = packBlobId.length.toByte(),
            packBlobIdOffset = 0u,
            packOffset = 0u,
            packedLength = 100u,
            deleted = false
        )

        val parsed = PackIndexV1.parseEntry(entry, extraData, 0)

        assertEquals(packBlobId, parsed.packBlobId)
    }

    // ===== Index Building Tests =====

    @Test
    fun `build should create valid V1 index with single entry`() {
        val contentId = ContentId.parse("0123456789abcdef0123456789abcdef")
        val info = ContentInfo(
            contentId = contentId,
            packBlobId = BlobId("p1234567890abcdef"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 800u,
            packOffset = 0u,
            compressionHeaderId = 0,
            deleted = false,
            formatVersion = 1,
            encryptionKeyId = 0
        )

        val indexData = PackIndexV1.build(listOf(info))

        // Parse back and verify
        val index = PackIndexV1.open(indexData)
        assertNotNull(index)

        val retrieved = index.getInfo(contentId)
        assertNotNull(retrieved)
        assertEquals(info.packBlobId.value, retrieved!!.packBlobId.value)
        assertEquals(info.timestampSeconds, retrieved.timestampSeconds)
        assertEquals(info.packedLength, retrieved.packedLength)
        assertEquals(info.packOffset, retrieved.packOffset)
        assertEquals(info.deleted, retrieved.deleted)
    }

    @Test
    fun `build should create valid V1 index with multiple entries sorted`() {
        val entries = listOf(
            createTestContentInfo("3333333333333333", 3),
            createTestContentInfo("1111111111111111", 1),
            createTestContentInfo("2222222222222222", 2)
        )

        val indexData = PackIndexV1.build(entries)
        val index = PackIndexV1.open(indexData)

        // Verify entries are sorted lexicographically
        val allEntries = index.iterate().toList()
        assertEquals(3, allEntries.size)
        assertEquals("1111111111111111", allEntries[0].contentId.toString())
        assertEquals("2222222222222222", allEntries[1].contentId.toString())
        assertEquals("3333333333333333", allEntries[2].contentId.toString())
    }

    @Test
    fun `build should reject entries with compression`() {
        val info = ContentInfo(
            contentId = ContentId.parse("0123456789abcdef"),
            packBlobId = BlobId("p1234567890"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 800u,
            packOffset = 0u,
            compressionHeaderId = 0x1000, // GZIP compression - not supported in V1
            deleted = false,
            formatVersion = 1,
            encryptionKeyId = 0
        )

        assertThrows<IllegalArgumentException> {
            PackIndexV1.build(listOf(info))
        }
    }

    @Test
    fun `build should reject entries with non-zero encryption key ID`() {
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
            encryptionKeyId = 1 // Not supported in V1
        )

        assertThrows<IllegalArgumentException> {
            PackIndexV1.build(listOf(info))
        }
    }

    // ===== Index Reading Tests =====

    @Test
    fun `open should parse valid V1 index`() {
        val entries = listOf(
            createTestContentInfo("aaaa111122223333", 1),
            createTestContentInfo("bbbb444455556666", 2)
        )

        val indexData = PackIndexV1.build(entries)
        val index = PackIndexV1.open(indexData)

        assertEquals(2, index.approximateCount())
    }

    @Test
    fun `getInfo should find existing content`() {
        val contentId = ContentId.parse("0123456789abcdef")
        val info = createTestContentInfo("0123456789abcdef", 0)

        val indexData = PackIndexV1.build(listOf(info))
        val index = PackIndexV1.open(indexData)

        val found = index.getInfo(contentId)
        assertNotNull(found)
        assertEquals(contentId, found!!.contentId)
    }

    @Test
    fun `getInfo should return null for non-existent content`() {
        val info = createTestContentInfo("0123456789abcdef", 0)
        val indexData = PackIndexV1.build(listOf(info))
        val index = PackIndexV1.open(indexData)

        val notFound = index.getInfo(ContentId.parse("ffffffffffffffff"))
        assertNull(notFound)
    }

    @Test
    fun `iterate should return all entries in sorted order`() {
        val entries = listOf(
            createTestContentInfo("cccc", 3),
            createTestContentInfo("aaaa", 1),
            createTestContentInfo("bbbb", 2)
        )

        val indexData = PackIndexV1.build(entries)
        val index = PackIndexV1.open(indexData)

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

        val indexData = PackIndexV1.build(entries)
        val index = PackIndexV1.open(indexData)

        // Iterate from "2222" (inclusive) to "4444" (exclusive)
        val iterated = index.iterate(
            startId = ContentId.parse("2222"),
            endId = ContentId.parse("4444")
        ).toList()

        assertEquals(2, iterated.size)
        assertEquals("2222", iterated[0].contentId.toString())
        assertEquals("3333", iterated[1].contentId.toString())
    }

    // ===== Extra Data Tests =====

    @Test
    fun `build should deduplicate pack blob IDs in extra data`() {
        val packBlobId = BlobId("p1234567890")
        val entries = listOf(
            ContentInfo(
                contentId = ContentId.parse("aaaa"),
                packBlobId = packBlobId,
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 90u,
                packOffset = 0u
            ),
            ContentInfo(
                contentId = ContentId.parse("bbbb"),
                packBlobId = packBlobId, // Same pack blob ID
                timestampSeconds = 1700000000L,
                originalLength = 200u,
                packedLength = 180u,
                packOffset = 90u
            )
        )

        val indexData = PackIndexV1.build(entries)

        // The pack blob ID should only appear once in extra data
        val packBlobIdBytes = packBlobId.value.toByteArray()
        var count = 0
        var searchStart = 0
        while (true) {
            val found = indexData.toList().windowed(packBlobIdBytes.size)
                .drop(searchStart)
                .indexOfFirst { it.toByteArray().contentEquals(packBlobIdBytes) }
            if (found < 0) break
            count++
            searchStart = searchStart + found + 1
        }
        assertEquals(1, count, "Pack blob ID should appear exactly once in index")
    }

    // ===== Prefixed Content ID Tests =====

    @Test
    fun `build should handle prefixed content IDs`() {
        // Content IDs with 'm' prefix (manifest content)
        val entries = listOf(
            createPrefixedTestContentInfo("m", "1111111122223333", 1),
            createPrefixedTestContentInfo("m", "4444555566667777", 2)
        )

        val indexData = PackIndexV1.build(entries)
        val index = PackIndexV1.open(indexData)

        val found = index.getInfo(ContentId.parse("m1111111122223333"))
        assertNotNull(found)
        assertEquals("m1111111122223333", found!!.contentId.toString())
    }

    // ===== Edge Cases =====

    @Test
    fun `build should handle empty entry list`() {
        val indexData = PackIndexV1.build(emptyList())
        val index = PackIndexV1.open(indexData)

        assertEquals(0, index.approximateCount())
        assertNull(index.getInfo(ContentId.parse("0000")))
    }

    @Test
    fun `build should handle maximum pack offset`() {
        val maxOffset = (1u shl 31) - 1u // 31-bit max (MSB reserved for deleted flag)
        val info = ContentInfo(
            contentId = ContentId.parse("0123456789abcdef"),
            packBlobId = BlobId("p1234"),
            timestampSeconds = 1700000000L,
            originalLength = 100u,
            packedLength = 100u,
            packOffset = maxOffset
        )

        val indexData = PackIndexV1.build(listOf(info))
        val index = PackIndexV1.open(indexData)

        val retrieved = index.getInfo(ContentId.parse("0123456789abcdef"))
        assertNotNull(retrieved)
        assertEquals(maxOffset, retrieved!!.packOffset)
    }

    // ===== Original Length Calculation Tests =====

    @Test
    fun `V1 index should compute originalLength from packedLength minus overhead`() {
        val info = ContentInfo(
            contentId = ContentId.parse("0123456789abcdef"),
            packBlobId = BlobId("p1234"),
            timestampSeconds = 1700000000L,
            originalLength = 973u, // Should be computed: packedLength - overhead
            packedLength = 1000u,
            packOffset = 0u
        )

        val encryptionOverhead = 27u // AES-256-GCM overhead
        val indexData = PackIndexV1.build(listOf(info))
        val index = PackIndexV1.open(indexData, encryptionOverhead)

        val retrieved = index.getInfo(ContentId.parse("0123456789abcdef"))
        assertNotNull(retrieved)
        // V1 computes originalLength as packedLength - overhead
        assertEquals(1000u - encryptionOverhead, retrieved!!.originalLength)
    }

    // ===== Helper Methods =====

    private fun buildTestEntry(
        timestamp: Long,
        formatVersion: Byte,
        packBlobIdLength: Byte,
        packBlobIdOffset: UInt,
        packOffset: UInt,
        packedLength: UInt,
        deleted: Boolean
    ): ByteArray {
        val entry = ByteArray(20)

        // Bytes 0-5: timestamp (48-bit big-endian)
        val timestampShifted = timestamp shl 16
        entry[0] = ((timestampShifted shr 56) and 0xFF).toByte()
        entry[1] = ((timestampShifted shr 48) and 0xFF).toByte()
        entry[2] = ((timestampShifted shr 40) and 0xFF).toByte()
        entry[3] = ((timestampShifted shr 32) and 0xFF).toByte()
        entry[4] = ((timestampShifted shr 24) and 0xFF).toByte()
        entry[5] = ((timestampShifted shr 16) and 0xFF).toByte()

        // Byte 6: format version
        entry[6] = formatVersion

        // Byte 7: pack blob ID length
        entry[7] = packBlobIdLength

        // Bytes 8-11: pack blob ID offset (big-endian)
        entry[8] = ((packBlobIdOffset.toInt() shr 24) and 0xFF).toByte()
        entry[9] = ((packBlobIdOffset.toInt() shr 16) and 0xFF).toByte()
        entry[10] = ((packBlobIdOffset.toInt() shr 8) and 0xFF).toByte()
        entry[11] = (packBlobIdOffset.toInt() and 0xFF).toByte()

        // Bytes 12-15: deleted flag (MSB) + pack offset (big-endian)
        var offsetWithFlag = packOffset.toInt()
        if (deleted) {
            offsetWithFlag = offsetWithFlag or 0x80000000.toInt()
        }
        entry[12] = ((offsetWithFlag shr 24) and 0xFF).toByte()
        entry[13] = ((offsetWithFlag shr 16) and 0xFF).toByte()
        entry[14] = ((offsetWithFlag shr 8) and 0xFF).toByte()
        entry[15] = (offsetWithFlag and 0xFF).toByte()

        // Bytes 16-19: packed length (big-endian)
        entry[16] = ((packedLength.toInt() shr 24) and 0xFF).toByte()
        entry[17] = ((packedLength.toInt() shr 16) and 0xFF).toByte()
        entry[18] = ((packedLength.toInt() shr 8) and 0xFF).toByte()
        entry[19] = (packedLength.toInt() and 0xFF).toByte()

        return entry
    }

    private fun createTestContentInfo(contentIdHex: String, packOffset: Int): ContentInfo {
        return ContentInfo(
            contentId = ContentId.parse(contentIdHex),
            packBlobId = BlobId("p1234567890"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 1000u,
            packOffset = packOffset.toUInt()
        )
    }

    private fun createPrefixedTestContentInfo(prefix: String, hashHex: String, packOffset: Int): ContentInfo {
        return ContentInfo(
            contentId = ContentId.parse(prefix + hashHex),
            packBlobId = BlobId("q1234567890"), // q prefix for special content
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 1000u,
            packOffset = packOffset.toUInt()
        )
    }
}
