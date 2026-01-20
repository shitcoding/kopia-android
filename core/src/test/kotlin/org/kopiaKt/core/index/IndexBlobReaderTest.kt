package org.kopiaKt.core.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo

/**
 * Tests for IndexBlobReader.
 */
class IndexBlobReaderTest {

    // ===== Basic Reading Tests =====

    @Test
    fun `openUnencrypted should read V1 index blob`() {
        val builder = IndexBlobBuilder(version = IndexVersion.V1)
        builder.add(createTestContentInfo("1234567890abcdef", 0))

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        assertEquals(1, reader.approximateCount())
        reader.close()
    }

    @Test
    fun `openUnencrypted should read V2 index blob`() {
        val builder = IndexBlobBuilder(version = IndexVersion.V2)
        builder.add(createTestContentInfo("1234567890abcdef", 0))

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        assertEquals(1, reader.approximateCount())
        reader.close()
    }

    @Test
    fun `openUnencrypted should reject empty data`() {
        assertThrows<IllegalArgumentException> {
            IndexBlobReader.openUnencrypted(ByteArray(0), BlobId("ntest"))
        }
    }

    @Test
    fun `openUnencrypted should reject data with only suffix`() {
        // Only 32 bytes (just suffix, no actual index data)
        val data = ByteArray(IndexBlobConstants.RANDOM_SUFFIX_SIZE)

        assertThrows<IllegalArgumentException> {
            IndexBlobReader.openUnencrypted(data, BlobId("ntest"))
        }
    }

    @Test
    fun `openUnencrypted should reject unsupported version`() {
        // Create data with invalid version byte (0xFF)
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("1234", 0))
        val validData = builder.buildUnencrypted()

        // Corrupt the version byte
        validData[0] = 0xFF.toByte()

