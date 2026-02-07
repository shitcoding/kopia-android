package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.DirectRepositoryWriter
import org.kopiaKt.core.`object`.ObjectWriterOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import java.time.Instant

/**
 * Integration tests for the full maintenance cycle exercising [MaintenanceRunner].
 *
 * These tests verify that running maintenance (retention + GC + index compaction) on a
 * repository with real objects and snapshot manifests preserves data integrity, is
 * idempotent, and does not redundantly process blobs across consecutive runs.
 *
 * Note: [SnapshotGC.iterateContentByPrefix] is currently a placeholder, so GC Phase 2
 * (deletion of unreferenced content) always reports zero deletions. Tests focus on
 * verifying that the maintenance pipeline executes without errors and preserves all
 * referenced content.
 */
@DisplayName("Full Maintenance Cycle")
class FullMaintenanceCycleTest {

    private var repo: DirectRepositoryImpl? = null

    @AfterEach
    fun tearDown() {
        repo?.close()
    }

    // -- Helpers (same pattern as MultiSnapshotGCTest) --

    private suspend fun DirectRepositoryWriter.writeFileObject(data: ByteArray): ObjectId {
        return writeObject(data)
    }

    private suspend fun DirectRepositoryWriter.writeDirManifestObject(
        dirManifest: DirManifest
    ): ObjectId {
        val json = DirManifest.json.encodeToString(DirManifest.serializer(), dirManifest)
        return writeObject(json.toByteArray(Charsets.UTF_8), ObjectWriterOptions(prefix = 'k'))
    }

    private suspend fun DirectRepositoryWriter.createSnapshot(
        id: String,
        source: SourceInfo,
        rootDirObjectId: ObjectId
    ) {
        val snapshot = SnapshotManifest(
            id = id,
            source = source,
            startTime = Instant.now(),
            endTime = Instant.now(),
            rootEntry = DirEntry(
                name = "",
                type = EntryType.DIRECTORY,
                permissions = 493, // 0o755
                fileSize = 0,
                modTime = Instant.now(),
                objectId = rootDirObjectId.toString()
            )
        )
        putManifest(
            ManifestLabels.forSnapshot(source),
            snapshot,
            SnapshotManifest.serializer()
        )
    }

    /**
     * Write several file objects, build a directory manifest referencing them,
     * create a snapshot, and return the mapping of label -> ObjectId so tests
     * can verify readability after maintenance.
     */
    private suspend fun populateRepository(
        repository: DirectRepositoryImpl,
        source: SourceInfo,
        snapshotId: String,
        fileContents: Map<String, ByteArray>
    ): Map<String, ObjectId> {
        val writer = repository.newDirectWriter()

        val objectIds = mutableMapOf<String, ObjectId>()
        val dirEntries = mutableListOf<DirEntry>()

        for ((name, data) in fileContents) {
            val oid = writer.writeFileObject(data)
            objectIds[name] = oid
            dirEntries.add(
                DirEntry(
                    name = name,
                    type = EntryType.FILE,
                    permissions = 420, // 0o644
                    fileSize = data.size.toLong(),
                    modTime = Instant.now(),
                    objectId = oid.toString()
                )
            )
        }

        val dirManifest = DirManifest(entries = dirEntries)
        val dirOid = writer.writeDirManifestObject(dirManifest)
        objectIds["__dir__"] = dirOid

        writer.flush()
        repository.refresh()

        writer.createSnapshot(snapshotId, source, dirOid)
        writer.flush()
        repository.refresh()
        writer.close()

        return objectIds
    }

    // -----------------------------------------------------------------------
    // Test groups
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Repository validity after full maintenance")
    inner class RepositoryValidityAfterMaintenance {

        @Test
        fun `should leave repository valid after full maintenance cycle`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val source = SourceInfo("host", "user", "/data")
            val files = mapOf(
                "readme.txt" to "This is the readme".toByteArray(),
                "data.bin" to ByteArray(1024) { it.toByte() },
                "config.json" to """{"key":"value"}""".toByteArray()
            )

            val objectIds = populateRepository(repository, source, "snap-full-1", files)

            // Run full maintenance
            val runner = MaintenanceRunner(repository)
            val result = runner.run(
                MaintenanceOptions(
                    mode = MaintenanceMode.FULL,
                    force = true,
                    safety = SafetyParameters.Default
                )
            )

            assertThat(result.mode).isEqualTo(MaintenanceMode.FULL)
            assertThat(result.success).isTrue()
            assertThat(result.error).isNull()
            assertThat(result.duration).isNotNull()
            assertThat(result.startTime).isAtMost(result.endTime)

            // Refresh to see any changes made by maintenance
            repository.refresh()

            // Verify every object is still readable and matches the original data
            for ((name, data) in files) {
                val oid = objectIds.getValue(name)
                val readBack = repository.readObject(oid)
                assertThat(readBack).isEqualTo(data)
            }

            // Verify directory manifest is intact
            val dirOid = objectIds.getValue("__dir__")
            val dirBytes = repository.readObject(dirOid)
            val parsedDir = DirManifest.fromJson(dirBytes.decodeToString())
            assertThat(parsedDir.entries).hasSize(files.size)
            assertThat(parsedDir.entries.map { it.name }).containsExactlyElementsIn(files.keys)
        }

