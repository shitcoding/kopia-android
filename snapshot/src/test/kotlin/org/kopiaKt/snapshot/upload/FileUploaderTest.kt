package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.policy.CompressionPolicy
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

class FileUploaderTest {

    @Nested
    inner class CacheReuse {

        @Test
        fun `reuses previous entry when metadata matches`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val progress = CountingUploadProgress()
            val uploader = FileUploader(mockWriter, progress)

            val previousEntry = DirEntry(
                name = "test.txt",
                type = EntryType.FILE,
                permissions = 420,
                fileSize = 100,
                modTime = Instant.parse("2025-01-01T00:00:00Z"),
                objectId = "previous-object-id"
            )

            val mockFile = MockFile(
                name = "test.txt",
                size = 100,
                modTime = Instant.parse("2025-01-01T00:00:00Z"),
                mode = 420,
                content = ByteArray(100)
            )

            val result = uploader.processFile(mockFile, "test.txt", previousEntry)

            assertEquals("previous-object-id", result.objectId)
            assertEquals(1, progress.snapshot().totalCachedFiles)
            assertEquals(0, progress.snapshot().totalHashedFiles)
        }

        @Test
        fun `uploads when size differs`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val progress = CountingUploadProgress()
            val uploader = FileUploader(mockWriter, progress)

            val previousEntry = DirEntry(
                name = "test.txt",
                type = EntryType.FILE,
                permissions = 420,
                fileSize = 50, // Different size
                modTime = Instant.parse("2025-01-01T00:00:00Z"),
                objectId = "previous-object-id"
            )

            val mockFile = MockFile(
                name = "test.txt",
                size = 100, // Different size
                modTime = Instant.parse("2025-01-01T00:00:00Z"),
                mode = 420,
                content = ByteArray(100)
            )

            val result = uploader.processFile(mockFile, "test.txt", previousEntry)