        assertThrows<IllegalArgumentException> {
            IndexBlobReader.openUnencrypted(validData, BlobId("ntest"))
        }
    }

    // ===== Content Lookup Tests =====

    @Test
    fun `getInfo should find existing content`() {
        val contentId = ContentId.parse("1234567890abcdef")
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("1234567890abcdef", 0))

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val info = reader.getInfo(contentId)
        assertNotNull(info)
        assertEquals(contentId, info!!.contentId)

        reader.close()
    }

    @Test
    fun `getInfo should return null for missing content`() {
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("1234567890abcdef", 0))

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val info = reader.getInfo(ContentId.parse("ffffffffffffffff"))
        assertNull(info)

        reader.close()
    }

    // ===== Iteration Tests =====

    @Test
    fun `iterate should return all entries in sorted order`() {
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("cccc", 200))
        builder.add(createTestContentInfo("aaaa", 0))
        builder.add(createTestContentInfo("bbbb", 100))

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val all = reader.iterate().toList()
        assertEquals(3, all.size)
        assertEquals("aaaa", all[0].contentId.toString())
        assertEquals("bbbb", all[1].contentId.toString())
        assertEquals("cccc", all[2].contentId.toString())

        reader.close()
    }

    @Test
    fun `iterate should support range filtering`() {
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("1111", 0))
        builder.add(createTestContentInfo("2222", 100))
        builder.add(createTestContentInfo("3333", 200))
        builder.add(createTestContentInfo("4444", 300))
        builder.add(createTestContentInfo("5555", 400))

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val range = reader.iterate(
            startId = ContentId.parse("2222"),
            endId = ContentId.parse("4444")
        ).toList()

        assertEquals(2, range.size)
        assertEquals("2222", range[0].contentId.toString())
        assertEquals("3333", range[1].contentId.toString())

        reader.close()
    }

    // ===== Extension Function Tests =====

    @Test
    fun `toList extension should return all entries`() {
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("aaaa", 0))
        builder.add(createTestContentInfo("bbbb", 100))

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val list = reader.toList()
        assertEquals(2, list.size)

        reader.close()
    }

    @Test
    fun `filterByPackBlobId extension should filter entries`() {
        val packBlobId1 = BlobId("p1111")
        val packBlobId2 = BlobId("p2222")

        val builder = IndexBlobBuilder()
        builder.add(
            ContentInfo(
                contentId = ContentId.parse("aaaa"),
                packBlobId = packBlobId1,
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 100u,
                packOffset = 0u
            )
        )
        builder.add(
            ContentInfo(
                contentId = ContentId.parse("bbbb"),
                packBlobId = packBlobId2,
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 100u,
                packOffset = 0u
            )
        )
        builder.add(
            ContentInfo(
                contentId = ContentId.parse("cccc"),
                packBlobId = packBlobId1,
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 100u,
                packOffset = 100u
            )
        )

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val filtered = reader.filterByPackBlobId(packBlobId1).toList()
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.packBlobId == packBlobId1 })

        reader.close()
    }

    @Test
    fun `findDeleted extension should find deleted entries`() {
        val builder = IndexBlobBuilder()
        builder.add(
            ContentInfo(
                contentId = ContentId.parse("aaaa"),
                packBlobId = BlobId("p1234"),
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 100u,
                packOffset = 0u,
                deleted = false
            )
        )
        builder.add(
            ContentInfo(
                contentId = ContentId.parse("bbbb"),
                packBlobId = BlobId("p1234"),
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 100u,
                packOffset = 100u,
                deleted = true
            )
        )

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val deleted = reader.findDeleted().toList()
        assertEquals(1, deleted.size)
        assertEquals("bbbb", deleted[0].contentId.toString())

        reader.close()
    }

    @Test
    fun `findActive extension should find non-deleted entries`() {
        val builder = IndexBlobBuilder()
        builder.add(
            ContentInfo(
                contentId = ContentId.parse("aaaa"),
                packBlobId = BlobId("p1234"),
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 100u,
                packOffset = 0u,
                deleted = false
            )
        )
        builder.add(
            ContentInfo(
                contentId = ContentId.parse("bbbb"),
                packBlobId = BlobId("p1234"),
                timestampSeconds = 1700000000L,
                originalLength = 100u,
                packedLength = 100u,
                packOffset = 100u,
                deleted = true
            )
        )

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val active = reader.findActive().toList()
        assertEquals(1, active.size)
        assertEquals("aaaa", active[0].contentId.toString())

        reader.close()
    }

    // ===== Blob ID Tests =====

    @Test
    fun `getBlobId should return the blob ID`() {
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("1234", 0))

        val data = builder.buildUnencrypted()
        val blobId = BlobId("ntest1234")
        val reader = IndexBlobReader.openUnencrypted(data, blobId)

        assertEquals(blobId, reader.getBlobId())

        reader.close()
    }

    // ===== Raw Index Reading Tests =====

    @Test
    fun `openRaw should read pack index without suffix`() {
        // Build index data without the suffix
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("1234", 0))

        // Get unencrypted data and remove the suffix manually
        val fullData = builder.buildUnencrypted()
        val indexData = fullData.copyOfRange(0, fullData.size - IndexBlobConstants.RANDOM_SUFFIX_SIZE)

        val reader = IndexBlobReader.openRaw(indexData, BlobId("ntest"))

        assertEquals(1, reader.approximateCount())
        reader.close()
    }

    // ===== V1 Per-Content Overhead Tests =====

    @Test
    fun `V1 index should compute originalLength with overhead`() {
        val builder = IndexBlobBuilder(version = IndexVersion.V1)
        builder.add(
            ContentInfo(
                contentId = ContentId.parse("1234567890abcdef"),
                packBlobId = BlobId("p1234"),
                timestampSeconds = 1700000000L,
                originalLength = 973u, // This is the expected computed value
                packedLength = 1000u,
                packOffset = 0u
            )
        )

        val data = builder.buildUnencrypted()
        val overhead = 27u // AES-256-GCM overhead

        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"), v1PerContentOverhead = overhead)

        val info = reader.getInfo(ContentId.parse("1234567890abcdef"))
        assertNotNull(info)
        // V1 computes originalLength = packedLength - overhead
        assertEquals(1000u - overhead, info!!.originalLength)

        reader.close()
    }

    // ===== Helper Methods =====

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
}
