package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.FilesPolicy
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.testutil.MockDirectory
import org.kopiaKt.snapshot.testutil.MockFile
import java.util.concurrent.atomic.AtomicLong

/**
 * task-37 — three `UploadProgress` callbacks were declared, overridden, stored, and called by
 * nothing in production. The Tasks screen displays every one of them (task-30.18), so "Uploaded
 * Bytes", "Excluded Files" and "Excluded Directories" read a confident 0 on every backup that ever
 * ran, whatever it actually did.
 */
class UnreportedCountersTest {

    private val source = SourceInfo(host = "phone", userName = "local", path = "/sdcard/DCIM")

    @Test
    fun `bytes written to storage are reported`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val uploaded = AtomicLong(0)
        val tree = MockDirectory("DCIM", listOf(MockFile("a.bin", ByteArray(64 * 1024) { it.toByte() })))

        val writer = repository.newWriter(WriteSessionOptions(onUpload = { uploaded.addAndGet(it) }))
        try {
            SnapshotUploader(writer = writer, source = source).upload(tree, UploadOptions(parallelUploads = 1))
        } finally {
            writer.close()
        }

        // What went to storage, not what was read: on a metered connection that is the number that
        // matters, and it is the one Go reports (`onUpload`, from the content manager's blob writes).
        assertThat(uploaded.get()).isGreaterThan(0L)
        repository.close()
    }

    @Test
    fun `a run that uploads nothing new reports no uploaded bytes`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val tree = MockDirectory("DCIM", listOf(MockFile("a.bin", ByteArray(64 * 1024) { it.toByte() })))

        // First run puts everything in the repository.
        uploadWith(repository, tree, Policy(), CountingUploadProgress())
        val second = AtomicLong(0)
        val writer = repository.newWriter(WriteSessionOptions(onUpload = { second.addAndGet(it) }))
        try {
            SnapshotUploader(writer = writer, source = source).upload(tree, UploadOptions(parallelUploads = 1))
        } finally {
            writer.close()
        }

        // The second run re-reads nothing and re-uploads nothing but its own small manifests, so
        // "Uploaded Bytes" must be far below the file it did not send. A counter that reported
        // hashed or cached bytes here would make an incremental backup look like a full one.
        assertThat(second.get()).isLessThan(64L * 1024)
        repository.close()
    }

    @Test
    fun `what is reported is exactly what reached storage`(): Unit = runBlocking {
        val (repository, storage) = TestRepositoryFactory.createInMemory()
        val uploaded = AtomicLong(0)
        val tree = MockDirectory(
            name = "DCIM",
            entries = (1..3).map { i -> MockFile("f$i.bin", ByteArray(32 * 1024) { (it * i).toByte() }) },
        )

        val before = storage.listBlobs("").toList().sumOf { it.length }
        val writer = repository.newWriter(WriteSessionOptions(onUpload = { uploaded.addAndGet(it) }))
        try {
            SnapshotUploader(writer = writer, source = source).upload(tree, UploadOptions(parallelUploads = 1))
        } finally {
            writer.close()
        }
        val after = storage.listBlobs("").toList().sumOf { it.length }

        // The whole point of the counter, stated as an equation: every byte the repository grew by
        // was reported, and nothing was reported twice. This is the assertion that catches a future
        // blob write that forgets to report, and a retry or a second hook that reports the same
        // bytes again — neither of which any per-callback test would notice.
        assertThat(uploaded.get()).isEqualTo(after - before)
        repository.close()
    }

    @Test
    fun `a file skipped for being too large counts as excluded`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val tree = MockDirectory(
            name = "DCIM",
            entries = listOf(MockFile("small.jpg", ByteArray(10)), MockFile("huge.mov", ByteArray(5000))),
        )

        // Deliberately MORE than Go reports: Go fires its ignore callback only for name-pattern
        // matches, so a file dropped for its size is silently absent from its counters. "Excluded
        // Files: 0" next to a missing video is the kind of quiet that makes a backup tool
        // untrustworthy.
        val progress = CountingUploadProgress()
        val result = uploadWith(repository, tree, Policy(filesPolicy = FilesPolicy(maxFileSize = 100)), progress)

        assertThat(progress.snapshot().totalExcludedFiles).isEqualTo(1)
        assertThat(result.stats.totalFileCount).isEqualTo(1)
        repository.close()
    }

    @Test
    fun `excluded files and directories are counted`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val tree = MockDirectory(
            name = "DCIM",
            entries = listOf(
                MockFile("keep.jpg", ByteArray(10)),
                MockFile("skip.tmp", ByteArray(999)),
                MockDirectory("cache", listOf(MockFile("junk.bin", ByteArray(10)))),
            ),
        )
        val policy = Policy(filesPolicy = FilesPolicy(ignoreRules = listOf("*.tmp", "cache")))

        val progress = CountingUploadProgress()
        val result = uploadWith(repository, tree, policy, progress)

        assertThat(progress.snapshot().totalExcludedFiles).isEqualTo(1)
        assertThat(progress.snapshot().totalExcludedDirs).isEqualTo(1)
        // And the excluded entries really were excluded, not merely counted.
        assertThat(result.stats.totalFileCount).isEqualTo(1)
        repository.close()
    }

    @Test
    fun `the estimate running alongside does not double-count exclusions`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val tree = MockDirectory(
            name = "DCIM",
            entries = listOf(MockFile("keep.jpg", ByteArray(10)), MockFile("skip.tmp", ByteArray(999))),
        )
        val policy = Policy(filesPolicy = FilesPolicy(ignoreRules = listOf("*.tmp")))

        // The estimator walks the tree too (task-30.20). Go wraps the tree separately for it with
        // ignore reporting OFF for exactly this reason: sharing one reporting wrapper would count
        // every excluded entry twice and the Tasks screen would say a backup skipped twice what it did.
        val progress = CountingUploadProgress()
        uploadWith(repository, tree, policy, progress)

        assertThat(progress.snapshot().totalExcludedFiles).isEqualTo(1)
        repository.close()
    }

    private suspend fun uploadWith(
        repository: DirectRepository,
        tree: MockDirectory,
        policy: Policy,
        progress: UploadProgress,
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
