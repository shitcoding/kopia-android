package org.kopiaKt.core.content

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.encryption.Encryptor
import org.kopiaKt.core.encryption.EncryptorFactory
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What moving compression and encryption out of `ContentManager`'s mutex must not break (task-66).
 *
 * That mutex used to span hashing, the dedup lookup, compression, encryption, pack assembly and the
 * pack write, so four upload workers ran one at a time — measured at **1.13x** from four workers
 * instead of one, i.e. the parallelism was decorative. Hashing, compression and encryption are pure
 * functions of their inputs, so they now happen outside it, and the lock covers only the state it
 * protects.
 *
 * The cost is a race that did not exist before: two workers can both miss the dedup check for the
 * same content and both encrypt it. Content is addressed by its hash so both produce the same id,
 * and the second one is dropped by a re-check inside the lock — these tests are what say so.
 */
@DisplayName("Concurrent writeContent")
class ConcurrentWriteContentTest {

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /**
     * The race the change introduces, driven directly: the SAME bytes written by many workers at
     * once, which is exactly the case that can pass the dedup check twice.
     */
    @Test
    fun `the same content written concurrently is stored once and reads back intact`(): Unit = runBlocking {
        val (repo, storage) = TestRepositoryFactory.createInMemory()
        val data = randomBytes(256 * 1024)

        val writer = repo.newDirectWriter(WriteSessionOptions(purpose = "race"))
        val ids = runBlocking(Dispatchers.Default) {
            (1..16).map { async { writer.writeObject(data) } }.awaitAll()
        }
        writer.flush()
        repo.refresh()

        // Content-addressed: every writer must agree on the id, whoever won.
        assertThat(ids.distinct()).hasSize(1)
        assertThat(repo.readObject(ids.first())).isEqualTo(data)

        // Stored once, not sixteen times: 256 KB of incompressible data sixteen times over would be
        // 4 MB of pack blobs. Without the re-check inside the lock the losers of the race would each
        // append their own copy.
        val packBytes = storage.listBlobs("p").toList().sumOf { it.length }
        assertThat(packBytes).isLessThan(2 * data.size.toLong())
        repo.close()
    }

    /**
     * The same race, made **deterministic**.
     *
     * Both reviewers made the same point about the test above: 16 concurrent duplicate writes hit the
     * window in practice, but nothing *forces* every worker past the dedup check before one of them
     * appends — on a single-core runner the coroutines can serialize and the test would pass even
     * with the re-check deleted. Evidence about one machine is not evidence about all of them.
     *
     * So this one holds every worker inside `encrypt()` — which is exactly the unlocked window the
     * change introduces — until all of them have arrived. Every worker has therefore provably passed
     * the dedup check before any of them can reach the append, which is the interleaving the re-check
     * exists for, on every machine.
     */
    @Test
    fun `every worker provably passes the dedup check before any of them appends`(): Unit = runBlocking {
        val workers = 4
        val allInsideEncrypt = CountDownLatch(workers)
        val storage = InMemoryBlobStorage()
        val config = TestRepositoryFactory.createConfig()
        val repo = DirectRepositoryImpl.create(storage, "test-password", config)

        // Wrap the real encryptor so it blocks until every worker is inside it. Correctness of the
        // ciphertext is unchanged; only the timing is forced.
        val barrier = object : EncryptorFactory {
            override fun create(algorithm: EncryptionAlgorithm, masterKey: ByteArray): Encryptor {
                val real = DefaultEncryptorFactory().create(algorithm, masterKey)
                return object : Encryptor by real {
                    override suspend fun encrypt(plaintext: ByteArray, contentId: ContentId): ByteArray {
                        allInsideEncrypt.countDown()
                        // Every worker waits here until the last one arrives. If any of them had been
                        // stopped by the dedup check, this would time out -- which is the point.
                        allInsideEncrypt.await(10, TimeUnit.SECONDS)
                        return real.encrypt(plaintext, contentId)
                    }
                }
            }
        }

        val manager = ContentManager(
            storage = storage,
            hasherFactory = DefaultContentHasherFactory(),
            hashAlgorithm = HashAlgorithm.fromId(config.hash)!!,
            hashSecret = config.secret,
            encryptorFactory = barrier,
            encryptionAlgorithm = EncryptionAlgorithm.fromId(config.encryption)!!,
            encryptionKey = config.masterKey,
            compressorFactory = DefaultCompressorFactory(),
        )

        val data = randomBytes(64 * 1024)
        val ids = runBlocking(Dispatchers.Default) {
            (1..workers).map { async { manager.writeContent(data) } }.awaitAll()
        }

        // All four raced through the window; exactly one may have appended.
        assertThat(ids.distinct()).hasSize(1)
        manager.flush()
        val packBytes = storage.listBlobs("p").toList().sumOf { it.length }
        assertThat(packBytes).isLessThan(2 * data.size.toLong())
        repo.close()
    }