            assertNotNull(result.objectId)
            assertTrue(result.objectId != "previous-object-id")
        }

        @Test
        fun `uploads when mod time differs`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val progress = CountingUploadProgress()
            val uploader = FileUploader(mockWriter, progress)

            val previousEntry = DirEntry(
                name = "test.txt",
                type = EntryType.FILE,
                permissions = 420,
                fileSize = 100,
                modTime = Instant.parse("2025-01-01T00:00:00Z"),
                objectId = "previous-object-id"
            )

            val mockFile = MockFile(
                name = "test.txt",
                size = 100,
                modTime = Instant.parse("2025-01-02T00:00:00Z"), // Different time
                mode = 420,
                content = ByteArray(100)
            )

            val result = uploader.processFile(mockFile, "test.txt", previousEntry)

            assertNotNull(result.objectId)
            assertTrue(result.objectId != "previous-object-id")
        }
    }

    @Nested
    inner class ForceHash {

        @Test
        fun `always uploads when force hash is 100 percent`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val progress = CountingUploadProgress()
            val uploader = FileUploader(mockWriter, progress, forceHashPercentage = 100)

            val previousEntry = DirEntry(
                name = "test.txt",
                type = EntryType.FILE,
                permissions = 420,
                fileSize = 100,
                modTime = Instant.parse("2025-01-01T00:00:00Z"),
                objectId = "previous-object-id"
            )

            val mockFile = MockFile(
                name = "test.txt",
                size = 100,
                modTime = Instant.parse("2025-01-01T00:00:00Z"),
                mode = 420,
                content = ByteArray(100)
            )

            val result = uploader.processFile(mockFile, "test.txt", previousEntry)

            // Should upload even though metadata matches
            assertTrue(result.objectId != "previous-object-id")
        }
    }

    @Nested
    inner class DirectoryManifests {

        @Test
        fun `uploads directory manifest as JSON`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val uploader = FileUploader(mockWriter, NullUploadProgress())

            val manifest = DirManifest(
                entries = listOf(
                    DirEntry(name = "file.txt", type = EntryType.FILE, fileSize = 100)
                )
            )

            val objectId = uploader.uploadDirectoryManifest(manifest)

            assertNotNull(objectId)
            assertTrue(mockWriter.writtenObjects.isNotEmpty())

            // Verify JSON contains expected content
            val writtenData = mockWriter.writtenObjects.first()
            val json = writtenData.toString(Charsets.UTF_8)
            assertTrue(json.contains("kopia:directory"))
            assertTrue(json.contains("file.txt"))
        }
    }

    @Nested
    inner class SymlinkProcessing {

        @Test
        fun `processes symlinks`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val uploader = FileUploader(mockWriter, NullUploadProgress())

            val mockSymlink = MockSymlink(
                name = "link.txt",
                target = "/path/to/target",
                modTime = Instant.now(),
                mode = 511
            )

            val result = uploader.processSymlink(mockSymlink, "link.txt", null)

            assertEquals("link.txt", result.name)
            assertEquals(EntryType.SYMLINK, result.type)
            assertNotNull(result.objectId)
        }
    }

    @Nested
    inner class CompressionPolicy {

        @Test
        fun `applies compression policy to files`() = runBlocking {
            val policy = CompressionPolicy(
                compressorName = "zstd",
                minSize = 10
            )
            val mockWriter = MockRepositoryWriter()
            val uploader = FileUploader(
                mockWriter,
                NullUploadProgress(),
                compressionPolicy = policy
            )

            val mockFile = MockFile(
                name = "test.txt",
                size = 100,
                modTime = Instant.now(),
                mode = 420,
                content = ByteArray(100)
            )

            val result = uploader.processFile(mockFile, "test.txt", null)

            assertNotNull(result.objectId)
        }
    }

    /**
     * Mock file implementation for testing.
     */
    private class MockFile(
        override val name: String,
        override val size: Long,
        override val modTime: Instant,
        override val mode: Int,
        private val content: ByteArray
    ) : org.kopiaKt.snapshot.fs.File {
        override val type = org.kopiaKt.snapshot.fs.EntryType.FILE
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun open(): InputStream = ByteArrayInputStream(content)
        override fun close() {}
    }

    /**
     * Mock symlink implementation for testing.
     */
    private class MockSymlink(
        override val name: String,
        private val target: String,
        override val modTime: Instant,
        override val mode: Int
    ) : org.kopiaKt.snapshot.fs.Symlink {
        override val type = org.kopiaKt.snapshot.fs.EntryType.SYMLINK
        override val size: Long = 0
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun readlink(): String = target
        override suspend fun resolve(): org.kopiaKt.snapshot.fs.Entry {
            throw UnsupportedOperationException("Mock")
        }
        override fun close() {}
    }

    /**
     * Mock repository writer for testing.
     */
    private class MockRepositoryWriter : org.kopiaKt.core.repository.RepositoryWriter {
        val writtenObjects = mutableListOf<ByteArray>()
        private var nextId = 1

        // Generate valid hex object ID format (ObjectId requires hex content hash)
        private fun nextObjectId(): org.kopiaKt.core.content.ObjectId {
            // Generate a 32-character hex string (16 bytes)
            val hexId = String.format("%032x", nextId++)
            return org.kopiaKt.core.content.ObjectId.parse(hexId)
        }

        override fun newObjectWriter(options: org.kopiaKt.core.`object`.ObjectWriterOptions): org.kopiaKt.core.`object`.ObjectWriter {
            return MockObjectWriter { data ->
                writtenObjects.add(data)
                nextObjectId()
            }
        }

        override suspend fun writeObject(data: ByteArray, options: org.kopiaKt.core.`object`.ObjectWriterOptions): org.kopiaKt.core.content.ObjectId {
            writtenObjects.add(data)
            return nextObjectId()
        }

        override suspend fun concatenateObjects(objectIds: List<org.kopiaKt.core.content.ObjectId>, options: org.kopiaKt.core.repository.ConcatenateOptions): org.kopiaKt.core.content.ObjectId {
            TODO("Not needed for test")
        }

        override suspend fun <T> putManifest(labels: Map<String, String>, payload: T, serializer: kotlinx.serialization.KSerializer<T>): org.kopiaKt.core.manifest.ManifestId {
            TODO("Not needed for test")
        }

        override suspend fun <T> replaceManifests(labels: Map<String, String>, payload: T, serializer: kotlinx.serialization.KSerializer<T>): org.kopiaKt.core.manifest.ManifestId {
            TODO("Not needed for test")
        }

        override suspend fun deleteManifest(id: org.kopiaKt.core.manifest.ManifestId) {
            TODO("Not needed for test")
        }

        override fun onSuccessfulFlush(callback: suspend (org.kopiaKt.core.repository.RepositoryWriter) -> Unit) {
            TODO("Not needed for test")
        }

        override suspend fun flush() {}

        // Read-only methods (from Repository)
        override fun openObject(objectId: org.kopiaKt.core.content.ObjectId): org.kopiaKt.core.`object`.ObjectReader {
            TODO("Not needed for test")
        }

        override suspend fun readObject(objectId: org.kopiaKt.core.content.ObjectId): ByteArray {
            TODO("Not needed for test")
        }

        override suspend fun verifyObject(objectId: org.kopiaKt.core.content.ObjectId): List<org.kopiaKt.core.content.ContentId> {
            TODO("Not needed for test")
        }

        override suspend fun <T> getManifest(id: org.kopiaKt.core.manifest.ManifestId, serializer: kotlinx.serialization.KSerializer<T>): Pair<T, org.kopiaKt.core.manifest.EntryMetadata> {
            TODO("Not needed for test")
        }

        override suspend fun findManifests(labels: Map<String, String>): List<org.kopiaKt.core.manifest.EntryMetadata> {
            TODO("Not needed for test")
        }

        override suspend fun contentInfo(contentId: org.kopiaKt.core.content.ContentId): org.kopiaKt.core.content.ContentInfo? {
            TODO("Not needed for test")
        }

        override fun time(): Instant = Instant.now()

        override fun clientOptions(): org.kopiaKt.core.repository.ClientOptions {
            return org.kopiaKt.core.repository.ClientOptions()
        }

        override suspend fun newWriter(options: org.kopiaKt.core.repository.WriteSessionOptions): org.kopiaKt.core.repository.RepositoryWriter {
            TODO("Not needed for test")
        }

        override fun updateDescription(description: String) {}

        override suspend fun refresh() {}

        override fun close() {}
    }

    /**
     * Mock object writer for testing.
     */
    private class MockObjectWriter(
        private val onResult: (ByteArray) -> org.kopiaKt.core.content.ObjectId
    ) : org.kopiaKt.core.`object`.ObjectWriter {
        private val buffer = java.io.ByteArrayOutputStream()

        override suspend fun write(data: ByteArray): Int {
            buffer.write(data)
            return data.size
        }

        override suspend fun checkpoint(): org.kopiaKt.core.content.ObjectId {
            return org.kopiaKt.core.content.ObjectId.Empty
        }

        override suspend fun result(): org.kopiaKt.core.content.ObjectId {
            return onResult(buffer.toByteArray())
        }

        override suspend fun close() {}
    }
}
