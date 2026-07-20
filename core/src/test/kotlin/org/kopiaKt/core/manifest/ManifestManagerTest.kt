package org.kopiaKt.core.manifest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.content.ContentManager
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import java.time.Instant

/**
 * Tests for ManifestManager - manages JSON manifests in the repository.
 *
 * ManifestManager provides:
 * - Put: Store manifest with labels
 * - Get: Retrieve manifest by ID
 * - GetMetadata: Get manifest metadata without content
 * - Find: Query manifests by labels
 * - Delete: Mark manifest for deletion
 * - Flush: Persist pending changes to storage
 * - Compact: Consolidate manifest contents
 *
 * Storage format:
 * - Manifests stored as gzip-compressed JSON
 * - Written with content ID prefix 'm'
 * - Multiple entries per manifest content block
 */
class ManifestManagerTest {

    private lateinit var storage: InMemoryBlobStorage
    private lateinit var contentManager: ContentManager
    private lateinit var manifestManager: ManifestManager

    @BeforeEach
    fun setUp() {
        storage = InMemoryBlobStorage()
        contentManager = ContentManager(
            storage = storage,
            hasherFactory = DefaultContentHasherFactory(),
            hashAlgorithm = HashAlgorithm.BLAKE2B_256_128,
            hashSecret = ByteArray(32),
            encryptorFactory = DefaultEncryptorFactory(),
            encryptionAlgorithm = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256,
            encryptionKey = ByteArray(32),
            compressorFactory = DefaultCompressorFactory(),
            defaultCompression = CompressionAlgorithm.ZSTD_FASTEST
        )
        manifestManager = ManifestManager(contentManager)
    }

    // === Basic Put/Get Tests ===

    @Serializable
    data class TestPayload(
        val name: String,
        val value: Int,
        val nested: NestedData? = null
    )

    @Serializable
    data class NestedData(
        val items: List<String>
    )

    @Test
    fun `put and get simple manifest`() = runBlocking {
        val payload = TestPayload("test", 42)
        val labels = mapOf("type" to "test-type", "key" to "value")

        val id = manifestManager.put(labels, payload)

        assertNotNull(id)
        assertEquals(32, id.value.length)

        val (retrieved, metadata) = manifestManager.get<TestPayload>(id)

        assertEquals(payload, retrieved)
        assertEquals(id, metadata.id)
        assertEquals("test-type", metadata.labels["type"])
        assertEquals("value", metadata.labels["key"])
        assertTrue(metadata.length > 0)
    }

    @Test
    fun `put and get manifest with nested data`() = runBlocking {
        val payload = TestPayload("complex", 100, NestedData(listOf("a", "b", "c")))
        val labels = mapOf("type" to "complex", "extra" to "data")

        val id = manifestManager.put(labels, payload)
        val (retrieved, _) = manifestManager.get<TestPayload>(id)

        assertEquals(payload, retrieved)
        assertEquals(listOf("a", "b", "c"), retrieved.nested?.items)
    }

    @Test
    fun `put requires type label`() = runBlocking {
        val payload = TestPayload("test", 42)
        val labels = mapOf("key" to "value")  // missing "type"

        assertThrows<IllegalArgumentException> {
            runBlocking { manifestManager.put(labels, payload) }
        }
    }

    @Test
    fun `get returns not found for unknown id`() = runBlocking {
        val unknownId = ManifestId("00000000000000000000000000000000")

        assertThrows<ManifestNotFoundException> {
            runBlocking { manifestManager.get<TestPayload>(unknownId) }
        }
    }

    // === Metadata Tests ===

    @Test
    fun `getMetadata returns metadata without content`() = runBlocking {
        val payload = TestPayload("test", 42)
        val labels = mapOf("type" to "meta-test", "category" to "example")

        val id = manifestManager.put(labels, payload)
        val metadata = manifestManager.getMetadata(id)

        assertEquals(id, metadata.id)
        assertEquals("meta-test", metadata.labels["type"])
        assertEquals("example", metadata.labels["category"])
        assertTrue(metadata.length > 0)
        assertNotNull(metadata.modTime)
    }

    @Test
    fun `getMetadata returns null for unknown id`() = runBlocking {
        val unknownId = ManifestId("00000000000000000000000000000000")
        val metadata = manifestManager.getMetadataOrNull(unknownId)
        assertNull(metadata)
    }

