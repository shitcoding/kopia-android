package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.fs.DirectoryIterator
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.FilesPolicy
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.testutil.MockDirectory
import org.kopiaKt.snapshot.testutil.MockFile
import org.kopiaKt.snapshot.testutil.SlowMockFile
import java.io.IOException

/**
 * Phase 4.3 — a backup has to say how big it is, or every progress bar it drives is a spinner.
 *
 * `estimatedDataSize` was declared, overridden and stored, and had **zero production callers**.
 * `computeProgressPercent` reads `counters.estimatedBytes`, and the Tasks screen and the progress
 * sheet compute their fraction from Processed / Estimated, so all three were permanently
 * indeterminate: a multi-hour first backup showed a spinner and nothing else.
 */
class UploadEstimationTest {

    private val source = SourceInfo(host = "phone", userName = "local", path = "/sdcard/DCIM")

    @Test
    fun `a running backup reports how much there is to do`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        // Slow enough that the metadata-only estimate certainly finishes first, as it does in
        // practice — the estimator never opens a file.
        val tree = MockDirectory(
            name = "DCIM",
            entries = (1..4).map { i -> SlowMockFile("f$i.bin", ByteArray(1000), delayMs = 80) },
        )

        val progress = CountingUploadProgress()
        upload(repository, tree, progress)

        assertThat(progress.snapshot().estimatedBytes).isEqualTo(4000)
        assertThat(progress.snapshot().estimatedFiles).isEqualTo(4)
        repository.close()
    }

    @Test
    fun `the estimate counts a nested tree, not just the root`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val tree = MockDirectory(
            name = "DCIM",
            entries = listOf(
                SlowMockFile("top.bin", ByteArray(1000), delayMs = 80),
                MockDirectory("sub", listOf(MockFile("nested.bin", ByteArray(500)))),
            ),
        )

        val progress = CountingUploadProgress()
        upload(repository, tree, progress)

        assertThat(progress.snapshot().estimatedBytes).isEqualTo(1500)
        assertThat(progress.snapshot().estimatedFiles).isEqualTo(2)
        repository.close()
    }

    @Test
    fun `the estimate describes the backup that is actually running`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val tree = MockDirectory(
            name = "DCIM",
            entries = listOf(
                SlowMockFile("keep.bin", ByteArray(1000), delayMs = 80),
                MockFile("skip.tmp", ByteArray(9999)),
            ),
        )

        // Estimating under a different policy than the run uses is worse than not estimating: the
        // bar would be confidently wrong, and it is wrong in the direction that makes a finished
        // backup look stuck at 10%.
        val policy = Policy(filesPolicy = FilesPolicy(ignoreRules = listOf("*.tmp")))
        val progress = CountingUploadProgress()
        val result = upload(repository, tree, progress, policy)

        assertThat(progress.snapshot().estimatedBytes).isEqualTo(1000)
        assertThat(result.stats.totalFileSize).isEqualTo(1000)
        repository.close()
    }

    @Test
    fun `a subtree that cannot be listed costs its own files, not the whole estimate`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val tree = MockDirectory(
            name = "DCIM",
            entries = listOf(
                SlowMockFile("keep.bin", ByteArray(1000), delayMs = 80),
                UnreadableDirectory("locked"),
            ),
        )

        val progress = CountingUploadProgress()
        upload(repository, tree, progress)

        // One lapsed SAF grant anywhere in a photo library used to throw the entire count away —
        // and the backups that lost it were exactly the long ones a determinate bar is for.
        assertThat(progress.snapshot().estimatedBytes).isEqualTo(1000)
        repository.close()
    }

    @Test
    fun `nothing is estimated when nobody is listening`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        var iterations = 0
        val tree = object : MockDirectory("DCIM", listOf(MockFile("f.bin", ByteArray(10)))) {
            override suspend fun iterate(): DirectoryIterator {
                iterations++
                return super.iterate()
            }
        }

        // Go skips estimation when the progress reporter is disabled. Walking the tree a second
        // time to tell nobody anything is pure cost, and on a phone over SAF it is not small.
        upload(repository, tree, NullUploadProgress())

        assertThat(iterations).isEqualTo(1)
        repository.close()
    }

    /** A directory whose contents cannot be listed at all — a lapsed SAF grant, in miniature. */
    private class UnreadableDirectory(name: String) : MockDirectory(name, emptyList()) {
        override suspend fun iterate(): DirectoryIterator = throw IOException("permission denied")
    }

    private suspend fun upload(
        repository: org.kopiaKt.core.repository.DirectRepository,
        tree: MockDirectory,
        progress: UploadProgress,
        policy: Policy = Policy(),
    ): UploadResult {
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            return SnapshotUploader(writer = writer, source = source, policy = policy, progress = progress)
                .upload(tree, UploadOptions(parallelUploads = 1))
        } finally {
            writer.close()
        }
    }
}
