package org.kopiaKt.core.content

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.RepositoryUnavailableException
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.testutil.TestRepositoryFactory
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * What happens to a write session when a pack blob cannot be written (task-72).
 *
 * `flushCurrentPackUnlocked` builds the pack before uploading it, and building permanently marks the
 * builder built. When the upload then failed — a dropped connection, a destination that went away
 * (task-65), a full disk — the builder was left built with its contents still pending, so the run
 * carried on writing content that deduplicated against a pack which is not in storage, and the NEXT
 * flush failed with `"Pack has already been built"`: an error about the manager's own bookkeeping,
 * pointing nowhere near the network failure that actually happened.
 */
@DisplayName("ContentManager pack writes")
class ContentManagerPackWriteTest {

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /**
     * Every manager in a test shares one config, because the reader in
     * [flush does not return while a pack is still being written] has to decrypt what the writer
     * wrote — `TestRepositoryFactory.createConfig()` generates fresh random keys per call.
     */
    private fun newManager(storage: BlobStorage, config: RepositoryConfig): ContentManager = ContentManager(
        storage = storage,
        hasherFactory = DefaultContentHasherFactory(),
        hashAlgorithm = HashAlgorithm.fromId(config.hash)!!,
        hashSecret = config.secret,
        encryptorFactory = DefaultEncryptorFactory(),
        encryptionAlgorithm = EncryptionAlgorithm.fromId(config.encryption)!!,
        encryptionKey = config.masterKey,
        compressorFactory = DefaultCompressorFactory(),
        // Small enough that a handful of 16 KB chunks fills a pack, so a flush happens mid-write
        // exactly as it does on a real backup at 20 MB.
        maxPackSize = SMALL_PACK,
    )

    /** Every message in the chain, so a wrapper can neither hide the cause nor fake it. */
    private fun messages(e: Throwable) = generateSequence(e) { it.cause }.mapNotNull { it.message }.toList()

    /**
     * task-72's first acceptance criterion: after the upload fails, the next flush must report the
     * upload failure and not the builder's own single-use guard.
     */
    @Test
    fun `after a pack upload fails the next flush names the upload failure`(): Unit = runBlocking {
        val backing = InMemoryBlobStorage()
        val storage = object : BlobStorage by backing {
            override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
                if (blobId.value.startsWith("p")) throw IOException("the network went away")
                backing.putBlob(blobId, data, options)
            }
        }
        val manager = newManager(storage, TestRepositoryFactory.createConfig())

        val duringWrite = assertThrows<Exception> {
            repeat(CHUNKS_PER_TWO_PACKS) { manager.writeContent(randomBytes(CHUNK_SIZE)) }
        }
        assertThat(messages(duringWrite)).contains("the network went away")

