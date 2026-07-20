package org.kopiaKt.core.content

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm

/**
 * Epoch-mode index blob naming + epoch-marker handling (task-20 part 1).
 *
 * Byte-format-critical: Go's epoch index reader only sees index blobs named `xn<epoch>_<hash>-s<session>-c<N>`;
 * the flat `x<hash>-<session>` Kotlin wrote before was invisible to Go on epoch (FormatVersion 2/3) repos.
 * These lock the on-disk name shape (a cross-compat contract) without needing the Go binary; the
 * EpochIndexCrossCompatibilityTest e2e suite proves the real Go interop.
 */
@DisplayName("ContentManager epoch index blob naming (task-20)")
class ContentManagerEpochIndexTest {

    private fun contentManager(storage: InMemoryBlobStorage, epochsEnabled: Boolean) = ContentManager(
        storage = storage,
        hasherFactory = DefaultContentHasherFactory(),
        hashAlgorithm = HashAlgorithm.BLAKE2B_256_128,
        hashSecret = ByteArray(32) { (it + 1).toByte() },
        encryptorFactory = DefaultEncryptorFactory(),
        encryptionAlgorithm = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256,
        encryptionKey = ByteArray(32) { it.toByte() },
        compressorFactory = DefaultCompressorFactory(),
        defaultCompression = CompressionAlgorithm.NONE,
        maxPackSize = 20 * 1024 * 1024,
        epochsEnabled = epochsEnabled
    )

    private suspend fun blobIds(storage: InMemoryBlobStorage, prefix: String): List<String> =
        storage.listBlobs(prefix).toList().map { it.blobId.value }

    @Test
    fun `epoch mode writes Go-compatible xn0 uncompacted index blob names`() = runBlocking {
        val storage = InMemoryBlobStorage()
        val cm = contentManager(storage, epochsEnabled = true)
        cm.writeContent("epoch content".toByteArray())
        cm.flush()

        // A flush writes only index blobs under the "x" uber-prefix (packs are p/q, manifests are content).
        val xBlobs = blobIds(storage, "x")
        assertThat(xBlobs).isNotEmpty()
        // Every one must carry Go's uncompacted-epoch name xn<epoch>_<32-hex>-s<session>-c<shardCount>
        // (epoch 0 for a fresh marker-less repo) — NOT the old flat "x<hash>-<session>".
        xBlobs.forEach { assertThat(it).matches("^xn0_[0-9a-f]{32}-s[0-9a-f]+-c[0-9]+$") }
    }

    @Test
    fun `legacy (non-epoch) mode writes n-prefixed index blob names`() = runBlocking {
        val storage = InMemoryBlobStorage()
        val cm = contentManager(storage, epochsEnabled = false)
        cm.writeContent("legacy content".toByteArray())
        cm.flush()

        val indexBlobs = blobIds(storage, "n")
        assertThat(indexBlobs).isNotEmpty()
        indexBlobs.forEach { assertThat(it).matches("^n[0-9a-f]{32}-[0-9a-f]+$") }
        // No epoch-style index blobs.
        assertThat(blobIds(storage, "xn")).isEmpty()
    }

    @Test
    fun `writer discovers the current epoch from xe markers`() = runBlocking {
        val storage = InMemoryBlobStorage()
        // Pretend the repo advanced to epoch 3 (marker blob is plaintext "epoch-marker").
        storage.putBlob(BlobId("xe3"), "epoch-marker".toByteArray())

        val cm = contentManager(storage, epochsEnabled = true)
        cm.writeContent("content for epoch 3".toByteArray())
        cm.flush()

        val indexBlobs = blobIds(storage, "xn")
        assertThat(indexBlobs).isNotEmpty()
        indexBlobs.forEach { assertThat(it).startsWith("xn3_") }
    }

    @Test
    fun `epoch marker blobs are skipped on load without flagging the index incomplete`() = runBlocking {
        val storage = InMemoryBlobStorage()
        val cm = contentManager(storage, epochsEnabled = true)
        val id = cm.writeContent("still readable".toByteArray())
        cm.flush()

        // Inject plaintext epoch marker + deletion watermark blobs (share the "x" uber-prefix). They must
        // be skipped as control blobs, NOT mis-parsed as corrupt index blobs (which would flag the load
        // incomplete and make delete-GC refuse — see task-9 completeness gate).
        storage.putBlob(BlobId("xe1"), "epoch-marker".toByteArray())
        storage.putBlob(BlobId("xw1700000000"), "deletion-watermark".toByteArray())

        cm.refresh()

        assertThat(cm.isIndexLoadComplete()).isTrue()
        assertThat(cm.getContentInfo(id)).isNotNull()
    }
}
