package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.manifest.EntryMetadata
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.`object`.ObjectReader
import org.kopiaKt.core.`object`.ObjectWriter
import org.kopiaKt.core.`object`.ObjectWriterOptions
import org.kopiaKt.core.repository.ConcatenateOptions
import org.kopiaKt.core.repository.ClientOptions
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.DirectoryIterator
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.CompressionPolicy
import org.kopiaKt.snapshot.policy.ErrorHandlingPolicy
import org.kopiaKt.snapshot.policy.FilesPolicy
import org.kopiaKt.snapshot.policy.Policy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for the snapshot upload pipeline.
 *
 * Tests the SnapshotUploader with policy application, incremental uploads,
 * error handling, and edge cases using in-memory mock implementations.
 */
class SnapshotUploadIntegrationTest {

    private val testSource = SourceInfo(
        host = "testhost",
        userName = "testuser",
        path = "/test/path"
    )

    // -----------------------------------------------------------------------
    //  Policy Application During Upload
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Policy Application During Upload")
    inner class PolicyApplication {

        @Test
        @DisplayName("upload applies compression policy")
        fun `upload applies compression policy`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val policy = Policy(
                compressionPolicy = CompressionPolicy(
                    compressorName = "zstd",
                    minSize = 10
                )
            )

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = policy,
                progress = CountingUploadProgress()
            )