        val atFlush = assertThrows<Exception> { manager.flush() }
        assertThat(messages(atFlush)).contains("the network went away")
        assertThat(messages(atFlush).joinToString()).doesNotContain("already been built")
    }

    /**
     * The decision recorded for task-72's third criterion: the session does not carry on.
     *
     * Retrying the upload IS supported — at the two layers that can actually make it end
     * differently. `RetryingBlobStorage` wraps every remote backend (S3, WebDAV, SFTP) and has
     * already retried this write with backoff before the exception reaches here, and WorkManager
     * re-runs the whole backup in a fresh session, which `BackupWorker.isTerminalFailure`
     * deliberately still allows for an I/O failure. What is NOT kept is Go's in-session
     * `failedPacks` queue: a pack whose blob-level retries are exhausted has no different outcome
     * available inside this session, and holding its ~20 MB alive for an attempt nothing is waiting
     * on is precisely the retention that killed a 190 MB backup in task-59.
     *
     * So the write session ends at the first pack it could not store, saying why — instead of
     * deduplicating later content against a pack that is not in the repository.
     *
     * The failure staged here is `RepositoryUnavailableException`, and asserting that **exact type**
     * comes back out is the point of the test, not decoration. That is the destination-has-gone case
     * from task-65, and `BackupWorker.isTerminalFailure` classifies it by TYPE without walking the
     * cause chain — so wrapping the recorded cause in any new exception would silently turn a
     * terminal failure back into retries against a repository that is not there. A message-only
     * assertion would not notice.
     */
    @Test
    fun `a write session does not continue past a pack it could not store`(): Unit = runBlocking {
        val backing = InMemoryBlobStorage()
        val packWrites = AtomicInteger()
        val storage = object : BlobStorage by backing {
            override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
                if (blobId.value.startsWith("p") && packWrites.incrementAndGet() == 1) {
                    throw RepositoryUnavailableException("the backup destination is gone")
                }
                backing.putBlob(blobId, data, options)
            }
        }
        val manager = newManager(storage, TestRepositoryFactory.createConfig())

        assertThrows<RepositoryUnavailableException> {
            repeat(CHUNKS_PER_TWO_PACKS) { manager.writeContent(randomBytes(CHUNK_SIZE)) }
        }

        // The very next write fails with the real cause, unwrapped, even though storage would now
        // accept it.
        val next = assertThrows<RepositoryUnavailableException> { manager.writeContent(randomBytes(CHUNK_SIZE)) }
        assertThat(messages(next)).contains("the backup destination is gone")
        assertThat(messages(next).joinToString()).doesNotContain("already been built")
    }

    /**
     * The half of the guard the test above does not reach, and it is the one that loses data.
     *
     * A pack holds chunks from many files, and only the ONE caller whose write happened to fill it
     * ever sees the storage failure. Ask for any of the OTHER chunks again and the dedup check finds
     * them — they are still sitting in `pendingContents`, because the pack that was going to make
     * them durable is the pack that did not get written. Answering "already stored" there hands the
     * caller a content id that is in no pack blob and no index, and it goes into a snapshot that
     * looks complete.
     *
     * (Found by mutation: with the guard removed from the dedup lock alone, the test above still
     * passed — it only ever asks for content the manager has never seen, so it is answered by the
     * guard in `addEncryptedContent` instead.)
     */
    @Test
    fun `content from the lost pack is not reported as already stored`(): Unit = runBlocking {
        val backing = InMemoryBlobStorage()
        val packWrites = AtomicInteger()
        val storage = object : BlobStorage by backing {
            override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
                if (blobId.value.startsWith("p") && packWrites.incrementAndGet() == 1) {
                    throw IOException("the disk is full")
                }
                backing.putBlob(blobId, data, options)
            }
        }
        val manager = newManager(storage, TestRepositoryFactory.createConfig())

        // The first chunk lands in the pack that fails; a later chunk is the one that fills it, so
        // it is that caller — not this one — who is told anything went wrong.
        val first = randomBytes(CHUNK_SIZE)
        manager.writeContent(first)
        assertThrows<Exception> {
            repeat(CHUNKS_PER_TWO_PACKS) { manager.writeContent(randomBytes(CHUNK_SIZE)) }
        }

        val again = assertThrows<Exception> { manager.writeContent(first) }
        assertThat(messages(again)).contains("the disk is full")
    }

    /**
     * The other half of the spent-builder problem, found by review: it is not only `putBlob` that can
     * throw after the builder is spent.
     *
     * `buildEncrypted` calls `finalizeContents()` — which sets `built = true` — and only THEN
     * serializes the local index and copies the buffer out. `PackIndexV1.build` refuses any entry
     * carrying a non-zero compression header, and `toByteArray()` can run out of memory on a 20 MB
     * pack (task-59's territory). Either leaves exactly the spent builder task-72 is about, so the
     * guard has to cover the build, not just the upload.
     *
     * Driven through the compression route because it is deterministic: a manager whose default
     * compression is zstd, given compressible bytes, stamps a content-level header that the V1 index
     * writer then rejects. Storage here never fails at all — the failure is entirely local, which is
     * the point.
     */
    @Test
    fun `a pack that cannot be built reports that, not the spent builder`(): Unit = runBlocking {
        val config = TestRepositoryFactory.createConfig()
        val storage = InMemoryBlobStorage()
        val manager = ContentManager(
            storage = storage,
            hasherFactory = DefaultContentHasherFactory(),
            hashAlgorithm = HashAlgorithm.fromId(config.hash)!!,
            hashSecret = config.secret,
            encryptorFactory = DefaultEncryptorFactory(),
            encryptionAlgorithm = EncryptionAlgorithm.fromId(config.encryption)!!,
            encryptionKey = config.masterKey,
            compressorFactory = DefaultCompressorFactory(),
            defaultCompression = CompressionAlgorithm.ZSTD_DEFAULT,
            maxPackSize = SMALL_PACK,
        )

        // Compressible, so maybeCompress really does stamp a header (it keeps the compressed form
        // only when it is smaller). One content and an explicit flush, rather than filling a pack:
        // this data compresses about a thousand to one, so it would never reach the size limit.
        manager.writeContent(ByteArray(CHUNK_SIZE) { (it % 7).toByte() })

        val building = assertThrows<Exception> { manager.flush() }
        assertThat(messages(building).joinToString()).contains("Compression not supported in index V1")

        val again = assertThrows<Exception> { manager.flush() }
        assertThat(messages(again).joinToString()).contains("Compression not supported in index V1")
        assertThat(messages(again).joinToString()).doesNotContain("already been built")
    }

    /**
     * The fourth route onto the spent builder, which both reviewers found and my first fix missed.
     *
     * `deleteContent` calls `flushCurrentPackUnlocked()` when the content it is tombstoning is still
     * in the unflushed pack — so guarding `flush()` and the write path but not this one left the
     * original symptom reachable through a door the tests were not knocking on. It is not a
     * hypothetical API: `ManifestManager.deleteSupersededContent` reaches it from manifest
     * compaction during an ordinary backup.
     *
     * Fixed by moving the guard into `flushCurrentPackUnlocked` itself, which is the only place all
     * four routes pass through.
     */
    @Test
    fun `deleting content after a failed pack write reports the failure`(): Unit = runBlocking {
        val backing = InMemoryBlobStorage()
        val packWrites = AtomicInteger()
        val storage = object : BlobStorage by backing {
            override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
                if (blobId.value.startsWith("p") && packWrites.incrementAndGet() == 1) {
                    throw IOException("the disk is full")
                }
                backing.putBlob(blobId, data, options)
            }
        }
        val manager = newManager(storage, TestRepositoryFactory.createConfig())

        val stranded = randomBytes(CHUNK_SIZE)
        val strandedId = manager.writeContent(stranded)
        assertThrows<Exception> {
            repeat(CHUNKS_PER_TWO_PACKS) { manager.writeContent(randomBytes(CHUNK_SIZE)) }
        }

        val deleted = assertThrows<Exception> { manager.deleteContent(strandedId) }
        assertThat(messages(deleted)).contains("the disk is full")
        assertThat(messages(deleted).joinToString()).doesNotContain("already been built")
    }

    /**
     * After `flush()`, every content a writer was told was stored must be readable by a session that
     * knows only what the index blobs say. Said plainly because it is easy to claim more: **today
     * there is no in-flight window to race** — the pack write still happens under the mutex, inside
     * `writeContent`, so no pack is ever being uploaded once the writers have returned. This test
     * cannot and does not prove `flush()` waits for a concurrent upload.
     *
     * What it is, is the guard that a future move of the pack write OFF the mutex has to satisfy.
     * The index blob is what makes content findable, and `flushIndexUnlocked` writes it from the
     * packs whose upload has completed. A version of `flush()` that returned while a pack was still
     * uploading would leave that blob in storage with nothing in any index pointing at it — and the
     * snapshot referencing it would be saved, look complete, and restore to a hole. The deliberately
     * slow pack write is there so such a version would actually lose the race and go red, rather
     * than pass by being too fast to notice.
     *
     * It is not decoration in the meantime: deleting `flushCurrentPackUnlocked()` from `flush()`
     * turns it red today, because the last partial pack then never reaches storage or the index.
     */
    @Test
    fun `everything written is readable from the index alone after flush`(): Unit = runBlocking {
        val config = TestRepositoryFactory.createConfig()
        val backing = InMemoryBlobStorage()
        val storage = object : BlobStorage by backing {
            override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
                if (blobId.value.startsWith("p")) delay(SLOW_PACK_WRITE_MS)
                backing.putBlob(blobId, data, options)
            }
        }
        val manager = newManager(storage, config)
        val chunks = List(CHUNK_COUNT) { randomBytes(CHUNK_SIZE) }

        val ids = runBlocking(Dispatchers.Default) {
            chunks.chunked(CHUNK_COUNT / 4)
                .map { batch -> async { batch.map { manager.writeContent(it) } } }
                .awaitAll()
        }.flatten()
        manager.flush()

        // Several packs, so the index really has to span more than the last one.
        assertThat(backing.listBlobs("p").toList().size).isGreaterThan(1)

        val reader = newManager(storage, config)
        reader.refresh()
        var indexed = 0
        reader.iterateContentInfos(includeDeleted = false) { indexed++ }
        assertThat(indexed).isEqualTo(chunks.size)

        // Indexed is not the same as readable: an index written before its blob landed would satisfy
        // the count above and still restore to nothing. Read the bytes back through the index.
        ids.forEachIndexed { i, id ->
            assertThat(reader.getContent(id)).isEqualTo(chunks[i])
        }
    }

    private companion object {
        const val SMALL_PACK = 64 * 1024
        const val CHUNK_SIZE = 16 * 1024

        /** Enough 16 KB chunks to fill [SMALL_PACK] twice over, so a mid-write flush is reached. */
        const val CHUNKS_PER_TWO_PACKS = 8

        const val CHUNK_COUNT = 32

        const val SLOW_PACK_WRITE_MS = 150L
    }
}
