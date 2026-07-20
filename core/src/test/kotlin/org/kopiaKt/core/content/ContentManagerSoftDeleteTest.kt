package org.kopiaKt.core.content

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm

/**
 * Soft-delete / undelete + include-deleted iteration for snapshot GC Phase 2 (task-9 #3).
 *
 * Mirrors Go kopia's content tombstone model: deleting content writes a new index entry with
 * `deleted=true` and a STRICTLY-increasing timestamp; the committed-index merge resolves the winner
 * per content id by `contentInfoGreaterThan` (higher timestamp wins; tie -> non-deleted). Undelete
 * re-writes a live entry. These are the data-loss-critical invariants — a wrong merge either
 * resurrects deleted content or hides live content.
 */
@DisplayName("ContentManager soft-delete / undelete (GC Phase 2 API)")
class ContentManagerSoftDeleteTest {

    private lateinit var storage: InMemoryBlobStorage
    private lateinit var cm: ContentManager

    @BeforeEach
    fun setup() {
        storage = InMemoryBlobStorage()
        cm = ContentManager(
            storage = storage,
            hasherFactory = DefaultContentHasherFactory(),
            hashAlgorithm = HashAlgorithm.BLAKE2B_256_128,
            hashSecret = ByteArray(32) { (it + 1).toByte() },
            encryptorFactory = DefaultEncryptorFactory(),
            encryptionAlgorithm = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256,
            encryptionKey = ByteArray(32) { it.toByte() },
            compressorFactory = DefaultCompressorFactory(),
            defaultCompression = CompressionAlgorithm.NONE,
            maxPackSize = 20 * 1024 * 1024
        )
    }

    private suspend fun collect(includeDeleted: Boolean): Map<ContentId, ContentInfo> {
        val out = mutableMapOf<ContentId, ContentInfo>()
        cm.iterateContentInfos(includeDeleted) { out[it.contentId] = it }
        return out
    }

    @Test
    fun `deleteContent hides content and leaves a tombstone visible only with includeDeleted`() = runBlocking {
        val id = cm.writeContent("hello world".toByteArray())
        cm.flush()
        assertThat(cm.getContentInfo(id)).isNotNull()

        cm.deleteContent(id)

        assertThat(cm.getContentInfo(id)).isNull()
        assertThat(collect(includeDeleted = false)).doesNotContainKey(id)
        val withDeleted = collect(includeDeleted = true)
        assertThat(withDeleted).containsKey(id)
        assertThat(withDeleted.getValue(id).deleted).isTrue()
    }

    @Test
    fun `undeleteContent revives a deleted content and it is readable again`() = runBlocking {
        val id = cm.writeContent("data".toByteArray())
        cm.flush()
        cm.deleteContent(id)
        assertThat(cm.getContentInfo(id)).isNull()

        cm.undeleteContent(id)

        val info = cm.getContentInfo(id)
        assertThat(info).isNotNull()
        assertThat(info!!.deleted).isFalse()
        assertThat(cm.getContent(id).decodeToString()).isEqualTo("data")
    }

    @Test
    fun `deleteContent no-ops on already-deleted and undelete no-ops on live`() = runBlocking {
        val id = cm.writeContent("x".toByteArray())
        cm.flush()

        cm.undeleteContent(id) // live -> no-op
        assertThat(cm.getContentInfo(id)).isNotNull()

        cm.deleteContent(id)
        val firstTs = collect(true).getValue(id).timestampSeconds
        cm.deleteContent(id) // already deleted -> no-op, no new timestamp
        assertThat(collect(true).getValue(id).timestampSeconds).isEqualTo(firstTs)
    }

    @Test
    fun `tombstone survives flush + reload and last-writer-wins across index blobs`() = runBlocking {
        val id = cm.writeContent("payload".toByteArray())
        cm.flush()
        cm.deleteContent(id)
        cm.flush()
        cm.refresh() // reload committed indexes from storage (merge across blobs)

        assertThat(cm.getContentInfo(id)).isNull()
        assertThat(collect(true).getValue(id).deleted).isTrue()

        cm.undeleteContent(id)
        cm.flush()
        cm.refresh()
        assertThat(cm.getContentInfo(id)).isNotNull()
        assertThat(collect(true).getValue(id).deleted).isFalse()
    }

    @Test
    fun `re-writing deleted content resurrects it as live, beating the tombstone even in the same second`() = runBlocking {
        val bytes = "resurrect me".toByteArray()
        val id = cm.writeContent(bytes)
        cm.flush()
        cm.deleteContent(id)
        cm.flush()
        assertThat(cm.getContentInfo(id)).isNull()

        // Re-writing the same bytes (a new snapshot references GC-deleted content) must NOT dedup to the
        // tombstone: it writes a fresh live entry stamped contentWriteTime(tombstone), so it wins the
        // merge even when the re-write lands in the same wall-clock second as the delete.
        val id2 = cm.writeContent(bytes)
        assertThat(id2).isEqualTo(id)
        val info = cm.getContentInfo(id)
        assertThat(info).isNotNull()
        assertThat(info!!.deleted).isFalse()
        assertThat(cm.getContent(id).decodeToString()).isEqualTo("resurrect me")

        // And it stays live across flush + reload.
        cm.flush()
        cm.refresh()
        assertThat(cm.getContentInfo(id)).isNotNull()
        assertThat(cm.getContentInfo(id)!!.deleted).isFalse()
    }

    @Test
    fun `getContent and getContentInfo agree that a deleted content is absent`() = runBlocking {
        val id = cm.writeContent("bytes".toByteArray())
        cm.flush()
        cm.deleteContent(id) // tombstone in writtenContents, live entry still committed

        assertThat(cm.getContentInfo(id)).isNull()
        var threw = false
        try {
            cm.getContent(id)
        } catch (e: ContentNotFoundException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `deleteContent on unflushed pending content actually deletes it and it stays deleted`() = runBlocking {
        val id = cm.writeContent("pending".toByteArray()) // NOT flushed
        cm.deleteContent(id)

        assertThat(cm.getContentInfo(id)).isNull()
        // Must survive the subsequent pending-pack flush + reload (not resurrected by the live entry).
        cm.flush()
        cm.refresh()
        assertThat(cm.getContentInfo(id)).isNull()
        assertThat(collect(true).getValue(id).deleted).isTrue()
    }

    @Test
    fun `delete-undelete-delete resolves to deleted via strictly increasing timestamps`() = runBlocking {
        val id = cm.writeContent("z".toByteArray())
        cm.flush()
        cm.deleteContent(id); cm.flush()
        cm.undeleteContent(id); cm.flush()
        cm.deleteContent(id); cm.flush()
        cm.refresh()

        // Even if all ops land in one wall-clock second, max(now, prev+1) makes each timestamp strictly
        // greater, so the final delete wins. A naive now()-only clock would tie and mis-resolve.
        assertThat(cm.getContentInfo(id)).isNull()
        assertThat(collect(true).getValue(id).deleted).isTrue()
    }
}
