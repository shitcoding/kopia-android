package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.policy.RetentionPolicy
import org.kopiaKt.snapshot.testutil.MockDirectory
import org.kopiaKt.snapshot.testutil.MockFile
import java.time.Instant

/**
 * Go checks `RetentionPolicy.IgnoreIdenticalSnapshots` before saving and skips the manifest entirely
 * when the root object id matches the previous snapshot's
 * (`cli/command_snapshot_create.go`). Kotlin modelled the policy field and then always wrote the
 * manifest anyway, so a user who turned it on got a new snapshot every run regardless — and on a
 * phone backing up a photo folder that changes once a week, that is six snapshots of nothing.
 */
class IgnoreIdenticalSnapshotsTest {

    private val source = SourceInfo(host = "phone", userName = "local", path = "/sdcard/DCIM")

    private val ignoreIdentical = Policy(
        retentionPolicy = RetentionPolicy(ignoreIdenticalSnapshots = true),
    )

    private fun tree(content: String, modTime: Instant = Instant.parse("2026-01-01T00:00:00Z")) = MockDirectory(
        name = "DCIM",
        entries = listOf(MockFile("a.txt", content.toByteArray(), modTime = modTime)),
    )

    @Test
    fun `an unchanged tree writes no second snapshot`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val unchanged = tree("hello")

        val first = upload(repository, unchanged, ignoreIdentical)
        val second = upload(repository, unchanged, ignoreIdentical)

        assertThat(snapshots(repository)).hasSize(1)
        // The caller is told the run succeeded and pointed at the snapshot that still describes the
        // source — reporting a failure, or a manifest id that does not exist, would both be lies.
        assertThat(second.identicalToPrevious).isTrue()
        assertThat(second.manifestId).isEqualTo(first.manifestId)
        repository.close()
    }

    @Test
    fun `the default writes every snapshot`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val unchanged = tree("hello")

        // Go defaults this FALSE, and a backup tool that silently stopped recording runs would be
        // the worse failure of the two.
        upload(repository, unchanged, Policy())
        val second = upload(repository, unchanged, Policy())

        assertThat(snapshots(repository)).hasSize(2)
        assertThat(second.identicalToPrevious).isFalse()
        repository.close()
    }

    @Test
    fun `a changed tree still writes`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()

        upload(repository, tree("hello"), ignoreIdentical)
        val second = upload(
            repository,
            tree("goodbye", modTime = Instant.parse("2026-02-02T00:00:00Z")),
            ignoreIdentical,
        )

        assertThat(snapshots(repository)).hasSize(2)
        assertThat(second.identicalToPrevious).isFalse()
        repository.close()
    }

    @Test
    fun `an interrupted run's tree never stands in for a complete snapshot`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val unchanged = tree("hello")

        // Only an INCOMPLETE snapshot exists, and it happens to name the same tree — which is
        // exactly what an interrupted run that got all the way to the end leaves behind.
        val interrupted = uploadCancelled(repository, unchanged)
        assertThat(interrupted.incomplete).isTrue()

        val complete = upload(repository, unchanged, ignoreIdentical)

        // The outcome, by whatever route: a run following an interrupted one ends with a complete
        // snapshot on record. This particular fixture cannot collide (its cancel lands before the
        // walk, so the root manifest IS stamped) — the collision that can actually happen is the
        // subject of the next test.
        assertThat(complete.identicalToPrevious).isFalse()
        assertThat(snapshots(repository).filter { it.incompleteReason == null }).hasSize(1)
        repository.close()
    }

    /**
     * The collision that is genuinely reachable, and the reason this comparison ignores incomplete
     * manifests at all.
     *
     * `TreeWalker` reads `incompleteReason()` once when it builds the root manifest and
     * `SnapshotUploader` reads it again after the walk returns, while `cancel()` arrives from
     * another thread. A cancel landing between those two reads — a window spanning the root
     * manifest's upload — saves a manifest marked "canceled" whose root is byte-identical to a
     * complete run's. Go has the same two-read shape, so one can arrive from a desktop too.
     *
     * Racing that window in a test would be a coin flip, so the state it produces is constructed
     * directly. Without the complete-only filter this run would skip, and a source whose first
     * backup was cancelled at the wrong instant would never get a complete snapshot while every
     * later run reported success.
     */
    @Test
    fun `an incomplete manifest naming a complete tree is not mistaken for one`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val unchanged = tree("hello")

        val first = upload(repository, unchanged, ignoreIdentical)
        val completeRoot = first.manifest.rootEntry
        assertThat(completeRoot).isNotNull()

        // Leave ONLY an incomplete manifest behind, pointing at that complete tree.
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            writer.deleteManifest(first.manifestId)
            writer.putManifest(
                ManifestLabels.forSnapshot(source),
                first.manifest.copy(
                    id = ManifestId.generate().value,
                    incompleteReason = "canceled",
                    rootEntry = completeRoot,
                ),
                SnapshotManifest.serializer(),
            )
            writer.flush()
        } finally {
            writer.close()
        }
        repository.refresh()

        val second = upload(repository, unchanged, ignoreIdentical)

        assertThat(second.identicalToPrevious).isFalse()
        assertThat(snapshots(repository).filter { it.incompleteReason == null }).hasSize(1)
        repository.close()
    }

    private suspend fun uploadCancelled(
        repository: DirectRepository,
        root: MockDirectory,
    ): UploadResult {
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            val uploader = SnapshotUploader(writer = writer, source = source, policy = ignoreIdentical)
            uploader.cancel()
            return uploader.upload(root, UploadOptions(parallelUploads = 1))
        } finally {
            writer.close()
        }
    }

    private suspend fun upload(
        repository: DirectRepository,
        root: MockDirectory,
        policy: Policy,
    ): UploadResult {
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            return SnapshotUploader(writer = writer, source = source, policy = policy)
                .upload(root, UploadOptions(parallelUploads = 1))
        } finally {
            writer.close()
        }
    }

    private suspend fun snapshots(repository: DirectRepository): List<SnapshotManifest> {
        repository.refresh()
        return repository.findManifests(ManifestLabels.forSnapshot(source)).map {
            repository.getManifest(it.id, SnapshotManifest.serializer()).first
        }
    }
}
