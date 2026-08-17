@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package org.kopiaKt.storage.filesystem

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.blob.RepositoryUnavailableException
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.content.ContentManager
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

/**
 * The end-to-end shape of task-69, across the two halves that fix it.
 *
 * A backup whose destination has gone still runs retention — `BackupSession` calls it from a
 * `finally` precisely so a failed or cancelled run's incomplete manifests get reaped — and retention
 * opens with `repository.refresh()`. So the failed path reads the repository, and what that read
 * answers decides what the user is then shown.
 *
 * It used to answer "empty", twice over: `listBlobs` reported a vanished root as a repository with no
 * blobs, and `ContentManager` had already cleared its committed view before loading, so there was
 * nothing left to fall back to. Every source showed zero snapshots until the app reconnected — for a
 * backup tool, indistinguishable from having lost everything.
 *
 * Neither half is sufficient alone, which is why this test is not in either module's own suite:
 * a failing `listBlobs` with the old destructive load still empties the view, and a non-destructive
 * load still gets emptied by a `listBlobs` that answers "no blobs".
 */
@DisplayName("A vanished repository root does not empty the snapshot view")
class MissingRootKeepsSnapshotViewTest {

    @TempDir
    lateinit var tempDir: Path

    private fun randomKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `content stays visible after a refresh against a destination that has gone`(): Unit = runTest {
        val root = tempDir.resolve("repo").also { it.createDirectories() }
        val storage = FilesystemBlobStorage.create(root)
        val manager = ContentManager(
            storage = storage,
            hasherFactory = DefaultContentHasherFactory(),
            hashAlgorithm = HashAlgorithm.BLAKE2B_256_128,
            hashSecret = randomKey(),
            encryptorFactory = DefaultEncryptorFactory(),
            encryptionAlgorithm = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256,
            encryptionKey = randomKey(),
            compressorFactory = DefaultCompressorFactory(),
        )

        val data = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        val contentId = manager.writeContent(data)
        manager.flush()
        manager.refresh()
        assertNotNull(manager.getContentInfo(contentId), "precondition: the content is committed")

        // The destination goes away: an unmounted card, a folder a sync client moved.
        root.deleteRecursively()

        // This is the refresh retention performs on the failed path.
        assertThrows<RepositoryUnavailableException> { manager.refresh() }

        assertNotNull(
            manager.getContentInfo(contentId),
            "a destination that could not be read must leave the previous view alone — reporting it " +
                "as empty is what showed the user zero snapshots",
        )
    }
}
