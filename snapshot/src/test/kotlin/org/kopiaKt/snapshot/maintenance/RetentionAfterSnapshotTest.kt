package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.policy.PolicyManager
import org.kopiaKt.snapshot.policy.RetentionPolicy
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Retention never ran after a backup, because the only way to reach it dragged a full GC mark walk
 * over the repository. So a phone that backs up nightly would keep every snapshot it ever made, and
 * every incomplete manifest left behind by a cancelled run, forever — while the user's policy said
 * otherwise. Go applies retention after every `snapshot create`.
 */
class RetentionAfterSnapshotTest {

    private val source = SourceInfo(host = "phone", userName = "local", path = "/sdcard/DCIM")

    @Test
    fun `a fourth backup leaves three snapshots when keepLatest is three`(@TempDir tempDir: Path) = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val sourceDir = tempDir.resolve("src").also { it.toFile().mkdirs() }
        PolicyManager.setPolicy(repository, source, Policy(retentionPolicy = RetentionPolicy(keepLatest = 3)))

        repeat(4) { run ->
            sourceDir.resolve("a.txt").writeText("run $run")
            upload(repository, sourceDir)
            MaintenanceRunner(repository).applyRetention(source)
        }

        assertThat(completeSnapshots(repository)).hasSize(3)
        repository.close()
    }

    @Test
    fun `incomplete manifests from cancelled runs are reaped once a backup completes`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val sourceDir = tempDir.resolve("src").also { it.toFile().mkdirs() }
        sourceDir.resolve("a.txt").writeText("data")
        PolicyManager.setPolicy(repository, source, Policy(retentionPolicy = RetentionPolicy(keepLatest = 3)))

        // Four cancelled runs, then a real one. Without retention these pile up forever and show in
        // the user's snapshot list as failures they cannot clear.
        repeat(4) { saveIncompleteManifest(repository, it.toLong()) }
        upload(repository, sourceDir)
        MaintenanceRunner(repository).applyRetention(source)

        val remaining = allSnapshots(repository)
        assertThat(remaining.filter { it.incompleteReason != null }).isEmpty()
        assertThat(remaining).hasSize(1)
        repository.close()
    }

    private suspend fun upload(repository: DirectRepository, sourceDir: Path) {
        val writer = repository.newWriter(WriteSessionOptions())
        SnapshotUploader(
            writer = writer,
            source = source,
            policy = Policy(),
            progress = CountingUploadProgress(),
        ).upload(LocalFilesystem.directory(sourceDir))
    }

    private suspend fun saveIncompleteManifest(repository: DirectRepository, index: Long) {
        val writer = repository.newWriter(WriteSessionOptions())
        val start = java.time.Instant.parse("2026-07-01T00:00:00Z").plusSeconds(index * 3600)
        writer.putManifest(
            ManifestLabels.forSnapshot(source),
            SnapshotManifest(
                id = org.kopiaKt.core.manifest.ManifestId.generate().value,
                source = source,
                startTime = start,
                endTime = start.plusSeconds(60),
                incompleteReason = "canceled",
            ),
            SnapshotManifest.serializer(),
        )
        writer.flush()
    }

    private suspend fun allSnapshots(repository: DirectRepository): List<SnapshotManifest> {
        val writer = repository.newWriter(WriteSessionOptions())
        return writer.findManifests(ManifestLabels.forSnapshot(source)).map {
            writer.getManifest(it.id, SnapshotManifest.serializer()).first
        }
    }

    private suspend fun completeSnapshots(repository: DirectRepository): List<SnapshotManifest> {
        val all = allSnapshots(repository)
        return all.filter { it.incompleteReason == null }
    }
}