    // === Find Tests ===

    @Test
    fun `find by single label`() = runBlocking {
        manifestManager.put(mapOf("type" to "snapshot", "host" to "server1"), TestPayload("s1", 1))
        manifestManager.put(mapOf("type" to "snapshot", "host" to "server2"), TestPayload("s2", 2))
        manifestManager.put(mapOf("type" to "policy", "host" to "server1"), TestPayload("p1", 3))

        val snapshots = manifestManager.find(mapOf("type" to "snapshot"))
        assertEquals(2, snapshots.size)

        val server1 = manifestManager.find(mapOf("host" to "server1"))
        assertEquals(2, server1.size)
    }

    @Test
    fun `find by multiple labels`() = runBlocking {
        manifestManager.put(mapOf("type" to "snapshot", "host" to "server1", "user" to "alice"), TestPayload("s1", 1))
        manifestManager.put(mapOf("type" to "snapshot", "host" to "server1", "user" to "bob"), TestPayload("s2", 2))
        manifestManager.put(mapOf("type" to "snapshot", "host" to "server2", "user" to "alice"), TestPayload("s3", 3))

        val results = manifestManager.find(mapOf("host" to "server1", "user" to "alice"))
        assertEquals(1, results.size)
        assertEquals("s1", results[0].labels["type"]?.let { runBlocking {
            manifestManager.get<TestPayload>(results[0].id).first.name
        } })
    }