        @Test
        fun `should leave repository valid after quick maintenance`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val source = SourceInfo("host", "user", "/quick")
            val files = mapOf(
                "file1.txt" to "quick-test-content".toByteArray()
            )

            val objectIds = populateRepository(repository, source, "snap-quick-1", files)

            val runner = MaintenanceRunner(repository)
            val result = runner.run(
                MaintenanceOptions(
                    mode = MaintenanceMode.QUICK,
                    force = true
                )
            )

            assertThat(result.mode).isEqualTo(MaintenanceMode.QUICK)
            assertThat(result.success).isTrue()

            repository.refresh()

            val oid = objectIds.getValue("file1.txt")
            val readBack = repository.readObject(oid)
            assertThat(readBack).isEqualTo("quick-test-content".toByteArray())
        }

        @Test
        fun `should preserve multiple snapshots after full maintenance`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val source1 = SourceInfo("host1", "user1", "/path1")
            val source2 = SourceInfo("host2", "user2", "/path2")

            val files1 = mapOf(
                "alpha.txt" to "alpha-content".toByteArray(),
                "beta.txt" to "beta-content".toByteArray()
            )
            val files2 = mapOf(
                "gamma.txt" to "gamma-content".toByteArray(),
                "delta.txt" to "delta-content".toByteArray()
            )

            val oids1 = populateRepository(repository, source1, "snap-multi-1", files1)
            val oids2 = populateRepository(repository, source2, "snap-multi-2", files2)

            val runner = MaintenanceRunner(repository)
            val result = runner.run(
                MaintenanceOptions(mode = MaintenanceMode.FULL, force = true)
            )

            assertThat(result.success).isTrue()
            repository.refresh()

            // Verify all objects from snapshot 1
            for ((name, data) in files1) {
                assertThat(repository.readObject(oids1.getValue(name))).isEqualTo(data)
            }
            // Verify all objects from snapshot 2
            for ((name, data) in files2) {
                assertThat(repository.readObject(oids2.getValue(name))).isEqualTo(data)
            }
        }
    }

    @Nested
    @DisplayName("Idempotency")
    inner class Idempotency {

        @Test
        fun `should be idempotent when run twice`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val source = SourceInfo("host", "user", "/idem")
            val files = mapOf(
                "stable.txt" to "this content must survive two runs".toByteArray(),
                "binary.dat" to ByteArray(512) { (it * 7).toByte() }
            )

            val objectIds = populateRepository(repository, source, "snap-idem-1", files)

            val options = MaintenanceOptions(mode = MaintenanceMode.FULL, force = true)

            // First run
            val runner1 = MaintenanceRunner(repository)
            val result1 = runner1.run(options)
            assertThat(result1.success).isTrue()
            repository.refresh()

            // Second run
            val runner2 = MaintenanceRunner(repository)
            val result2 = runner2.run(options)
            assertThat(result2.success).isTrue()
            repository.refresh()

            // Both runs should report the same mode
            assertThat(result1.mode).isEqualTo(result2.mode)

            // All objects must still be readable after two consecutive runs
            for ((name, data) in files) {
                val oid = objectIds.getValue(name)
                val readBack = repository.readObject(oid)
                assertThat(readBack).isEqualTo(data)
            }

            // Directory manifest must still be intact
            val dirOid = objectIds.getValue("__dir__")
            val dirBytes = repository.readObject(dirOid)
            val parsedDir = DirManifest.fromJson(dirBytes.decodeToString())
            assertThat(parsedDir.entries).hasSize(files.size)
        }

        @Test
        fun `should produce consistent GC stats across two runs`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val source = SourceInfo("host", "user", "/gc-stats")
            val files = mapOf(
                "data.txt" to "gc-stats-test-content".toByteArray()
            )

            populateRepository(repository, source, "snap-gc-stats", files)

            val options = MaintenanceOptions(mode = MaintenanceMode.FULL, force = true)

            val result1 = MaintenanceRunner(repository).run(options)
            repository.refresh()

            val result2 = MaintenanceRunner(repository).run(options)
            repository.refresh()

            // GC stats should be consistent (same or fewer unreferenced on second run)
            assertThat(result1.gcStats).isNotNull()
            assertThat(result2.gcStats).isNotNull()

            // Since iterateContentByPrefix is a stub, both runs report zero deletions.
            // When the stub is replaced, the second run should report no more deletions
            // than the first, since nothing new became unreferenced between runs.
            assertThat(result2.gcStats!!.deletedContentCount)
                .isAtMost(result1.gcStats!!.deletedContentCount)
        }
    }

    @Nested
    @DisplayName("No duplicate blob processing")
    inner class NoDuplicateBlobProcessing {

        @Test
        fun `should not process same blob multiple times`() = runTest {
            // Regression: Go issue #3059 - blobs should not be processed more than once
            val (repository, storage) = TestRepositoryFactory.createInMemory()
            repo = repository

            val source = SourceInfo("host", "user", "/dedup")
            val files = mapOf(
                "file1.txt" to "dedup-content-one".toByteArray(),
                "file2.txt" to "dedup-content-two".toByteArray(),
                "file3.txt" to "dedup-content-three".toByteArray()
            )

            populateRepository(repository, source, "snap-dedup-1", files)

            // Record blob state before first maintenance run
            val blobCountBefore = storage.size()

            // Track progress messages to detect duplicate work
            val progressMessages1 = mutableListOf<String>()
            val options1 = MaintenanceOptions(
                mode = MaintenanceMode.FULL,
                force = true,
                onProgress = { progressMessages1.add(it) }
            )

            val result1 = MaintenanceRunner(repository).run(options1)
            assertThat(result1.success).isTrue()
            repository.refresh()

            val blobCountAfterFirst = storage.size()

            // Second run - should not redundantly process the same blobs
            val progressMessages2 = mutableListOf<String>()
            val options2 = MaintenanceOptions(
                mode = MaintenanceMode.FULL,
                force = true,
                onProgress = { progressMessages2.add(it) }
            )

            val result2 = MaintenanceRunner(repository).run(options2)
            assertThat(result2.success).isTrue()
            repository.refresh()

            val blobCountAfterSecond = storage.size()

            // The second run should not create significantly more blobs than the first.
            // A small increase is acceptable (e.g., manifest updates), but a large increase
            // would indicate duplicate processing.
            val blobGrowthFromFirstRun = blobCountAfterFirst - blobCountBefore
            val blobGrowthFromSecondRun = blobCountAfterSecond - blobCountAfterFirst

            // Second run blob growth should not exceed first run growth.
            // In a well-behaved implementation, the second run should create fewer or
            // equal new blobs compared to the first run.
            assertThat(blobGrowthFromSecondRun).isAtMost(blobGrowthFromFirstRun)

            // Both runs should go through the same maintenance phases
            assertThat(progressMessages1).isNotEmpty()
            assertThat(progressMessages2).isNotEmpty()
            // Both should start with the same phase
            assertThat(progressMessages2.first()).isEqualTo(progressMessages1.first())
        }

        @Test
        fun `should not duplicate work when objects are shared across snapshots`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            // Write shared content that appears in both snapshots
            val sharedData = "shared-content-across-snapshots".toByteArray()

            val writer = repository.newDirectWriter()
            val sharedOid = writer.writeFileObject(sharedData)

            // Snapshot 1: uses shared content + unique content
            val uniqueData1 = "unique-to-snapshot-1".toByteArray()
            val uniqueOid1 = writer.writeFileObject(uniqueData1)

            val dir1 = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "shared.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = sharedData.size.toLong(),
                        modTime = Instant.now(),
                        objectId = sharedOid.toString()
                    ),
                    DirEntry(
                        name = "unique1.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = uniqueData1.size.toLong(),
                        modTime = Instant.now(),
                        objectId = uniqueOid1.toString()
                    )
                )
            )
            val dirOid1 = writer.writeDirManifestObject(dir1)

            // Snapshot 2: uses same shared content + different unique content
            val uniqueData2 = "unique-to-snapshot-2".toByteArray()
            val uniqueOid2 = writer.writeFileObject(uniqueData2)

            val dir2 = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "shared.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = sharedData.size.toLong(),
                        modTime = Instant.now(),
                        objectId = sharedOid.toString()
                    ),
                    DirEntry(
                        name = "unique2.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = uniqueData2.size.toLong(),
                        modTime = Instant.now(),
                        objectId = uniqueOid2.toString()
                    )
                )
            )
            val dirOid2 = writer.writeDirManifestObject(dir2)

            writer.flush()
            repository.refresh()

            val source1 = SourceInfo("host", "user", "/path1")
            val source2 = SourceInfo("host", "user", "/path2")
            writer.createSnapshot("snap-shared-1", source1, dirOid1)
            writer.createSnapshot("snap-shared-2", source2, dirOid2)
            writer.flush()
            repository.refresh()
            writer.close()

            // Run maintenance
            val result = MaintenanceRunner(repository).run(
                MaintenanceOptions(mode = MaintenanceMode.FULL, force = true)
            )
            assertThat(result.success).isTrue()
            repository.refresh()

            // All objects must remain readable -- shared content is not double-deleted
            assertThat(repository.readObject(sharedOid)).isEqualTo(sharedData)
            assertThat(repository.readObject(uniqueOid1)).isEqualTo(uniqueData1)
            assertThat(repository.readObject(uniqueOid2)).isEqualTo(uniqueData2)

            // Run maintenance again -- should still preserve everything
            val result2 = MaintenanceRunner(repository).run(
                MaintenanceOptions(mode = MaintenanceMode.FULL, force = true)
            )
            assertThat(result2.success).isTrue()
            repository.refresh()

            assertThat(repository.readObject(sharedOid)).isEqualTo(sharedData)
            assertThat(repository.readObject(uniqueOid1)).isEqualTo(uniqueData1)
            assertThat(repository.readObject(uniqueOid2)).isEqualTo(uniqueData2)
        }
    }
}
