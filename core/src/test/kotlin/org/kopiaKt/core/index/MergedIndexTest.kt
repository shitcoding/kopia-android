package org.kopiaKt.core.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.pack.PackIndex

/**
 * Tests for MergedIndex.
 */
class MergedIndexTest {

    // ===== Basic Tests =====

    @Test
    fun `empty MergedIndex should have zero count`() {
        val merged = MergedIndex.empty()

        assertEquals(0, merged.approximateCount())
        assertEquals(0, merged.indexCount())
    }

    @Test
    fun `MergedIndex of single index should delegate`() {
        val index = createTestIndex(listOf("aaaa", "bbbb", "cccc"))
        val merged = MergedIndex.of(index)

        assertEquals(3, merged.approximateCount())
        assertEquals(1, merged.indexCount())
    }

    @Test
    fun `MergedIndex should combine multiple indexes`() {
        val index1 = createTestIndex(listOf("aaaa", "bbbb"))
        val index2 = createTestIndex(listOf("cccc", "dddd"))
        val merged = MergedIndex(listOf(index1, index2))

        // Total approximate count (may include duplicates)
        assertEquals(4, merged.approximateCount())
        assertEquals(2, merged.indexCount())
    }

    // ===== Content Lookup Tests =====

    @Test
    fun `getInfo should find content in first index`() {
        val index1 = createTestIndex(listOf("aaaa", "bbbb"))
        val index2 = createTestIndex(listOf("cccc", "dddd"))
        val merged = MergedIndex(listOf(index1, index2))

        val info = merged.getInfo(ContentId.parse("aaaa"))
        assertNotNull(info)
        assertEquals("aaaa", info!!.contentId.toString())
    }

    @Test
    fun `getInfo should find content in second index`() {
        val index1 = createTestIndex(listOf("aaaa", "bbbb"))
        val index2 = createTestIndex(listOf("cccc", "dddd"))
        val merged = MergedIndex(listOf(index1, index2))

        val info = merged.getInfo(ContentId.parse("cccc"))
        assertNotNull(info)
        assertEquals("cccc", info!!.contentId.toString())
    }

    @Test
    fun `getInfo should return null for missing content`() {
        val index1 = createTestIndex(listOf("aaaa", "bbbb"))
        val index2 = createTestIndex(listOf("cccc", "dddd"))
        val merged = MergedIndex(listOf(index1, index2))

        val info = merged.getInfo(ContentId.parse("ffff"))
        assertNull(info)
    }

    @Test
    fun `getInfo should return most recent entry for duplicates`() {
        // Same content ID in both indexes with different timestamps
        val entry1 = createTestContentInfoWithTimestamp("aaaa", 0, 1000L)
        val entry2 = createTestContentInfoWithTimestamp("aaaa", 100, 2000L) // More recent

        val index1 = createTestIndexFromEntries(listOf(entry1))
        val index2 = createTestIndexFromEntries(listOf(entry2))
        val merged = MergedIndex(listOf(index1, index2))

        val info = merged.getInfo(ContentId.parse("aaaa"))
        assertNotNull(info)
        assertEquals(2000L, info!!.timestampSeconds)
        assertEquals(100u, info.packOffset)
    }

    @Test
    fun `getInfo should prefer later index when timestamps equal`() {
        // Same timestamp, different offsets - tests tie-breaking
        val entry1 = createTestContentInfoWithTimestamp("aaaa", 0, 1000L)
        val entry2 = createTestContentInfoWithTimestamp("aaaa", 100, 1000L) // Same timestamp

        val index1 = createTestIndexFromEntries(listOf(entry1))
        val index2 = createTestIndexFromEntries(listOf(entry2))
        val merged = MergedIndex(listOf(index1, index2))

        val info = merged.getInfo(ContentId.parse("aaaa"))
        assertNotNull(info)
        // Both have same timestamp, first one encountered should be kept
        assertEquals(0u, info!!.packOffset)
    }

    // ===== Batch Lookup Tests =====

    @Test
    fun `getInfoBatch should find multiple contents`() {
        val index = createTestIndex(listOf("aaaa", "bbbb", "cccc", "dddd"))
        val merged = MergedIndex.of(index)

        val results = merged.getInfoBatch(listOf(
            ContentId.parse("aaaa"),
            ContentId.parse("cccc"),
            ContentId.parse("ffff") // Missing
        ))

        assertEquals(2, results.size)
        assertNotNull(results[ContentId.parse("aaaa")])
        assertNotNull(results[ContentId.parse("cccc")])
        assertNull(results[ContentId.parse("ffff")])
    }

    // ===== Iteration Tests =====

    @Test
    fun `iterate should return deduplicated entries in sorted order`() {
        // Two indexes with overlapping content IDs
        val index1 = createTestIndex(listOf("aaaa", "cccc", "eeee"))
        val index2 = createTestIndex(listOf("bbbb", "cccc", "dddd")) // "cccc" duplicated
        val merged = MergedIndex(listOf(index1, index2))

        val all = merged.iterate().toList()

        // Should be deduplicated
        assertEquals(5, all.size)
        assertEquals(listOf("aaaa", "bbbb", "cccc", "dddd", "eeee"),
            all.map { it.contentId.toString() })
    }