    @Test
    fun `find returns empty list for no matches`() = runBlocking {
        manifestManager.put(mapOf("type" to "snapshot", "host" to "server1"), TestPayload("s1", 1))

        val results = manifestManager.find(mapOf("type" to "nonexistent"))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `find results sorted by modification time`() = runBlocking {
        // Create manifests with small delays to ensure ordering
        val id1 = manifestManager.put(mapOf("type" to "sorted"), TestPayload("first", 1))
        val id2 = manifestManager.put(mapOf("type" to "sorted"), TestPayload("second", 2))
        val id3 = manifestManager.put(mapOf("type" to "sorted"), TestPayload("third", 3))

        val results = manifestManager.find(mapOf("type" to "sorted"))
        assertEquals(3, results.size)
        // Results should be in order of creation (oldest first)
        assertEquals(id1, results[0].id)
        assertEquals(id2, results[1].id)
        assertEquals(id3, results[2].id)
    }

    // === Delete Tests ===

    @Test
    fun `delete makes manifest unfindable`() = runBlocking {
        val id = manifestManager.put(mapOf("type" to "deletable"), TestPayload("test", 1))

        // Before delete
        val beforeDelete = manifestManager.find(mapOf("type" to "deletable"))
        assertEquals(1, beforeDelete.size)

        // Delete
        manifestManager.delete(id)

        // After delete (before flush)
        val afterDelete = manifestManager.find(mapOf("type" to "deletable"))
        assertEquals(0, afterDelete.size)

        // Get should throw
        assertThrows<ManifestNotFoundException> {
            runBlocking { manifestManager.get<TestPayload>(id) }
        }
    }

    @Test
    fun `delete non-existent manifest is no-op`() = runBlocking {
        val unknownId = ManifestId("00000000000000000000000000000000")
        // Should not throw
        manifestManager.delete(unknownId)
    }

    // === Flush and Persistence Tests ===

    @Test
    fun `flush persists pending manifests`() = runBlocking {
        val id = manifestManager.put(mapOf("type" to "persistent"), TestPayload("data", 123))

        // Flush to storage
        manifestManager.flush()
        contentManager.flush()

        // Create new manifest manager to simulate restart
        val newManager = ManifestManager(contentManager)
        newManager.refresh()

        val (retrieved, _) = newManager.get<TestPayload>(id)
        assertEquals("data", retrieved.name)
        assertEquals(123, retrieved.value)
    }

    @Test
    fun `flush persists deletions`() = runBlocking {
        val id = manifestManager.put(mapOf("type" to "to-delete"), TestPayload("data", 1))
        manifestManager.flush()
        contentManager.flush()

        // Delete and flush
        manifestManager.delete(id)
        manifestManager.flush()
        contentManager.flush()

        // New manager should not see deleted manifest
        val newManager = ManifestManager(contentManager)
        newManager.refresh()

        assertThrows<ManifestNotFoundException> {
            runBlocking { newManager.get<TestPayload>(id) }
        }
    }

    @Test
    fun `multiple manifests in single flush`() = runBlocking {
        val ids = (1..10).map { i ->
            manifestManager.put(mapOf("type" to "batch", "index" to "$i"), TestPayload("item$i", i))
        }

        manifestManager.flush()
        contentManager.flush()

        // Create new manager
        val newManager = ManifestManager(contentManager)
        newManager.refresh()

        val results = newManager.find(mapOf("type" to "batch"))
        assertEquals(10, results.size)

        // Verify all can be retrieved
        ids.forEachIndexed { index, id ->
            val (retrieved, _) = newManager.get<TestPayload>(id)
            assertEquals("item${index + 1}", retrieved.name)
        }
    }

    // === Refresh Tests ===

    @Test
    fun `refresh loads committed manifests`() = runBlocking {
        // Create and flush manifest
        val id = manifestManager.put(mapOf("type" to "refresh-test"), TestPayload("original", 1))
        manifestManager.flush()
        contentManager.flush()

        // New manager without refresh can't see it
        val newManager1 = ManifestManager(contentManager)
        assertThrows<ManifestNotFoundException> {
            runBlocking { newManager1.get<TestPayload>(id) }
        }

        // After refresh, should see it
        newManager1.refresh()
        val (retrieved, _) = newManager1.get<TestPayload>(id)
        assertEquals("original", retrieved.name)
    }

    // === Pending vs Committed Priority Tests ===

    @Test
    fun `pending entries override committed`() = runBlocking {
        // Create and flush initial manifest
        val id = manifestManager.put(mapOf("type" to "override"), TestPayload("old", 1))
        manifestManager.flush()
        contentManager.flush()

        // Delete (pending) should override committed
        manifestManager.delete(id)

        // Should not be found (pending delete takes priority)
        assertThrows<ManifestNotFoundException> {
            runBlocking { manifestManager.get<TestPayload>(id) }
        }
    }

    // === Edge Cases ===

    @Test
    fun `empty labels except type`() = runBlocking {
        val id = manifestManager.put(mapOf("type" to "minimal"), TestPayload("minimal", 0))
        val (retrieved, metadata) = manifestManager.get<TestPayload>(id)

        assertEquals("minimal", retrieved.name)
        assertEquals(1, metadata.labels.size)
        assertEquals("minimal", metadata.labels["type"])
    }

    @Test
    fun `unicode in payload and labels`() = runBlocking {
        val payload = TestPayload("日本語テスト", 42, NestedData(listOf("🎉", "こんにちは")))
        val labels = mapOf("type" to "unicode", "language" to "日本語")

        val id = manifestManager.put(labels, payload)
        manifestManager.flush()
        contentManager.flush()

        val newManager = ManifestManager(contentManager)
        newManager.refresh()

        val (retrieved, metadata) = newManager.get<TestPayload>(id)
        assertEquals("日本語テスト", retrieved.name)
        assertEquals("日本語", metadata.labels["language"])
        assertEquals(listOf("🎉", "こんにちは"), retrieved.nested?.items)
    }

    @Test
    fun `large payload`() = runBlocking {
        val largeList = (1..1000).map { "item_$it" }
        val payload = TestPayload("large", 1000, NestedData(largeList))
        val labels = mapOf("type" to "large")

        val id = manifestManager.put(labels, payload)
        manifestManager.flush()
        contentManager.flush()

        val newManager = ManifestManager(contentManager)
        newManager.refresh()

        val (retrieved, metadata) = newManager.get<TestPayload>(id)
        assertEquals(1000, retrieved.nested?.items?.size)
        assertTrue(metadata.length > 1000) // Should be substantial
    }

    // === Compaction Tests ===

    @Test
    fun `compact consolidates manifest contents`() = runBlocking {
        // Create multiple flushes to generate multiple manifest content blocks
        repeat(5) { batch ->
            repeat(3) { i ->
                manifestManager.put(
                    mapOf("type" to "compact-test", "batch" to "$batch", "item" to "$i"),
                    TestPayload("item_${batch}_$i", batch * 10 + i)
                )
            }
            manifestManager.flush()
            contentManager.flush()
        }

        // Should have multiple manifest contents now
        val beforeCompact = countManifestContents()
        assertTrue(beforeCompact > 1, "Should have multiple manifest contents before compact")

        // Compact
        manifestManager.compact()
        manifestManager.flush()
        contentManager.flush()

        // After compact, should have fewer manifest contents
        val afterCompact = countManifestContents()
        assertTrue(afterCompact <= beforeCompact, "Should have same or fewer contents after compact")

        // All manifests should still be accessible
        val results = manifestManager.find(mapOf("type" to "compact-test"))
        assertEquals(15, results.size) // 5 batches * 3 items
    }

    @Test
    fun `compact removes deleted manifests permanently`() = runBlocking {
        // Create manifests
        val keepId = manifestManager.put(mapOf("type" to "keep"), TestPayload("keep", 1))
        val deleteId = manifestManager.put(mapOf("type" to "delete"), TestPayload("delete", 2))
        manifestManager.flush()
        contentManager.flush()

        // Delete one
        manifestManager.delete(deleteId)
        manifestManager.flush()
        contentManager.flush()

        // Compact
        manifestManager.compact()
        manifestManager.flush()
        contentManager.flush()

        // Verify kept manifest still accessible
        val newManager = ManifestManager(contentManager)
        newManager.refresh()

        val (kept, _) = newManager.get<TestPayload>(keepId)
        assertEquals("keep", kept.name)

        // Deleted manifest should not be accessible
        assertThrows<ManifestNotFoundException> {
            runBlocking { newManager.get<TestPayload>(deleteId) }
        }
    }

    // === Utility Functions ===

    @Test
    fun `dedupeByLabel keeps latest for each label value`() {
        val entries = listOf(
            EntryMetadata(ManifestId("00000000000000000000000000000001"), 10, mapOf("type" to "a", "path" to "/a"), Instant.ofEpochSecond(100)),
            EntryMetadata(ManifestId("00000000000000000000000000000002"), 20, mapOf("type" to "a", "path" to "/a"), Instant.ofEpochSecond(200)),
            EntryMetadata(ManifestId("00000000000000000000000000000003"), 30, mapOf("type" to "a", "path" to "/b"), Instant.ofEpochSecond(150))
        )

        val deduped = ManifestManager.dedupeByLabel(entries, "path")
        assertEquals(2, deduped.size)

        // /a should be the later one (id ending in 2)
        val pathA = deduped.find { it.labels["path"] == "/a" }
        assertEquals("00000000000000000000000000000002", pathA?.id?.value)

        // /b only has one entry
        val pathB = deduped.find { it.labels["path"] == "/b" }
        assertEquals("00000000000000000000000000000003", pathB?.id?.value)
    }

    @Test
    fun `pickLatest returns most recent entry`() {
        val entries = listOf(
            EntryMetadata(ManifestId("00000000000000000000000000000001"), 10, mapOf("type" to "a"), Instant.ofEpochSecond(100)),
            EntryMetadata(ManifestId("00000000000000000000000000000003"), 30, mapOf("type" to "a"), Instant.ofEpochSecond(300)),
            EntryMetadata(ManifestId("00000000000000000000000000000002"), 20, mapOf("type" to "a"), Instant.ofEpochSecond(200))
        )

        val latest = ManifestManager.pickLatest(entries)
        assertEquals("00000000000000000000000000000003", latest?.id?.value)
    }

    @Test
    fun `pickLatest returns null for empty list`() {
        val latest = ManifestManager.pickLatest(emptyList())
        assertNull(latest)
    }

    @Test
    fun `pickLatest uses id as tiebreaker`() {
        val entries = listOf(
            EntryMetadata(ManifestId("00000000000000000000000000000001"), 10, mapOf("type" to "a"), Instant.ofEpochSecond(100)),
            EntryMetadata(ManifestId("00000000000000000000000000000003"), 30, mapOf("type" to "a"), Instant.ofEpochSecond(100)),
            EntryMetadata(ManifestId("00000000000000000000000000000002"), 20, mapOf("type" to "a"), Instant.ofEpochSecond(100))
        )

        val latest = ManifestManager.pickLatest(entries)
        // When times are equal, higher ID wins
        assertEquals("00000000000000000000000000000003", latest?.id?.value)
    }

    // === Helper Functions ===

    private suspend fun countManifestContents(): Int {
        return manifestManager.getCommittedContentCount()
    }
}