    /**
     * The interleaving review flagged as verified by reasoning alone: a **delete racing a write** of
     * the same content. It is the subtlest path the change touches — step 4 reads the tombstone it
     * must supersede (`currentInfoUnlocked`) under the SAME lock acquisition that appends, so a
     * tombstone appearing during the unlocked encrypt window cannot be missed.
     *
     * **What this test does and does not pin, measured rather than assumed.** It asserts the
     * user-visible invariant — a write racing a delete leaves the content readable — and it drives
     * the concurrent path that the narrowed lock created. It does **not** pin the
     * `contentWriteTime` tie-break itself: mutating that to a plain `now` leaves this test green,
     * because the final write lands after every delete and wins on ordering alone. The tie-break
     * matters only against a tombstone whose timestamp is not strictly older — a same-second or
     * clock-skewed entry from another client — and that is covered directly, without concurrency,
     * by [org.kopiaKt.core.content.ContentManagerSoftDeleteTest].
     *
     * Saying so because a test whose comment claims more than it checks is how the next person comes
     * to trust a guard nothing is actually holding.
     */
    @Test
    fun `content deleted while it is being rewritten ends up live, not deleted`(): Unit = runBlocking {
        val (repo, _) = TestRepositoryFactory.createInMemory()
        val data = randomBytes(32 * 1024)

        val writer = repo.newDirectWriter(WriteSessionOptions(purpose = "resurrect"))
        val objectId = writer.writeObject(data)
        val contentId = ContentId.parse(objectId.toString().removePrefix("k"))
        writer.flush()

        // Delete and rewrite the same content against each other, repeatedly, so the window is hit
        // from both sides.
        repeat(8) {
            runBlocking(Dispatchers.Default) {
                listOf(
                    async { writer.deleteContent(contentId) },
                    async { writer.writeObject(data) },
                ).awaitAll()
            }
        }
        // A write after a delete must win: write-after-delete means the content exists.
        writer.writeObject(data)
        writer.flush()
        repo.refresh()

        assertThat(repo.contentInfo(contentId)?.deleted).isFalse()
        assertThat(repo.readObject(objectId)).isEqualTo(data)
        repo.close()
    }

    /** Distinct content written concurrently: every chunk must survive, none lost to the race. */
    @Test
    fun `distinct content written concurrently all survives`(): Unit = runBlocking {
        val (repo, _) = TestRepositoryFactory.createInMemory()
        val payloads = List(32) { randomBytes(64 * 1024) }

        val writer = repo.newDirectWriter(WriteSessionOptions(purpose = "parallel"))
        val ids = runBlocking(Dispatchers.Default) {
            payloads.map { async { writer.writeObject(it) } }.awaitAll()
        }
        writer.flush()
        repo.refresh()

        assertThat(ids.distinct()).hasSize(payloads.size)
        payloads.forEachIndexed { i, expected ->
            assertThat(repo.readObject(ids[i])).isEqualTo(expected)
        }
        repo.close()
    }
}
