package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.testutil.MockDirectory
import org.kopiaKt.snapshot.testutil.SlowMockFile
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The poison case this fixes: a cancelled run saves an incomplete manifest whose `rootEntry` is
 * null, and picking simply the newest manifest handed that one back. Loading its root then returned
 * nothing, so the next backup re-hashed the entire tree — cancelling a backup made the retry as
 * expensive as the first run, exactly when a user least wants that.
 */
class PreviousSnapshotSelectionTest {

    private val source = SourceInfo(host = "phone", userName = "local", path = "/sdcard/DCIM")

    @Test
    fun `a newer incomplete snapshot does not hide the last complete one`(@TempDir tempDir: Path): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val sourceDir = tempDir.resolve("src").also { it.toFile().mkdirs() }
        sourceDir.resolve("a.txt").writeText("hello")

        val firstRun = upload(repository, sourceDir)
        assertThat(firstRun.manifest.rootEntry).isNotNull()

        // A cancelled run: a manifest that is newer than the complete one and carries no tree.
        saveIncompleteManifest(repository, firstRun.manifest.startTime)

        val secondRun = upload(repository, sourceDir)

        // Everything was already in the repository, so the second run must reuse it rather than
        // re-hash. A zero here means the incomplete manifest won the selection.
        assertThat(secondRun.stats.cachedFiles).isEqualTo(1)
        repository.close()
    }

    @Test
    fun `an interrupted run is resumed, not restarted`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val tree = MockDirectory(
            name = "DCIM",
            entries = (1..4).map { i -> SlowMockFile("f$i.bin", ByteArray(2048) { i.toByte() }, delayMs = 150) },
        )

        // Interrupt it: the drain (phase 3.1) writes a real partial tree and an incomplete manifest.
        val interrupted = uploadCancelledAfter(repository, tree, afterMillis = 250)
        assertThat(interrupted.incomplete).isTrue()
        assertThat(interrupted.manifest.rootEntry).isNotNull()

        val resumed = uploadTree(repository, tree)

        // The whole point of phase 3.2: there is NO complete snapshot to fall back on, so anything
        // reused here can only have come from the interrupted run's own partial tree. A zero means
        // the retry re-read and re-hashed every byte the first attempt had already uploaded.
        assertThat(resumed.stats.cachedFiles).isGreaterThan(0)
        repository.close()
    }

    private suspend fun uploadTree(
        repository: org.kopiaKt.core.repository.DirectRepository,
        tree: MockDirectory,
    ): UploadResult {
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            return SnapshotUploader(writer = writer, source = source, progress = CountingUploadProgress())
                .upload(tree, UploadOptions(parallelUploads = 1))
        } finally {
            writer.close()
        }
    }

    private suspend fun uploadCancelledAfter(
        repository: org.kopiaKt.core.repository.DirectRepository,
        tree: MockDirectory,
        afterMillis: Long,
    ): UploadResult = coroutineScope {
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            val uploader = SnapshotUploader(writer = writer, source = source, progress = CountingUploadProgress())
            val job = async { uploader.upload(tree, UploadOptions(parallelUploads = 1)) }
            delay(afterMillis)
            uploader.cancel()
            job.await()
        } finally {
            writer.close()
        }
    }

    private suspend fun upload(
        repository: org.kopiaKt.core.repository.DirectRepository,
        sourceDir: Path,
    ): UploadResult {
        val writer = repository.newWriter(WriteSessionOptions())
        val uploader = SnapshotUploader(
            writer = writer,
            source = source,
            policy = Policy(),
            progress = CountingUploadProgress(),
        )
        return uploader.upload(LocalFilesystem.directory(sourceDir))
    }

    private suspend fun saveIncompleteManifest(
        repository: org.kopiaKt.core.repository.DirectRepository,
        after: java.time.Instant,
    ) {
        val writer = repository.newWriter(WriteSessionOptions())
        val manifest = org.kopiaKt.snapshot.model.SnapshotManifest(
            id = org.kopiaKt.core.manifest.ManifestId.generate().value,
            source = source,
            startTime = after.plusSeconds(1),
            endTime = after.plusSeconds(2),
            incompleteReason = "canceled",
            rootEntry = null,
        )
        writer.putManifest(
            org.kopiaKt.snapshot.model.ManifestLabels.forSnapshot(source),
            manifest,
            org.kopiaKt.snapshot.model.SnapshotManifest.serializer(),
        )
        writer.flush()
    }
}