    @Test
    fun `iterate should prefer most recent timestamp for duplicates`() {
        val entry1Old = createTestContentInfoWithTimestamp("aaaa", 0, 1000L)
        val entry1New = createTestContentInfoWithTimestamp("aaaa", 100, 2000L)

        val index1 = createTestIndexFromEntries(listOf(entry1Old))
        val index2 = createTestIndexFromEntries(listOf(entry1New))
        val merged = MergedIndex(listOf(index1, index2))

        val all = merged.iterate().toList()

        assertEquals(1, all.size)
        assertEquals(2000L, all[0].timestampSeconds)
    }

    @Test
    fun `iterate should support range filtering`() {
        val index1 = createTestIndex(listOf("1111", "3333", "5555"))
        val index2 = createTestIndex(listOf("2222", "4444", "6666"))
        val merged = MergedIndex(listOf(index1, index2))

        val range = merged.iterate(
            startId = ContentId.parse("2222"),
            endId = ContentId.parse("5555")
        ).toList()

        assertEquals(3, range.size)
        assertEquals(listOf("2222", "3333", "4444"), range.map { it.contentId.toString() })
    }

    @Test
    fun `iterateAll should return all entries including duplicates`() {
        val index1 = createTestIndex(listOf("aaaa", "bbbb"))
        val index2 = createTestIndex(listOf("aaaa", "cccc")) // "aaaa" duplicated
        val merged = MergedIndex(listOf(index1, index2))

        val all = merged.iterateAll().toList()

        // Should include duplicates
        assertEquals(4, all.size)
    }

    // ===== Close Tests =====

    @Test
    fun `close should close all underlying indexes`() {
        val index1 = CloseTrackingIndex()
        val index2 = CloseTrackingIndex()
        val merged = MergedIndex(listOf(index1, index2))

        merged.close()

        assertTrue(index1.closed)
        assertTrue(index2.closed)
    }

    @Test
    fun `close should handle errors gracefully`() {
        val index1 = CloseTrackingIndex(throwOnClose = true)
        val index2 = CloseTrackingIndex()
        val merged = MergedIndex(listOf(index1, index2))

        // Should not throw even if one index fails to close
        merged.close()

        assertTrue(index2.closed) // Second index should still be closed
    }

    // ===== Builder Tests =====

    @Test
    fun `MergedIndexBuilder should build empty index`() {
        val builder = MergedIndexBuilder()
        val merged = builder.build()

        assertEquals(0, merged.indexCount())
    }

    @Test
    fun `MergedIndexBuilder should accumulate indexes`() {
        val builder = MergedIndexBuilder()
            .add(createTestIndex(listOf("aaaa")))
            .add(createTestIndex(listOf("bbbb")))

        assertEquals(2, builder.size())

        val merged = builder.build()
        assertEquals(2, merged.indexCount())
    }

    @Test
    fun `MergedIndexBuilder addAll should add multiple indexes`() {
        val indexes = listOf(
            createTestIndex(listOf("aaaa")),
            createTestIndex(listOf("bbbb")),
            createTestIndex(listOf("cccc"))
        )

        val builder = MergedIndexBuilder().addAll(indexes)
        val merged = builder.build()

        assertEquals(3, merged.indexCount())
    }

    // ===== Helper Methods =====

    private fun createTestIndex(contentIds: List<String>): PackIndex {
        val builder = IndexBlobBuilder()
        contentIds.forEachIndexed { index, id ->
            builder.add(createTestContentInfo(id, index * 100))
        }
        val data = builder.buildUnencrypted()
        return IndexBlobReader.openUnencrypted(data, BlobId("ntest"))
    }

    private fun createTestIndexFromEntries(entries: List<ContentInfo>): PackIndex {
        val builder = IndexBlobBuilder()
        entries.forEach { builder.add(it) }
        val data = builder.buildUnencrypted()
        return IndexBlobReader.openUnencrypted(data, BlobId("ntest"))
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

    private fun createTestContentInfoWithTimestamp(
        contentIdHex: String,
        packOffset: Int,
        timestamp: Long
    ): ContentInfo {
        return ContentInfo(
            contentId = ContentId.parse(contentIdHex),
            packBlobId = BlobId("p1234567890"),
            timestampSeconds = timestamp,
            originalLength = 1000u,
            packedLength = 1000u,
            packOffset = packOffset.toUInt()
        )
    }

    private class CloseTrackingIndex(
        private val throwOnClose: Boolean = false
    ) : PackIndex {
        var closed = false

        override fun approximateCount(): Int = 0
        override fun getInfo(contentId: ContentId): ContentInfo? = null
        override fun iterate(startId: ContentId?, endId: ContentId?): Sequence<ContentInfo> = emptySequence()

        override fun close() {
            closed = true
            if (throwOnClose) {
                throw RuntimeException("Close failed")
            }
        }
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.jupiter.api.Assertions.assertTrue(condition)
    }
}