            val rootDir = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("data.txt", ByteArray(100) { it.toByte() })
                )
            )

            val result = uploader.upload(rootDir)

            assertFalse(result.incomplete, "Upload should complete successfully")
            assertNotNull(result.manifestId)

            // Verify that compression was requested in object writer options
            val fileWriteOptions = writer.objectWriterOptions
                .filter { it.compression != null }
            assertTrue(
                fileWriteOptions.isNotEmpty(),
                "At least one object should be written with compression enabled"
            )
        }

        @Test
        @DisplayName("upload applies file exclusion rules")
        fun `upload applies file exclusion rules`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val policy = Policy(
                filesPolicy = FilesPolicy(
                    ignoreRules = listOf("*.tmp", "*.log")
                )
            )
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = policy,
                progress = progress
            )

            val rootDir = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("keep.txt", "keep me".toByteArray()),
                    InMemoryFile("temp.tmp", "temporary".toByteArray()),
                    InMemoryFile("app.log", "log data".toByteArray()),
                    InMemoryFile("also_keep.dat", "important".toByteArray())
                )
            )

            val result = uploader.upload(rootDir)

            assertFalse(result.incomplete)

            // Read the root manifest to check entries
            val rootObjectId = result.manifest.rootEntry?.objectId
            assertNotNull(rootObjectId, "Root entry should have objectId")

            val rootManifestBytes = writer.readObject(ObjectId.parse(rootObjectId!!))
            val rootManifest = DirManifest.fromJson(rootManifestBytes.toString(Charsets.UTF_8))

            val entryNames = rootManifest.entries.map { it.name }
            assertTrue("keep.txt" in entryNames, "keep.txt should be in manifest")
            assertTrue("also_keep.dat" in entryNames, "also_keep.dat should be in manifest")
            assertFalse("temp.tmp" in entryNames, "temp.tmp should be excluded")
            assertFalse("app.log" in entryNames, "app.log should be excluded")
        }

        @Test
        @DisplayName("upload respects max file size policy")
        fun `upload respects max file size policy`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val policy = Policy(
                filesPolicy = FilesPolicy(
                    maxFileSize = 50
                )
            )
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = policy,
                progress = progress
            )

            val rootDir = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("small.txt", ByteArray(30)),
                    InMemoryFile("large.txt", ByteArray(100)),
                    InMemoryFile("exact.txt", ByteArray(50)),
                    InMemoryFile("huge.bin", ByteArray(1000))
                )
            )

            val result = uploader.upload(rootDir)

            assertFalse(result.incomplete)

            val rootObjectId = result.manifest.rootEntry?.objectId
            assertNotNull(rootObjectId)

            val rootManifestBytes = writer.readObject(ObjectId.parse(rootObjectId!!))
            val rootManifest = DirManifest.fromJson(rootManifestBytes.toString(Charsets.UTF_8))

            val entryNames = rootManifest.entries.map { it.name }
            assertTrue("small.txt" in entryNames, "small.txt (30 bytes) should be included")
            assertTrue("exact.txt" in entryNames, "exact.txt (50 bytes) should be included")
            assertFalse("large.txt" in entryNames, "large.txt (100 bytes) should be excluded")
            assertFalse("huge.bin" in entryNames, "huge.bin (1000 bytes) should be excluded")
        }
    }

    // -----------------------------------------------------------------------
    //  Incremental Upload Verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Incremental Upload Verification")
    inner class IncrementalUpload {

        @Test
        @DisplayName("second upload deduplicates unchanged files")
        fun `second upload deduplicates unchanged files`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val progress1 = CountingUploadProgress()

            val fileContent = "unchanged content".toByteArray()
            val modTime = Instant.parse("2025-01-01T00:00:00Z")

            // First upload
            val uploader1 = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress1
            )

            val rootDir1 = InMemoryDirectory(
                "root",
                modTime = modTime,
                entries = listOf(
                    InMemoryFile("file.txt", fileContent, modTime = modTime)
                )
            )

            val result1 = uploader1.upload(rootDir1)
            assertFalse(result1.incomplete)

            val firstUploadHashedFiles = progress1.snapshot().totalHashedFiles

            // Second upload with same content and same metadata
            val progress2 = CountingUploadProgress()
            val uploader2 = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress2
            )

            val rootDir2 = InMemoryDirectory(
                "root",
                modTime = modTime,
                entries = listOf(
                    InMemoryFile("file.txt", fileContent, modTime = modTime)
                )
            )

            val result2 = uploader2.upload(rootDir2)
            assertFalse(result2.incomplete)

            val secondUploadCachedFiles = progress2.snapshot().totalCachedFiles
            val secondUploadHashedFiles = progress2.snapshot().totalHashedFiles

            // In the second upload, the file should be cached (deduped), not re-hashed
            assertTrue(firstUploadHashedFiles > 0, "First upload should hash files")
            assertTrue(
                secondUploadCachedFiles > 0,
                "Second upload should have cached files (dedup)"
            )
        }

        @Test
        @DisplayName("modified file re-uploaded on second backup")
        fun `modified file re-uploaded on second backup`() = runBlocking {
            val writer = TrackingRepositoryWriter()

            val originalModTime = Instant.parse("2025-01-01T00:00:00Z")
            val modifiedModTime = Instant.parse("2025-01-02T00:00:00Z")

            // First upload
            val progress1 = CountingUploadProgress()
            val uploader1 = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress1
            )

            val rootDir1 = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("file.txt", "original content".toByteArray(), modTime = originalModTime)
                )
            )

            val result1 = uploader1.upload(rootDir1)
            assertFalse(result1.incomplete)

            // Get first snapshot's root entry objectId for comparison
            val firstRootObjId = result1.manifest.rootEntry?.objectId

            // Second upload with modified content and different modTime
            val progress2 = CountingUploadProgress()
            val uploader2 = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress2
            )

            val rootDir2 = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("file.txt", "modified content".toByteArray(), modTime = modifiedModTime)
                )
            )

            val result2 = uploader2.upload(rootDir2)
            assertFalse(result2.incomplete)

            // The modified file should have been re-hashed (not cached)
            val secondCounters = progress2.snapshot()
            assertTrue(
                secondCounters.totalHashedFiles > 0,
                "Modified file should be re-hashed"
            )

            // Root manifests should differ since content changed
            val secondRootObjId = result2.manifest.rootEntry?.objectId
            assertNotNull(firstRootObjId)
            assertNotNull(secondRootObjId)
            assertNotEquals(
                firstRootObjId, secondRootObjId,
                "Root object IDs should differ when content changes"
            )
        }

        @Test
        @DisplayName("deleted file absent from second snapshot manifest")
        fun `deleted file absent from second snapshot manifest`() = runBlocking {
            val writer = TrackingRepositoryWriter()

            // First upload with two files
            val uploader1 = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = CountingUploadProgress()
            )

            val rootDir1 = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("keep.txt", "keep me".toByteArray()),
                    InMemoryFile("delete_me.txt", "will be deleted".toByteArray())
                )
            )

            val result1 = uploader1.upload(rootDir1)
            assertFalse(result1.incomplete)

            // Verify both files are in first snapshot
            val rootObjId1 = result1.manifest.rootEntry?.objectId!!
            val manifest1 = DirManifest.fromJson(
                writer.readObject(ObjectId.parse(rootObjId1)).toString(Charsets.UTF_8)
            )
            val names1 = manifest1.entries.map { it.name }
            assertTrue("keep.txt" in names1)
            assertTrue("delete_me.txt" in names1)

            // Second upload without the deleted file
            val uploader2 = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = CountingUploadProgress()
            )

            val rootDir2 = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("keep.txt", "keep me".toByteArray())
                )
            )

            val result2 = uploader2.upload(rootDir2)
            assertFalse(result2.incomplete)

            // Verify deleted file is absent in second snapshot
            val rootObjId2 = result2.manifest.rootEntry?.objectId!!
            val manifest2 = DirManifest.fromJson(
                writer.readObject(ObjectId.parse(rootObjId2)).toString(Charsets.UTF_8)
            )
            val names2 = manifest2.entries.map { it.name }
            assertTrue("keep.txt" in names2, "keep.txt should still be present")
            assertFalse("delete_me.txt" in names2, "delete_me.txt should be absent")
        }
    }

    // -----------------------------------------------------------------------
    //  Upload Error Handling
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Upload Error Handling")
    inner class UploadErrorHandling {

        @Test
        @DisplayName("upload with read error marks snapshot incomplete when error not ignored")
        fun `upload with read error marks snapshot incomplete`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            // Default error policy: ignoreFileErrors=null (treated as false)
            val policy = Policy(
                errorHandlingPolicy = ErrorHandlingPolicy(
                    ignoreFileErrors = false
                )
            )

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = policy,
                progress = progress
            )

            val rootDir = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("good.txt", "good content".toByteArray()),
                    FailingFile("bad.txt", IOException("Disk read error"))
                )
            )

            val result = uploader.upload(rootDir)

            // Should be incomplete due to read error
            assertTrue(result.incomplete, "Snapshot should be incomplete when file read error occurs")
            assertNotNull(result.incompleteReason, "Incomplete reason should be set")
            assertTrue(
                result.incompleteReason!!.contains("error"),
                "Incomplete reason should mention error"
            )
        }

        @Test
        @DisplayName("upload with ignored read error completes with error count")
        fun `upload with ignored read error completes with error count`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            val policy = Policy(
                errorHandlingPolicy = ErrorHandlingPolicy(
                    ignoreFileErrors = true
                )
            )

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = policy,
                progress = progress
            )

            val rootDir = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryFile("good.txt", "good content".toByteArray()),
                    FailingFile("bad.txt", IOException("Disk read error")),
                    InMemoryFile("also_good.txt", "also good".toByteArray())
                )
            )

            val result = uploader.upload(rootDir)

            // Should complete (not incomplete) because errors are ignored
            assertFalse(result.incomplete, "Snapshot should complete when errors are ignored")
            assertTrue(
                result.stats.ignoredErrorCount > 0,
                "Should have ignored error count > 0"
            )
        }

        @Test
        @DisplayName("upload cancellation stops mid-upload")
        fun `upload cancellation stops mid-upload`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress
            )

            // Strategy: Use a CancellingDirectory with no children that fires
            // the cancel during its iterate() call. Place a sibling directory
            // after it. Because entries are processed in launch order and
            // the coroutines share a single-threaded dispatcher (runBlocking),
            // the sibling directory's walkDirectory will be entered after
            // the cancel flag is already set, causing CancelledException.
            //
            // The root dir's children are iterated all at once, then each
            // processEntry async is launched. Under single-threaded dispatch,
            // they execute sequentially. The cancel trigger finishes first
            // (its walkDirectory returns since it has no entries), then the
            // sibling's processEntry calls walkDirectory which checks
            // cancelled.get() at the top and throws.
            val cancelTrigger = CancellingDirectory(
                // Name sorts before "zz_sibling" to ensure it's processed first
                "aa_trigger",
                uploader = uploader,
                entries = emptyList()
            )
            val siblingDir = InMemoryDirectory(
                "zz_sibling",
                entries = listOf(InMemoryFile("file.txt", ByteArray(10)))
            )
            val rootDir = InMemoryDirectory(
                "root",
                entries = listOf(cancelTrigger, siblingDir)
            )

            val result = uploader.upload(rootDir)

            // Should be marked as incomplete/canceled
            assertTrue(
                result.incomplete,
                "Cancelled upload should be incomplete"
            )
            assertEquals("canceled", result.incompleteReason)
        }
    }

    // -----------------------------------------------------------------------
    //  Empty/Edge Cases
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Empty and Edge Cases")
    inner class EmptyEdgeCases {

        @Test
        @DisplayName("upload empty directory creates valid snapshot")
        fun `upload empty directory creates valid snapshot`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress
            )

            val emptyDir = InMemoryDirectory("root", entries = emptyList())

            val result = uploader.upload(emptyDir)

            // Snapshot should be complete and valid
            assertFalse(result.incomplete, "Empty directory upload should complete successfully")
            assertNotNull(result.manifestId, "Should have a manifest ID")
            assertNotNull(result.manifest.rootEntry, "Should have a root entry")
            assertNotNull(
                result.manifest.rootEntry!!.objectId,
                "Root entry should have objectId"
            )
            assertEquals(
                EntryType.DIRECTORY,
                result.manifest.rootEntry!!.type,
                "Root entry should be a directory"
            )

            // Read root manifest and verify 0 entries
            val rootObjectId = result.manifest.rootEntry!!.objectId!!
            val rootManifestBytes = writer.readObject(ObjectId.parse(rootObjectId))
            val rootManifest = DirManifest.fromJson(rootManifestBytes.toString(Charsets.UTF_8))

            assertTrue(rootManifest.entries.isEmpty(), "Empty dir should have 0 entries")
            assertEquals(
                "kopia:directory",
                rootManifest.streamType,
                "Should have correct stream type"
            )
            assertNotNull(rootManifest.summary, "Should have a summary")
            assertEquals(0L, rootManifest.summary!!.totalFileCount)
            assertEquals(1, rootManifest.summary!!.totalDirCount, "Should count itself")
        }

        @Test
        @DisplayName("upload nested empty directories creates valid snapshot")
        fun `upload nested empty directories creates valid snapshot`() = runBlocking {
            val writer = TrackingRepositoryWriter()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = CountingUploadProgress()
            )

            val rootDir = InMemoryDirectory(
                "root",
                entries = listOf(
                    InMemoryDirectory("subdir_a", entries = emptyList()),
                    InMemoryDirectory(
                        "subdir_b",
                        entries = listOf(
                            InMemoryDirectory("nested", entries = emptyList())
                        )
                    )
                )
            )

            val result = uploader.upload(rootDir)

            assertFalse(result.incomplete)
            assertNotNull(result.manifest.rootEntry?.objectId)

            val rootObjectId = result.manifest.rootEntry!!.objectId!!
            val rootManifestBytes = writer.readObject(ObjectId.parse(rootObjectId))
            val rootManifest = DirManifest.fromJson(rootManifestBytes.toString(Charsets.UTF_8))

            // Root should contain 2 directory entries
            assertEquals(2, rootManifest.entries.size)
            assertTrue(rootManifest.entries.all { it.type == EntryType.DIRECTORY })

            // Summary should count all directories
            val summary = rootManifest.summary!!
            // root(1) + subdir_a(1) + subdir_b(1) + nested(1) = 4
            assertEquals(4, summary.totalDirCount, "Should count all directories")
            assertEquals(0L, summary.totalFileCount, "Should have no files")
        }
    }

    // -----------------------------------------------------------------------
    //  In-memory filesystem mocks
    // -----------------------------------------------------------------------

    /**
     * In-memory file implementation for testing.
     */
    private class InMemoryFile(
        override val name: String,
        private val content: ByteArray,
        override val modTime: Instant = Instant.parse("2025-06-01T12:00:00Z"),
        override val mode: Int = 420 // 0644
    ) : org.kopiaKt.snapshot.fs.File {
        override val type = org.kopiaKt.snapshot.fs.EntryType.FILE
        override val size: Long = content.size.toLong()
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun open(): InputStream = ByteArrayInputStream(content)
        override fun close() {}
    }

    /**
     * File that throws an exception when opened.
     */
    private class FailingFile(
        override val name: String,
        private val error: Throwable,
        override val modTime: Instant = Instant.parse("2025-06-01T12:00:00Z"),
        override val mode: Int = 420
    ) : org.kopiaKt.snapshot.fs.File {
        override val type = org.kopiaKt.snapshot.fs.EntryType.FILE
        override val size: Long = 100
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun open(): InputStream = throw error
        override fun close() {}
    }

    /**
     * A directory that cancels the uploader when its children are iterated.
     * Used to test cancellation: children returned from iterate() include
     * subdirectories whose walkDirectory calls will see the cancelled flag.
     */
    private class CancellingDirectory(
        override val name: String,
        private val uploader: SnapshotUploader,
        private val entries: List<Entry> = emptyList(),
        override val modTime: Instant = Instant.parse("2025-06-01T12:00:00Z"),
        override val mode: Int = 493
    ) : Directory {
        override val type = org.kopiaKt.snapshot.fs.EntryType.DIRECTORY
        override val size: Long = 0
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun child(name: String): Entry? {
            return entries.find { it.name == name }
        }

        override suspend fun iterate(): DirectoryIterator {
            // Cancel the uploader before returning children.
            // The child directories will check the cancelled flag in walkDirectory.
            uploader.cancel()
            return InMemoryDirectoryIterator(entries)
        }

        override fun close() {}
    }

    /**
     * In-memory directory implementation for testing.
     */
    private class InMemoryDirectory(
        override val name: String,
        private val entries: List<Entry> = emptyList(),
        override val modTime: Instant = Instant.parse("2025-06-01T12:00:00Z"),
        override val mode: Int = 493 // 0755
    ) : Directory {
        override val type = org.kopiaKt.snapshot.fs.EntryType.DIRECTORY
        override val size: Long = 0
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun child(name: String): Entry? {
            return entries.find { it.name == name }
        }

        override suspend fun iterate(): DirectoryIterator {
            return InMemoryDirectoryIterator(entries)
        }

        override fun close() {}
    }

    /**
     * Simple in-memory directory iterator.
     */
    private class InMemoryDirectoryIterator(
        private val entries: List<Entry>
    ) : DirectoryIterator {
        private var index = 0

        override suspend fun next(): Entry? {
            return if (index < entries.size) entries[index++] else null
        }

        override fun close() {}
    }

    // -----------------------------------------------------------------------
    //  Tracking Repository Writer mock
    // -----------------------------------------------------------------------

    /**
     * A mock RepositoryWriter that tracks all operations in memory.
     * Supports reading back written objects and manifests for verification.
     */
    private class TrackingRepositoryWriter : RepositoryWriter {
        private val objectStore = ConcurrentHashMap<String, ByteArray>()
        private val manifestStore = ConcurrentHashMap<String, ManifestEntry>()
        private val nextId = AtomicInteger(1)

        /** Options passed to newObjectWriter calls */
        val objectWriterOptions = mutableListOf<ObjectWriterOptions>()

        /** Number of writeObject calls */
        val writeCount = AtomicInteger(0)

        data class ManifestEntry(
            val id: ManifestId,
            val labels: Map<String, String>,
            val payload: ByteArray,
            val modTime: Instant
        )

        private fun nextHexId(): String = String.format("%032x", nextId.getAndIncrement())

        override fun newObjectWriter(options: ObjectWriterOptions): ObjectWriter {
            synchronized(objectWriterOptions) {
                objectWriterOptions.add(options)
            }
            return TrackingObjectWriter { data ->
                writeCount.incrementAndGet()
                val id = nextHexId()
                objectStore[id] = data
                ObjectId.parse(id)
            }
        }

        override suspend fun writeObject(
            data: ByteArray,
            options: ObjectWriterOptions
        ): ObjectId {
            synchronized(objectWriterOptions) {
                objectWriterOptions.add(options)
            }
            writeCount.incrementAndGet()
            val id = nextHexId()
            objectStore[id] = data
            return ObjectId.parse(id)
        }

        override suspend fun concatenateObjects(
            objectIds: List<ObjectId>,
            options: ConcatenateOptions
        ): ObjectId {
            TODO("Not needed for upload tests")
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> putManifest(
            labels: Map<String, String>,
            payload: T,
            serializer: KSerializer<T>
        ): ManifestId {
            val json = kotlinx.serialization.json.Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
            val jsonStr = json.encodeToString(serializer, payload)
            val id = ManifestId.generate()
            manifestStore[id.value] = ManifestEntry(
                id = id,
                labels = labels,
                payload = jsonStr.toByteArray(Charsets.UTF_8),
                modTime = Instant.now()
            )
            return id
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> replaceManifests(
            labels: Map<String, String>,
            payload: T,
            serializer: KSerializer<T>
        ): ManifestId {
            // Remove existing manifests with matching labels, then add new
            manifestStore.entries.removeIf { (_, entry) ->
                labels.all { (k, v) -> entry.labels[k] == v }
            }
            return putManifest(labels, payload, serializer)
        }

        override suspend fun deleteManifest(id: ManifestId) {
            manifestStore.remove(id.value)
        }

        override fun onSuccessfulFlush(callback: suspend (RepositoryWriter) -> Unit) {
            // No-op for tests
        }

        override suspend fun flush() {
            // No-op for tests
        }

        // --- Read operations ---

        override fun openObject(objectId: ObjectId): ObjectReader {
            TODO("Not needed for upload tests")
        }

        override suspend fun readObject(objectId: ObjectId): ByteArray {
            val id = objectId.toString()
            return objectStore[id]
                ?: throw NoSuchElementException("Object not found: $id")
        }

        override suspend fun verifyObject(objectId: ObjectId): List<ContentId> {
            TODO("Not needed for upload tests")
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> getManifest(
            id: ManifestId,
            serializer: KSerializer<T>
        ): Pair<T, EntryMetadata> {
            val entry = manifestStore[id.value]
                ?: throw NoSuchElementException("Manifest not found: ${id.value}")
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }
            val payload = json.decodeFromString(serializer, entry.payload.toString(Charsets.UTF_8))
            val metadata = EntryMetadata(
                id = entry.id,
                length = entry.payload.size,
                labels = entry.labels,
                modTime = entry.modTime
            )
            return payload to metadata
        }

        override suspend fun findManifests(labels: Map<String, String>): List<EntryMetadata> {
            return manifestStore.values
                .filter { entry ->
                    labels.all { (k, v) -> entry.labels[k] == v }
                }
                .map { entry ->
                    EntryMetadata(
                        id = entry.id,
                        length = entry.payload.size,
                        labels = entry.labels,
                        modTime = entry.modTime
                    )
                }
        }

        override suspend fun contentInfo(contentId: ContentId): ContentInfo? = null

        override fun time(): Instant = Instant.now()

        override fun clientOptions(): ClientOptions = ClientOptions()

        override suspend fun newWriter(options: WriteSessionOptions): RepositoryWriter {
            return this
        }

        override fun updateDescription(description: String) {}

        override suspend fun refresh() {}

        override fun close() {}
    }

    /**
     * Object writer that tracks written data and returns an ObjectId.
     */
    private class TrackingObjectWriter(
        private val onResult: (ByteArray) -> ObjectId
    ) : ObjectWriter {
        private val buffer = ByteArrayOutputStream()

        override suspend fun write(data: ByteArray): Int {
            buffer.write(data)
            return data.size
        }

        override suspend fun checkpoint(): ObjectId {
            return ObjectId.Empty
        }

        override suspend fun result(): ObjectId {
            return onResult(buffer.toByteArray())
        }

        override suspend fun close() {}
    }
}
