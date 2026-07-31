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
import org.kopiaKt.core.format.EpochParameters
import org.kopiaKt.core.format.FormatVersion
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.repository.DirectRepositoryImpl
import java.security.SecureRandom

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
        epochsEnabled = epochsEnabled,
    )

    private suspend fun blobIds(storage: InMemoryBlobStorage, prefix: String): List<String> = storage.listBlobs(prefix).toList().map { it.blobId.value }

    @Test
    fun `epoch mode writes Go-compatible xn0 uncompacted index blob names`(): Unit = runBlocking {
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
    fun `legacy (non-epoch) mode writes n-prefixed index blob names`(): Unit = runBlocking {
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
    fun `writer discovers the current epoch from xe markers`(): Unit = runBlocking {
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
    fun `epoch marker blobs are skipped on load without flagging the index incomplete`(): Unit = runBlocking {
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

    @Test
    fun `isEpochIndexEnabled follows the format version, not the epochParameters flag`() {
        // A pre-0.9 (FormatVersion 1) format blob may OMIT the epoch key, which deserializes to the truthy
        // EpochParameters.DEFAULT. Epoch-mode detection must key on the version (Go couples them), so such a
        // legacy repo is NOT falsely treated as epoch mode.
        fun cfg(v: Int, ep: EpochParameters) = RepositoryConfig(hash = "BLAKE2B-256-128", encryption = "AES256-GCM-HMAC-SHA256", version = v, epochParameters = ep)

        assertThat(cfg(FormatVersion.V1.value, EpochParameters.DEFAULT).isEpochIndexEnabled()).isFalse()
        assertThat(cfg(FormatVersion.V1.value, EpochParameters.DISABLED).isEpochIndexEnabled()).isFalse()
        assertThat(cfg(FormatVersion.V2.value, EpochParameters.DEFAULT).isEpochIndexEnabled()).isTrue()
        assertThat(cfg(FormatVersion.V3.value, EpochParameters.DEFAULT).isEpochIndexEnabled()).isTrue()
    }

    @Test
    fun `a FormatVersion 1 repo writes legacy n-prefixed index blobs even if epochParameters says enabled`(): Unit = runBlocking {
        // Simulates opening a pre-0.9 legacy repo whose format blob omits the epoch key (deserialized to the
        // truthy EpochParameters.DEFAULT): the runtime must still write Go's V0 legacy "n<hash>" index names,
        // NOT "xn0_", or Go's V0 reader on that legacy repo cannot see Kotlin's writes. (task-20 misdetection)
        val storage = InMemoryBlobStorage()
        val random = SecureRandom()
        val config = RepositoryConfig(
            hash = "BLAKE2B-256-128",
            encryption = "AES256-GCM-HMAC-SHA256",
            secret = ByteArray(32).also { random.nextBytes(it) },
            masterKey = ByteArray(32).also { random.nextBytes(it) },
            splitter = "FIXED-1M",
            version = FormatVersion.V1.value,
            epochParameters = EpochParameters.DEFAULT, // truthy but must be IGNORED for a V1 repo
            enablePasswordChange = false,
        )
        val repo = DirectRepositoryImpl.create(storage, "pw", config)
        repo.use {
            val writer = repo.newDirectWriter()
            writer.writeObject("legacy content".toByteArray())
            writer.flush()
            writer.close()
        }

        assertThat(blobIds(storage, "xn")).isEmpty()
        val legacyIndex = blobIds(storage, "n").filter { Regex("^n[0-9a-f]{32}-").containsMatchIn(it) }
        assertThat(legacyIndex).isNotEmpty()
    }
}
