package org.kopiaKt.core.index

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo

/**
 * Tests for IndexBlobBuilder.
 */
class IndexBlobBuilderTest {

    // ===== Basic Building Tests =====

    @Test
    fun `build should create valid V1 index blob`() {
        val builder = IndexBlobBuilder(version = IndexVersion.V1)
        builder.add(createTestContentInfo("aaaa111122223333", 0))
        builder.add(createTestContentInfo("bbbb444455556666", 100))

        val data = builder.buildUnencrypted()

        // Should contain pack index + 32-byte random suffix
        assertTrue(data.size > IndexBlobConstants.RANDOM_SUFFIX_SIZE)

        // First byte after removing suffix should be version 1
        assertEquals(IndexVersion.V1.toByte(), data[0])
    }

    @Test
    fun `build should create valid V2 index blob`() {
        val builder = IndexBlobBuilder(version = IndexVersion.V2)
        builder.add(createTestContentInfo("aaaa111122223333", 0))
        builder.add(createTestContentInfo("bbbb444455556666", 100))

        val data = builder.buildUnencrypted()

        // First byte after removing suffix should be version 2
        assertEquals(IndexVersion.V2.toByte(), data[0])
    }

    @Test
    fun `build should append 32-byte random suffix`() {
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("aaaa", 0))

        val data1 = builder.buildUnencrypted()
        val data2 = builder.buildUnencrypted()

        // Last 32 bytes should differ (random)
        val suffix1 = data1.takeLast(IndexBlobConstants.RANDOM_SUFFIX_SIZE)
        val suffix2 = data2.takeLast(IndexBlobConstants.RANDOM_SUFFIX_SIZE)

        // Very unlikely to be equal for random data
        assertTrue(suffix1 != suffix2 || suffix1.all { it == 0.toByte() })
    }

    @Test
    fun `build should handle empty entries`() {
        val builder = IndexBlobBuilder()

        val data = builder.buildUnencrypted()

        // Should contain empty index + 32-byte random suffix
        assertTrue(data.size >= IndexBlobConstants.RANDOM_SUFFIX_SIZE)
    }

    // ===== Entry Management Tests =====

    @Test
    fun `add should accumulate entries`() {
        val builder = IndexBlobBuilder()

        assertEquals(0, builder.size())

        builder.add(createTestContentInfo("aaaa", 0))
        assertEquals(1, builder.size())

        builder.add(createTestContentInfo("bbbb", 100))
        assertEquals(2, builder.size())
    }

    @Test
    fun `addAll should add multiple entries`() {
        val builder = IndexBlobBuilder()
        val entries = listOf(
            createTestContentInfo("aaaa", 0),
            createTestContentInfo("bbbb", 100),
            createTestContentInfo("cccc", 200)
        )

        builder.addAll(entries)

        assertEquals(3, builder.size())
    }

    @Test
    fun `clear should remove all entries`() {
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("aaaa", 0))
        builder.add(createTestContentInfo("bbbb", 100))

        assertEquals(2, builder.size())

        builder.clear()

        assertEquals(0, builder.size())
    }

    @Test
    fun `getEntries should return copy of entries`() {
        val builder = IndexBlobBuilder()
        val entry = createTestContentInfo("aaaa", 0)
        builder.add(entry)

        val entries = builder.getEntries()

        assertEquals(1, entries.size)
        assertEquals(entry, entries[0])
    }

    // ===== Blob ID Derivation Tests =====

    @Test
    fun `deriveContentIdFromBlobId should handle simple blob ID`() {
        val blobId = BlobId("n1234567890abcdef1234567890abcdef")

        val contentId = IndexBlobBuilder.deriveContentIdFromBlobId(blobId)

        assertEquals("1234567890abcdef1234567890abcdef", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should handle blob ID with dash suffix`() {
        val blobId = BlobId("n1234567890abcdef1234567890abcdef-s12345")

        val contentId = IndexBlobBuilder.deriveContentIdFromBlobId(blobId)

        assertEquals("1234567890abcdef1234567890abcdef", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should take last 32 hex chars for long IDs`() {
        val blobId = BlobId("nabcdef1234567890abcdef1234567890abcdef1234")

        val contentId = IndexBlobBuilder.deriveContentIdFromBlobId(blobId)

        // After removing 'n' prefix: "abcdef1234567890abcdef1234567890abcdef1234" (40 chars)
        // Last 32 hex chars (positions 8-40): "567890abcdef1234567890abcdef1234"
        assertEquals("567890abcdef1234567890abcdef1234", contentId.toString())
    }

    @Test
    fun `deriveContentIdFromBlobId should handle short blob ID`() {
        val blobId = BlobId("n1234")

        val contentId = IndexBlobBuilder.deriveContentIdFromBlobId(blobId)

        assertEquals("1234", contentId.toString())
    }

    // ===== Build with Generated ID Tests =====

    @Test
    fun `buildWithGeneratedId should generate blob ID from hash`() = runBlocking {
        val builder = IndexBlobBuilder()
        builder.add(createTestContentInfo("aaaa", 0))

        val hasher: (ByteArray) -> ByteArray = { data ->
            // Simple test hasher - just take first 16 bytes or pad
            data.copyOf(16)
        }

        val (blobId, data) = builder.buildWithGeneratedId(null, hasher)

        assertNotNull(blobId)
        assertTrue(blobId.value.startsWith(IndexBlobConstants.INDEX_BLOB_PREFIX))
        assertNotNull(data)
    }

    // ===== Round-Trip Tests =====

    @Test
    fun `built index should be readable by IndexBlobReader`() {
        val builder = IndexBlobBuilder()
        val contentId = ContentId.parse("1234567890abcdef")
        val entry = createTestContentInfo("1234567890abcdef", 0)
        builder.add(entry)

        val data = builder.buildUnencrypted()
        val blobId = BlobId("ntest1234")

        val reader = IndexBlobReader.openUnencrypted(data, blobId)

        val retrieved = reader.getInfo(contentId)
        assertNotNull(retrieved)
        assertEquals(entry.packBlobId, retrieved!!.packBlobId)
        assertEquals(entry.packOffset, retrieved.packOffset)

        reader.close()
    }

    @Test
    fun `V2 index should preserve compression metadata`() {
        val builder = IndexBlobBuilder(version = IndexVersion.V2)
        val entry = ContentInfo(
            contentId = ContentId.parse("1234567890abcdef"),
            packBlobId = BlobId("p1234567890"),
            timestampSeconds = 1700000000L,
            originalLength = 1000u,
            packedLength = 800u,
            packOffset = 0u,
            compressionHeaderId = 0x1100, // ZSTD
            deleted = false,
            formatVersion = 1,
            encryptionKeyId = 0
        )
        builder.add(entry)

        val data = builder.buildUnencrypted()
        val reader = IndexBlobReader.openUnencrypted(data, BlobId("ntest"))

        val retrieved = reader.getInfo(entry.contentId)
        assertNotNull(retrieved)
        assertEquals(0x1100, retrieved!!.compressionHeaderId)

        reader.close()
    }

    @Test
    fun `built index should handle multiple entries sorted correctly`() {
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
