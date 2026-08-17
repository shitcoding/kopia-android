package org.kopiaKt.core.content

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.testutil.TestRepositoryFactory
import java.io.IOException
import java.security.SecureRandom

/**
 * A refresh that fails must leave the committed view alone (task-71).
 *
 * `loadCommittedIndexes` cleared `committedIndexes` and `committedContents` **in place** and only
 * then listed the index blobs. A per-blob failure was already handled — the blob is skipped and the
 * load is marked partial — but a failure from the listing itself (a dropped connection on a remote
 * backend, a directory that went away) exits after the clear and before anything is read back. The
 * committed view is then EMPTY rather than stale, and stays that way until some later refresh
 * succeeds.
 *
 * Empty is not a harmless kind of wrong here:
 * - every content looks absent, so dedup stops and a backup re-uploads what it already stored;
 * - a **tombstone** becomes invisible, and the resurrect path stamps its new live entry from
 *   `currentInfoUnlocked` precisely so it beats the tombstone it supersedes. With the tombstone
 *   hidden it falls back to plain `now`, which usually wins — and "usually" is the wrong guarantee
 *   for the merge that decides whether deleted content comes back.
 */
@DisplayName("ContentManager refresh failure")
class ContentManagerRefreshFailureTest {

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /** Fails `listBlobs` on demand, so a refresh can be broken after the view is already populated. */
    private class BreakableStorage(private val delegate: BlobStorage) : BlobStorage by delegate {
        @Volatile
        var listShouldFail = false

        override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> {
            if (listShouldFail) {
                // Thrown from inside the flow, which is where a real backend's connection drops:
                // the collector has already been handed a Flow before anything goes wrong.
                return flow { throw IOException("the connection dropped mid-listing") }
            }
            return delegate.listBlobs(prefix)
        }
    }

    private fun newManager(storage: BlobStorage, config: RepositoryConfig) = ContentManager(
        storage = storage,
        hasherFactory = DefaultContentHasherFactory(),
        hashAlgorithm = HashAlgorithm.fromId(config.hash)!!,
        hashSecret = config.secret,
        encryptorFactory = DefaultEncryptorFactory(),
        encryptionAlgorithm = EncryptionAlgorithm.fromId(config.encryption)!!,
        encryptionKey = config.masterKey,
        compressorFactory = DefaultCompressorFactory(),
    )

    @Test
    fun `content stays visible when a refresh fails while listing`(): Unit = runBlocking {
        val config = TestRepositoryFactory.createConfig()
        val storage = BreakableStorage(InMemoryBlobStorage())
        val manager = newManager(storage, config)

        val data = randomBytes(32 * 1024)
        val contentId = manager.writeContent(data)
        manager.flush()
        manager.refresh()
        assertThat(manager.getContentInfo(contentId)).isNotNull()

        storage.listShouldFail = true
        assertThrows<IOException> { manager.refresh() }

        // Stale is fine. Empty is not: it says the content is gone, and dedup believes it.
        assertThat(manager.getContentInfo(contentId)).isNotNull()
        assertThat(manager.getContent(contentId)).isEqualTo(data)
    }

    /**
     * The sharper half: a tombstone that a failed refresh made invisible.
     *
     * By this point the tombstone lives ONLY in the committed view — `flush()` promoted it out of
     * `writtenContents` — so if a failed refresh empties that view the tombstone is gone from
     * memory entirely. This asserts exactly that and no more: the tombstone is still there
     * afterwards. What makes it matter rather than merely untidy is documented on the class: the
     * resurrect path reads the entry it must supersede in order to out-stamp it, and it cannot read
     * one that is not there.
     */
    @Test
    fun `a tombstone survives a failed refresh`(): Unit = runBlocking {
        val config = TestRepositoryFactory.createConfig()
        val storage = BreakableStorage(InMemoryBlobStorage())
        val manager = newManager(storage, config)

        val contentId = manager.writeContent(randomBytes(16 * 1024))
        manager.flush()
        manager.deleteContent(contentId)
        manager.flush()
        manager.refresh()
        assertThat(manager.getContentInfo(contentId)).isNull() // deleted

        storage.listShouldFail = true
        assertThrows<IOException> { manager.refresh() }

        var tombstones = 0
        manager.iterateContentInfos(includeDeleted = true) { if (it.contentId == contentId && it.deleted) tombstones++ }
        assertThat(tombstones).isEqualTo(1)
    }

    /**
     * The one that actually pins the flag, and it only exists because the obvious version did not.
     *
     * `isIndexLoadComplete` is what snapshot GC's delete path fails closed on — a partial view can
     * make live content look unreferenced. So the dangerous direction is a failed refresh silently
     * upgrading a PARTIAL view to complete, and that needs a partial view to start from: here, an
     * index blob overwritten with bytes that cannot be decrypted, which the per-blob handler skips
     * while recording that the view is incomplete.
     *
     * Found by review. The sibling test below starts from a COMPLETE view, so it cannot tell the
     * correct code from a version that assigns `indexLoadComplete = true` before loading — both
     * leave it true. I had claimed it could; running that mutation showed all three tests green.
     */
    @Test
    fun `a failed refresh does not upgrade a partial view to complete`(): Unit = runBlocking {
        val config = TestRepositoryFactory.createConfig()
        val backing = InMemoryBlobStorage()
        val storage = BreakableStorage(backing)
        val manager = newManager(storage, config)

        manager.writeContent(randomBytes(8 * 1024))
        manager.flush()

        // Make one index blob unreadable, so the next load is legitimately partial.
        val indexBlob = backing.listBlobs("n").toList().single()
        backing.putBlob(indexBlob.blobId, randomBytes(64))

        manager.refresh()
        assertThat(manager.isIndexLoadComplete()).isFalse()

        storage.listShouldFail = true
        assertThrows<IOException> { manager.refresh() }

        // Still partial. A refresh that could not run has not repaired anything.
        assertThat(manager.isIndexLoadComplete()).isFalse()
    }

    /**
     * The other direction: a complete view must not be marked partial by a refresh that never got
     * far enough to learn anything about it.
     */
    @Test
    fun `a failed refresh does not report the surviving view as partial`(): Unit = runBlocking {
        val config = TestRepositoryFactory.createConfig()
        val storage = BreakableStorage(InMemoryBlobStorage())
        val manager = newManager(storage, config)

        manager.writeContent(randomBytes(8 * 1024))
        manager.flush()
        manager.refresh()
        assertThat(manager.isIndexLoadComplete()).isTrue()

        storage.listShouldFail = true
        assertThrows<IOException> { manager.refresh() }

        assertThat(manager.isIndexLoadComplete()).isTrue()
    }
}
