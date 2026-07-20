package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.`object`.ObjectNotFoundException
import org.kopiaKt.core.`object`.ObjectWriterOptions
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.DirectRepositoryWriter
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import java.time.Duration
import java.time.Instant

/**
 * Integration tests for SnapshotGC operating on repositories with real snapshot manifests.
 *
 * These tests verify that GC Phase 1 (load snapshots, walk object trees, build in-use set)
 * works correctly and that content is preserved after a GC run.
 *
 * Note: SnapshotGC.iterateContentByPrefix() is currently a placeholder (empty method),
 * so Phase 2 (find unreferenced content, mark/delete) always returns zero for all stats
 * fields. Tests verify Phase 1 plumbing and content preservation rather than deletion stats.
 */
@DisplayName("Multi-Snapshot GC Integrity")
class MultiSnapshotGCTest {

    private var repo: DirectRepositoryImpl? = null

    @AfterEach
    fun tearDown() {
        repo?.close()
    }

    /**
     * Helper to write file data as a regular object (no prefix).
     */
    private suspend fun DirectRepositoryWriter.writeFileObject(data: ByteArray): ObjectId {
        return writeObject(data)
    }

    /**
     * Helper to write a DirManifest as an object with 'k' prefix,
     * which is how Kopia stores directory content.
     */
    private suspend fun DirectRepositoryWriter.writeDirManifestObject(
        dirManifest: DirManifest
    ): ObjectId {
        val json = DirManifest.json.encodeToString(DirManifest.serializer(), dirManifest)
        return writeObject(json.toByteArray(Charsets.UTF_8), ObjectWriterOptions(prefix = 'k'))
    }

    /**
     * Helper to write a DirManifest the way PRODUCTION did before task-9 prerequisite #1:
     * WITHOUT the 'k' prefix. isDirectoryId(objectId) is therefore false for these objects, so GC
     * traversal must recurse via DirEntry.type instead of the content prefix, or it under-collects
     * the in-use set. Deliberately distinct from writeDirManifestObject (which mimics prefixed dirs).
     */
    private suspend fun DirectRepositoryWriter.writeProductionDirManifestObject(
        dirManifest: DirManifest
    ): ObjectId {
        val json = DirManifest.json.encodeToString(DirManifest.serializer(), dirManifest)
        return writeObject(json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Helper to create and store a snapshot manifest pointing to a root directory object.
     */
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

    @Nested
    @DisplayName("Basic GC execution")
    inner class BasicGCExecution {

        @Test
        fun `should run GC without errors on repository with snapshots`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()

            // Write 3 file objects
            val fileData1 = "file-content-one".toByteArray()
            val fileData2 = "file-content-two".toByteArray()
            val fileData3 = "file-content-three".toByteArray()

            val fileOid1 = writer.writeFileObject(fileData1)
            val fileOid2 = writer.writeFileObject(fileData2)
            val fileOid3 = writer.writeFileObject(fileData3)

            // Create a DirManifest referencing all three files
            val dirManifest = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "file1.txt",
                        type = EntryType.FILE,
                        permissions = 420, // 0o644
                        fileSize = fileData1.size.toLong(),
                        modTime = Instant.now(),
                        objectId = fileOid1.toString()
                    ),
                    DirEntry(
                        name = "file2.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = fileData2.size.toLong(),
                        modTime = Instant.now(),
                        objectId = fileOid2.toString()
                    ),
                    DirEntry(
                        name = "file3.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = fileData3.size.toLong(),
                        modTime = Instant.now(),
                        objectId = fileOid3.toString()
                    )
                )
            )
            val dirOid = writer.writeDirManifestObject(dirManifest)

            // Flush objects to storage, then store the snapshot manifest
            writer.flush()
            repository.refresh()

            val source = SourceInfo("testhost", "testuser", "/test/path")
            writer.createSnapshot("snap-1", source, dirOid)
            writer.flush()
            repository.refresh()
            writer.close()

