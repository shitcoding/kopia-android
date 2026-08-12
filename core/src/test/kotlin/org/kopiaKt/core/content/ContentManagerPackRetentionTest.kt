package org.kopiaKt.core.content

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm

/**
 * A pack that has been written to storage is not also kept in memory (task-59).
 *
 * It used to be: every flushed pack's full bytes stayed in a `writtenPacks` map until the index was
 * flushed, as a read cache. A backup of a few large files therefore retained every 20 MB pack it had
 * written — measured on a device, the heap grew in step with the bytes hashed and a 190 MB source
 * died with OutOfMemoryError 75 seconds in, WorkManager retrying it into the same wall.
 *
 * Removing that cache is only safe because the pack is already durable when it is recorded
 * (`flushCurrentPackUnlocked` calls `storage.putBlob` first), so a read finds it in storage — as a
 * RANGED read of one content rather than a whole pack. These tests pin that: content stays readable
 * across a pack flush, and the small pack size makes several packs while the index is still pending.
 */
@DisplayName("ContentManager pack retention (task-59)")
class ContentManagerPackRetentionTest {

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
            // Small enough that a handful of writes rolls several packs, which is the situation the
            // retention bug scaled with. The index is not flushed until flush() below.
            maxPackSize = 64 * 1024,
        )
    }

    @Test
    fun `content stays readable after its pack rolls, with the index still unflushed`(): Unit = runBlocking {
        val payloads = (0 until 8).map { i -> ByteArray(24 * 1024) { (i + it).toByte() } }

        val ids = payloads.map { cm.writeContent(it) }

        // Several packs are in storage by now and none of them is in the committed index yet. The
        // first ones were readable only from the in-memory cache before this change.
        val packs = mutableListOf<String>()
        storage.listBlobs("p").collect { packs.add(it.blobId.value) }
        assertThat(packs.size).isAtLeast(2)
        for ((i, id) in ids.withIndex()) {
            assertThat(cm.getContent(id)).isEqualTo(payloads[i])
        }

        cm.flush()
        for ((i, id) in ids.withIndex()) {
            assertThat(cm.getContent(id)).isEqualTo(payloads[i])
        }
    }

    @Test
    fun `a read of flushed-pack content goes to storage rather than a retained copy`(): Unit = runBlocking {
        val payload = ByteArray(24 * 1024) { it.toByte() }
        val id = cm.writeContent(payload)
        // Roll the pack: this content's bytes are now in storage and nowhere else.
        repeat(3) { i -> cm.writeContent(ByteArray(24 * 1024) { (it + i + 1).toByte() }) }

        storage.deleteBlob(cm.getContentInfo(id)!!.packBlobId)

        // If the pack bytes were still being held in memory this would succeed and prove nothing.
        // Pinned to the absence, not to any failure: a decrypt or range error would mean something
        // else entirely.
        assertThrows<BlobNotFoundException> { runBlocking { cm.getContent(id) } }
    }
}
