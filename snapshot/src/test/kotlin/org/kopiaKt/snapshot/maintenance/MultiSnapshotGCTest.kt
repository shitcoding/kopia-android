package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.DirectRepository
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
        fun `run with delete=true fails loud until Phase 2 is implemented`() = runTest {
            // Phase 2 (content sweep/delete) is a stub, so delete=true must throw rather than silently
            // report success while reclaiming nothing (task-9). The throw precedes any repository access.
            val (repository, _) = TestRepositoryFactory.createInMemory()
            repo = repository

            var thrown: UnsupportedOperationException? = null
            try {
                SnapshotGC(repository).run(GCOptions(delete = true))
            } catch (e: UnsupportedOperationException) {
                thrown = e
            }
            assertThat(thrown).isNotNull()
            assertThat(thrown!!.message).contains("Phase 2")
        }

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

            // Orphan content is still readable (GC stub doesn't delete anything)
            val readBack = repository.readObject(orphanOid)
            assertThat(readBack).isEqualTo(orphanData)
        }
    }
}