            // Run GC -- should not throw
            val stats = SnapshotGC(repository).run()

            // Stats object is returned (all zeros due to iterateContentByPrefix being a stub)
            assertThat(stats).isNotNull()
            assertThat(stats.unreferencedContentCount).isEqualTo(0)
            assertThat(stats.deletedContentCount).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Content preservation")
    inner class ContentPreservation {

        @Test
        fun `should preserve all content when GC runs`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()

            val fileData1 = "preserve-me-alpha".toByteArray()
            val fileData2 = "preserve-me-beta".toByteArray()

            val fileOid1 = writer.writeFileObject(fileData1)
            val fileOid2 = writer.writeFileObject(fileData2)

            val dirManifest = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "alpha.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = fileData1.size.toLong(),
                        modTime = Instant.now(),
                        objectId = fileOid1.toString()
                    ),
                    DirEntry(
                        name = "beta.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = fileData2.size.toLong(),
                        modTime = Instant.now(),
                        objectId = fileOid2.toString()
                    )
                )
            )
            val dirOid = writer.writeDirManifestObject(dirManifest)

            writer.flush()
            repository.refresh()

            val source = SourceInfo("testhost", "testuser", "/data")
            writer.createSnapshot("snap-preserve", source, dirOid)
            writer.flush()
            repository.refresh()
            writer.close()

            // Run GC
            SnapshotGC(repository).run()

            // All objects must still be readable
            val read1 = repository.readObject(fileOid1)
            assertThat(read1).isEqualTo(fileData1)

            val read2 = repository.readObject(fileOid2)
            assertThat(read2).isEqualTo(fileData2)

            val readDir = repository.readObject(dirOid)
            val parsedDir = DirManifest.fromJson(readDir.decodeToString())
            assertThat(parsedDir.entries).hasSize(2)
            assertThat(parsedDir.entries.map { it.name }).containsExactly("alpha.txt", "beta.txt")
        }
    }

    @Nested
    @DisplayName("Multiple snapshots")
    inner class MultipleSnapshots {

        @Test
        fun `should handle repository with multiple snapshots`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()

            // Snapshot 1: two files
            val data1a = "snapshot-one-file-a".toByteArray()
            val data1b = "snapshot-one-file-b".toByteArray()
            val oid1a = writer.writeFileObject(data1a)
            val oid1b = writer.writeFileObject(data1b)

            val dir1 = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "a.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = data1a.size.toLong(),
                        modTime = Instant.now(),
                        objectId = oid1a.toString()
                    ),
                    DirEntry(
                        name = "b.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = data1b.size.toLong(),
                        modTime = Instant.now(),
                        objectId = oid1b.toString()
                    )
                )
            )
            val dirOid1 = writer.writeDirManifestObject(dir1)

            // Snapshot 2: two different files
            val data2a = "snapshot-two-file-x".toByteArray()
            val data2b = "snapshot-two-file-y".toByteArray()
            val oid2a = writer.writeFileObject(data2a)
            val oid2b = writer.writeFileObject(data2b)

            val dir2 = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "x.dat",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = data2a.size.toLong(),
                        modTime = Instant.now(),
                        objectId = oid2a.toString()
                    ),
                    DirEntry(
                        name = "y.dat",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = data2b.size.toLong(),
                        modTime = Instant.now(),
                        objectId = oid2b.toString()
                    )
                )
            )
            val dirOid2 = writer.writeDirManifestObject(dir2)

            writer.flush()
            repository.refresh()

            // Store both snapshot manifests
            val source1 = SourceInfo("host1", "user1", "/path1")
            val source2 = SourceInfo("host2", "user2", "/path2")
            writer.createSnapshot("snap-multi-1", source1, dirOid1)
            writer.createSnapshot("snap-multi-2", source2, dirOid2)
            writer.flush()
            repository.refresh()
            writer.close()

            // Run GC
            val stats = SnapshotGC(repository).run()
            assertThat(stats).isNotNull()

            // All objects from both snapshots must still be readable
            assertThat(repository.readObject(oid1a)).isEqualTo(data1a)
            assertThat(repository.readObject(oid1b)).isEqualTo(data1b)
            assertThat(repository.readObject(oid2a)).isEqualTo(data2a)
            assertThat(repository.readObject(oid2b)).isEqualTo(data2b)

            // Both directory manifests must be readable
            val parsedDir1 = DirManifest.fromJson(repository.readObject(dirOid1).decodeToString())
            assertThat(parsedDir1.entries).hasSize(2)

            val parsedDir2 = DirManifest.fromJson(repository.readObject(dirOid2).decodeToString())
            assertThat(parsedDir2.entries).hasSize(2)
        }
    }

    @Nested
    @DisplayName("Production-style directory traversal")
    inner class ProductionDirectoryTraversal {

        @Test
        fun `in-use set includes content nested under directories written without the k prefix`() = runTest {
            // Production directory objects are written WITHOUT the 'k' prefix, so isDirectoryId is
            // false for them. GC must still recurse into them (via DirEntry.type) or it under-collects
            // the in-use set and would delete live content once Phase 2 lands. Build a two-level tree
            // root/ -> subdir/ -> deep.txt using prefix-less dir objects, then assert the deeply-nested
            // file's content is collected as in-use. (task-9 prerequisite #2 regression)
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()

            val deepData = "deep-nested-content-must-survive-gc".toByteArray()
            val deepOid = writer.writeFileObject(deepData)

            // Subdirectory manifest, written WITHOUT the 'k' prefix (like production FileUploader).
            val subDir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "deep.txt",
                        type = EntryType.FILE,
                        permissions = 420,
                        fileSize = deepData.size.toLong(),
                        modTime = Instant.now(),
                        objectId = deepOid.toString()
                    )
                )
            )
            val subDirOid = writer.writeProductionDirManifestObject(subDir)

            // Root manifest referencing the subdirectory, also WITHOUT the 'k' prefix.
            val rootDir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "subdir",
                        type = EntryType.DIRECTORY,
                        permissions = 493,
                        modTime = Instant.now(),
                        objectId = subDirOid.toString()
                    )
                )
            )
            val rootOid = writer.writeProductionDirManifestObject(rootDir)

            writer.flush()
            repository.refresh()

            val source = SourceInfo("testhost", "testuser", "/nested")
            writer.createSnapshot("snap-nested", source, rootOid)
            writer.flush()
            repository.refresh()
            writer.close()

            // The content backing the deeply nested file must be reachable only via
            // root -> subdir -> deep.txt, so it is collected iff GC recurses into prefix-less dirs.
            val deepContentIds = repository.verifyObject(deepOid)
            assertThat(deepContentIds).isNotEmpty()

            val inUseSet = SnapshotGC(repository).buildInUseSet()
            try {
                for (contentId in deepContentIds) {
                    assertThat(inUseSet.contains(contentId)).isTrue()
                }
            } finally {
                inUseSet.close()
            }
        }
    }

    @Nested
    @DisplayName("Cancellation safety")
    inner class CancellationSafety {

        @Test
        fun `buildInUseSet propagates coroutine cancellation instead of returning a partial in-use set`() = runTest {
            // A cancelled GC coroutine (e.g. WorkManager stops the job mid-walk) must NOT be swallowed by
            // the walk's generic catches and reported as a completed-but-under-collected in-use set — that
            // would let a future deletion pass drop live data. Simulate cancellation by throwing
            // CancellationException from verifyObject mid-walk; buildInUseSet must propagate it. (task-9)
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()
            val fileData = "cancel-me".toByteArray()
            val fileOid = writer.writeFileObject(fileData)
            val dir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "f.txt",
                        type = EntryType.FILE,
                        fileSize = fileData.size.toLong(),
                        modTime = Instant.now(),
                        objectId = fileOid.toString()
                    )
                )
            )
            val dirOid = writer.writeDirManifestObject(dir)
            writer.flush()
            repository.refresh()
            writer.createSnapshot("snap-cancel", SourceInfo("h", "u", "/p"), dirOid)
            writer.flush()
            repository.refresh()
            writer.close()

            // Delegate everything to the real repo, but make the tree walk hit a cancellation.
            val cancellingRepo = object : DirectRepository by repository {
                override suspend fun verifyObject(objectId: ObjectId): List<ContentId> =
                    throw CancellationException("cancelled mid-walk")
            }

            var thrown: CancellationException? = null
            try {
                SnapshotGC(cancellingRepo).buildInUseSet()
            } catch (e: CancellationException) {
                thrown = e
            }
            assertThat(thrown).isNotNull()
        }
    }

    @Nested
    @DisplayName("Empty repository")
    inner class EmptyRepository {

        @Test
        fun `should handle repository with no snapshots`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            // Write some objects but do NOT create snapshot manifests
            val writer = repository.newDirectWriter()
            val orphanData = "orphan-content-no-snapshot".toByteArray()
            val orphanOid = writer.writeFileObject(orphanData)
            writer.flush()
            repository.refresh()
            writer.close()

            // Run GC on a repository with objects but no snapshots
            val stats = SnapshotGC(repository).run()

            // Should complete without errors
            assertThat(stats).isNotNull()
            // All stats are zero because iterateContentByPrefix is a stub
            assertThat(stats.unreferencedContentCount).isEqualTo(0)
            assertThat(stats.deletedContentCount).isEqualTo(0)
            assertThat(stats.inUseContentCount).isEqualTo(0)

            // Orphan content is still readable (GC dry run doesn't delete anything)
            val readBack = repository.readObject(orphanOid)
            assertThat(readBack).isEqualTo(orphanData)
        }
    }

    @Nested
    @DisplayName("Phase 2 content deletion")
    inner class Phase2ContentDeletion {

        // No age grace period: content is eligible for deletion the moment it is unreferenced. The
        // default (24h) would keep everything (freshly-written content is always "too recent").
        private val noGrace = SafetyParameters(minContentAgeSubjectToGC = Duration.ZERO)

        /**
         * Builds a repository with one snapshot referencing [referencedFile] and an unreferenced
         * orphan file. Returns the referenced-file object id and the orphan object id.
         */
        private suspend fun buildRepoWithOrphan(
            repository: DirectRepositoryImpl,
            referencedData: ByteArray,
            orphanData: ByteArray
        ): Pair<ObjectId, ObjectId> {
            val writer = repository.newDirectWriter()
            val referencedOid = writer.writeFileObject(referencedData)
            val orphanOid = writer.writeFileObject(orphanData)

            val dir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "kept.txt",
                        type = EntryType.FILE,
                        fileSize = referencedData.size.toLong(),
                        modTime = Instant.now(),
                        objectId = referencedOid.toString()
                    )
                )
            )
            val dirOid = writer.writeDirManifestObject(dir)
            writer.flush()
            repository.refresh()
            writer.createSnapshot("snap-orphan", SourceInfo("h", "u", "/p"), dirOid)
            writer.flush()
            repository.refresh()
            writer.close()
            return referencedOid to orphanOid
        }

        @Test
        fun `delete=true reclaims unreferenced content and keeps referenced content`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val keptData = "referenced-must-survive".toByteArray()
            val orphanData = "orphan-must-be-reclaimed".toByteArray()
            val (keptOid, orphanOid) = buildRepoWithOrphan(repository, keptData, orphanData)

            val stats = SnapshotGC(repository).run(GCOptions(delete = true, safety = noGrace))
            repository.refresh()

            // Referenced content survives; the orphan is gone.
            assertThat(repository.readObject(keptOid)).isEqualTo(keptData)
            assertThrows<ObjectNotFoundException> { repository.readObject(orphanOid) }

            // The snapshot manifest ('m' content) is system content and is always kept.
            val manifests = repository.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
            )
            assertThat(manifests).isNotEmpty()

            assertThat(stats.deletedContentCount).isAtLeast(1)
            assertThat(stats.unreferencedContentCount).isAtLeast(1)
            assertThat(stats.inUseContentCount).isAtLeast(1)
            assertThat(stats.inUseSystemContentCount).isAtLeast(1)
        }

        @Test
        fun `unreferenced content younger than minContentAge is kept`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val orphanData = "too-recent-to-delete".toByteArray()
            val (_, orphanOid) = buildRepoWithOrphan(repository, "kept".toByteArray(), orphanData)

            // Default 24h grace: the freshly-written orphan is too recent to delete.
            val stats = SnapshotGC(repository).run(GCOptions(delete = true))
            repository.refresh()

            assertThat(repository.readObject(orphanOid)).isEqualTo(orphanData)
            assertThat(stats.deletedContentCount).isEqualTo(0)
            assertThat(stats.unreferencedRecentContentCount).isAtLeast(1)
        }

        @Test
        fun `dry run reports but never deletes unreferenced content`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val orphanData = "orphan-dry-run".toByteArray()
            val (_, orphanOid) = buildRepoWithOrphan(repository, "kept".toByteArray(), orphanData)

            // delete=false with no grace: the orphan is REPORTED as unreferenced but not removed.
            val stats = SnapshotGC(repository).run(GCOptions(delete = false, safety = noGrace))
            repository.refresh()

            assertThat(repository.readObject(orphanOid)).isEqualTo(orphanData)
            assertThat(stats.unreferencedContentCount).isAtLeast(1)
            assertThat(stats.deletedContentCount).isEqualTo(0)
        }

        @Test
        fun `delete run fails closed and deletes nothing when a referenced object cannot be verified`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val orphanData = "orphan-protected-by-abort".toByteArray()
            val (_, orphanOid) = buildRepoWithOrphan(repository, "kept".toByteArray(), orphanData)

            // A referenced object that cannot be verified (transient read error / corruption) must ABORT
            // a delete run — a partial in-use set would let live-tree content be reclaimed. Simulate it by
            // failing verifyObject during the walk.
            val failingRepo = object : DirectRepositoryWriter by repository {
                override suspend fun verifyObject(objectId: ObjectId): List<ContentId> =
                    throw RuntimeException("simulated corrupt object")
            }

            assertThrows<RuntimeException> {
                SnapshotGC(failingRepo).run(GCOptions(delete = true, safety = noGrace))
            }

            // Nothing was deleted: the orphan (which WOULD have been reclaimed) is still present.
            repository.refresh()
            assertThat(repository.readObject(orphanOid)).isEqualTo(orphanData)
        }

        @Test
        fun `dry run is fail-open and tolerates an unverifiable object without aborting`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            buildRepoWithOrphan(repository, "kept".toByteArray(), "orphan".toByteArray())

            val failingRepo = object : DirectRepositoryWriter by repository {
                override suspend fun verifyObject(objectId: ObjectId): List<ContentId> =
                    throw RuntimeException("simulated corrupt object")
            }

            // delete=false must not abort on an unverifiable object — dry runs are best-effort.
            val stats = SnapshotGC(failingRepo).run(GCOptions(delete = false, safety = noGrace))
            assertThat(stats).isNotNull()
        }

        @Test
        fun `delete run completes on a snapshot containing an empty file`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()
            // A zero-byte file resolves to ObjectId.Empty (DirEntry.obj == ""). verifyObject must treat it
            // as backed by no content, or the fail-closed walk aborts delete-GC on ANY repo with an empty
            // file. Include a normal file too, to confirm the run still does real work.
            val emptyOid = writer.writeObject(ByteArray(0))
            val normalData = "normal-file-alongside-empty".toByteArray()
            val normalOid = writer.writeFileObject(normalData)

            val dir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "empty.txt", type = EntryType.FILE, fileSize = 0,
                        modTime = Instant.now(), objectId = emptyOid.toString()
                    ),
                    DirEntry(
                        name = "normal.txt", type = EntryType.FILE, fileSize = normalData.size.toLong(),
                        modTime = Instant.now(), objectId = normalOid.toString()
                    )
                )
            )
            val dirOid = writer.writeDirManifestObject(dir)
            writer.flush()
            repository.refresh()
            writer.createSnapshot("snap-empty-file", SourceInfo("h", "u", "/e"), dirOid)
            writer.flush()
            repository.refresh()
            writer.close()

            // Must NOT abort. The normal (referenced) file survives.
            SnapshotGC(repository).run(GCOptions(delete = true, safety = noGrace))
            repository.refresh()
            assertThat(repository.readObject(normalOid)).isEqualTo(normalData)
        }

        @Test
        fun `delete run keeps a multi-chunk indirect file and its index content`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()
            // > 1 MB with the FIXED-1M splitter => multiple chunks => an INDIRECT object with its own index
            // content. GC must collect the index content too (verifyObject recurses into it), or deleting
            // it orphans the file's chunks. Distinct bytes per 32-byte block to avoid whole-file dedup.
            val bigData = ByteArray(3 * 1024 * 1024) { ((it / 32) * 7 + it).toByte() }
            val bigOid = writer.writeFileObject(bigData)

            val dir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "big.bin", type = EntryType.FILE, fileSize = bigData.size.toLong(),
                        modTime = Instant.now(), objectId = bigOid.toString()
                    )
                )
            )
            val dirOid = writer.writeDirManifestObject(dir)
            writer.flush()
            repository.refresh()
            writer.createSnapshot("snap-indirect", SourceInfo("h", "u", "/big"), dirOid)
            writer.flush()
            repository.refresh()
            writer.close()

            // The referenced large file (chunks + index content) survives a full-reclaim delete run.
            SnapshotGC(repository).run(GCOptions(delete = true, safety = noGrace))
            repository.refresh()
            assertThat(repository.readObject(bigOid)).isEqualTo(bigData)
        }

        @Test
        fun `entry with UNKNOWN type over a prefix-less directory is still recursed (no under-collection)`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            val writer = repository.newDirectWriter()
            val leafData = "leaf-under-an-unknown-typed-dir".toByteArray()
            val leafOid = writer.writeFileObject(leafData)

            // Subdirectory written WITHOUT the 'k' prefix (isDirectoryId == false), referenced by an entry
            // whose type is UNKNOWN (a corrupt / older / unrecognized manifest entry). GC must still
            // recurse into it, or the leaf looks unreferenced and gets tombstoned once deletion runs.
            val subDir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "leaf.txt",
                        type = EntryType.FILE,
                        fileSize = leafData.size.toLong(),
                        modTime = Instant.now(),
                        objectId = leafOid.toString()
                    )
                )
            )
            val subDirOid = writer.writeProductionDirManifestObject(subDir)

            val rootDir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "subdir",
                        type = EntryType.UNKNOWN, // ambiguous type over a prefix-less directory object
                        modTime = Instant.now(),
                        objectId = subDirOid.toString()
                    )
                )
            )
            val rootOid = writer.writeProductionDirManifestObject(rootDir)
            writer.flush()
            repository.refresh()
            writer.createSnapshot("snap-unknown", SourceInfo("h", "u", "/unknown"), rootOid)
            writer.flush()
            repository.refresh()
            writer.close()

            // Delete run with no grace period: the leaf survives ONLY if GC recursed into the
            // UNKNOWN-typed subdirectory and collected it as in-use.
            SnapshotGC(repository).run(GCOptions(delete = true, safety = noGrace))
            repository.refresh()
            assertThat(repository.readObject(leafOid)).isEqualTo(leafData)
        }

        @Test
        fun `delete run refuses to run over a partial index view (fail closed)`() = runTest {
            val (repository, storage) = TestRepositoryFactory.createInMemory()
            repo = repository

            val orphanData = "orphan-protected-by-completeness-gate".toByteArray()
            buildRepoWithOrphan(repository, "kept".toByteArray(), orphanData)

            // Corrupt one index blob so the next load must skip it. The committed view is then PARTIAL —
            // a live snapshot's manifest content could be hidden, making its referenced content look
            // unreferenced. A delete run must refuse rather than tombstone live data over a partial view.
            val indexBlobId = (storage.listBlobs("n").toList() + storage.listBlobs("x").toList())
                .first().blobId
            storage.putBlob(indexBlobId, ByteArray(64) { 0xEE.toByte() })

            repository.refresh()
            assertThat(repository.lastLoadWasComplete()).isFalse()

            val thrown = assertThrows<IllegalStateException> {
                SnapshotGC(repository).run(GCOptions(delete = true, safety = noGrace))
            }
            // Pin to the completeness gate (not the writer-cast IllegalStateException).
            assertThat(thrown.message).contains("incomplete")
        }

        @Test
        fun `oversized directory manifest aborts a delete run and is skipped in a dry run`() = runTest {
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            // A VALID, walkable directory manifest whose serialized size exceeds the cap. This isolates
            // the SIZE guard: without it the manifest parses fine and the walk recurses normally (so a
            // removed cap would let the run proceed); with a small cap the object is refused before being
            // loaded whole into RAM — the OOM guard against a huge manifest / a file lying as a directory.
            // A small cap the large directory manifest reliably exceeds (200 entries ≈ 20+ KB of JSON)
            // while the tiny root manifest (one entry ≈ 200 bytes) stays under it.
            val cap = 4096L

            val writer = repository.newDirectWriter()
            val subFileData = "leaf-content".toByteArray()
            val subFileOid = writer.writeFileObject(subFileData)
            // Many entries (all pointing at the same real file object, deduped) inflate the JSON well past
            // the small cap while keeping every child walkable.
            val entries = (0 until 200).map { i ->
                DirEntry(
                    name = "entry-with-a-reasonably-long-name-$i.txt",
                    type = EntryType.FILE,
                    fileSize = subFileData.size.toLong(),
                    modTime = Instant.now(),
                    objectId = subFileOid.toString()
                )
            }
            val bigDir = DirManifest(entries = entries)
            val bigDirOid = writer.writeProductionDirManifestObject(bigDir)

            val rootDir = DirManifest(
                entries = listOf(
                    DirEntry(
                        name = "bigdir",
                        type = EntryType.DIRECTORY,
                        modTime = Instant.now(),
                        objectId = bigDirOid.toString()
                    )
                )
            )
            val rootOid = writer.writeProductionDirManifestObject(rootDir)
            writer.flush()
            repository.refresh()
            writer.createSnapshot("snap-oversized", SourceInfo("h", "u", "/big"), rootOid)
            writer.flush()
            repository.refresh()
            writer.close()

            // Delete run: the oversized directory manifest cannot be safely loaded → fail closed (abort).
            assertThrows<Exception> {
                SnapshotGC(repository, maxDirectoryManifestSize = cap)
                    .run(GCOptions(delete = true, safety = noGrace))
            }

            // Dry run: the same object is skipped (fail open), the run completes without throwing.
            val stats = SnapshotGC(repository, maxDirectoryManifestSize = cap)
                .run(GCOptions(delete = false, safety = noGrace))
            assertThat(stats).isNotNull()
        }
    }
}
