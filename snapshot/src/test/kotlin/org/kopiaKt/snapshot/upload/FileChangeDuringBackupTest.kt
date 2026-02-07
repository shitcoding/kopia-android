package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.EntryType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant

import com.google.common.truth.Truth.assertThat

/**
 * Tests for files that change between the scan phase and the upload phase of a backup.
 *
 * During a backup, the TreeWalker first scans directory entries (collecting metadata),
 * then FileUploader reads and uploads the actual file content. Files may be modified,
 * deleted, grown, or truncated in that window. These tests verify FileUploader behavior
 * under those race conditions.
 */
@DisplayName("Files Changing During Backup")
class FileChangeDuringBackupTest {

    @Nested
    @DisplayName("Content Change Detection")
    inner class ContentChangeDetection {

        @Test
        fun `should detect file content change between scan and upload when forceHash is 100`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val progress = CountingUploadProgress()
            val uploader = FileUploader(mockWriter, progress, forceHashPercentage = 100)

            // Previous entry from a prior snapshot: 100 bytes, specific modTime, permissions
            val previousEntry = DirEntry(
                name = "data.bin",
                type = EntryType.FILE,
                permissions = 420,
                fileSize = 100,
                modTime = Instant.parse("2025-06-01T12:00:00Z"),
                objectId = "previous-object-id"
            )

            // File on disk: metadata is IDENTICAL to previousEntry (size, modTime, permissions
            // all match), but the actual content is different (all 0xFF instead of all 0x00).
            // Without forceHash, canReuseEntry would return true and skip re-uploading.
            val changedContent = ByteArray(100) { 0xFF.toByte() }
            val mockFile = MockFile(
                name = "data.bin",
                size = 100,
                modTime = Instant.parse("2025-06-01T12:00:00Z"),
                mode = 420,
                content = changedContent
            )

            val result = uploader.processFile(mockFile, "data.bin", previousEntry)

            // With forceHash=100, the file is always re-uploaded, producing a new objectId
            assertThat(result.objectId).isNotNull()
            assertThat(result.objectId).isNotEqualTo("previous-object-id")
            // The writer should have received the actual file content
            assertThat(mockWriter.writtenObjects).hasSize(1)
            assertThat(mockWriter.writtenObjects[0]).isEqualTo(changedContent)
        }
    }

    @Nested
    @DisplayName("File Deletion Between Scan and Upload")
    inner class FileDeletionDuringBackup {

        @Test
        fun `should handle file deletion between scan and upload`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val progress = CountingUploadProgress()
            // forceHash=100 to ensure we always attempt upload (not reuse cache)
            val uploader = FileUploader(mockWriter, progress, forceHashPercentage = 100)

            val previousEntry = DirEntry(
                name = "deleted.txt",
                type = EntryType.FILE,
                permissions = 420,
                fileSize = 50,
                modTime = Instant.parse("2025-06-01T12:00:00Z"),
                objectId = "old-object-id"
            )

            // File that throws IOException when opened, simulating deletion after scan
            val deletedFile = DeletedMockFile(
                name = "deleted.txt",
                size = 50,
                modTime = Instant.parse("2025-06-01T12:00:00Z"),
                mode = 420
            )

            // processFile calls file.open() which throws IOException -- this should propagate
            assertThrows<IOException> {
                uploader.processFile(deletedFile, "deleted.txt", previousEntry)
            }

            // Nothing should have been written to the repository
            assertThat(mockWriter.writtenObjects).isEmpty()
        }
    }

    @Nested
    @DisplayName("File Size Changes Between Scan and Upload")
    inner class FileSizeChanges {

        @Test
        fun `should handle file growth between scan and upload`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val progress = CountingUploadProgress()
            val uploader = FileUploader(mockWriter, progress, forceHashPercentage = 100)

            val previousEntry = DirEntry(
                name = "growing.log",
                type = EntryType.FILE,
                permissions = 420,
                fileSize = 100,
                modTime = Instant.parse("2025-06-01T12:00:00Z"),
                objectId = "old-object-id"
            )

            // MockFile reports size=100 (the scan-time size) but open() returns 200 bytes
            // of actual content (the file grew between scan and upload).
            val grownContent = ByteArray(200) { (it % 256).toByte() }
            val grownFile = MockFile(
                name = "growing.log",
                size = 100, // scan-time size
                modTime = Instant.parse("2025-06-01T12:00:00Z"),
                mode = 420,
                content = grownContent
            )

            val result = uploader.processFile(grownFile, "growing.log", previousEntry)

            // The file should be uploaded successfully with the actual (larger) content
            assertThat(result.objectId).isNotNull()
            assertThat(result.objectId).isNotEqualTo("old-object-id")
            assertThat(mockWriter.writtenObjects).hasSize(1)
            assertThat(mockWriter.writtenObjects[0]).isEqualTo(grownContent)
        }

        @Test
        fun `should handle file truncation between scan and upload`() = runBlocking {
            val mockWriter = MockRepositoryWriter()
            val progress = CountingUploadProgress()
            val uploader = FileUploader(mockWriter, progress, forceHashPercentage = 100)

            val previousEntry = DirEntry(
                name = "truncated.dat",
                type = EntryType.FILE,
                permissions = 420,
                fileSize = 200,
                modTime = Instant.parse("2025-06-01T12:00:00Z"),
                objectId = "old-object-id"
            )

            // MockFile reports size=200 (the scan-time size) but open() returns only 50 bytes
            // (the file was truncated between scan and upload).
            val truncatedContent = ByteArray(50) { 0xAB.toByte() }
            val truncatedFile = MockFile(
                name = "truncated.dat",
                size = 200, // scan-time size
                modTime = Instant.parse("2025-06-01T12:00:00Z"),
                mode = 420,
                content = truncatedContent
            )

            val result = uploader.processFile(truncatedFile, "truncated.dat", previousEntry)

            // The file should be uploaded successfully with the actual (smaller) content
            assertThat(result.objectId).isNotNull()
            assertThat(result.objectId).isNotEqualTo("old-object-id")
            assertThat(mockWriter.writtenObjects).hasSize(1)
            assertThat(mockWriter.writtenObjects[0]).isEqualTo(truncatedContent)
        }
    }

    // -- Mock implementations --

    /**
     * Mock file that returns content from a byte array.
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
     * Mock file that throws IOException on open(), simulating a file deleted after scan.
     */
    private class DeletedMockFile(
        override val name: String,
        override val size: Long,
        override val modTime: Instant,
        override val mode: Int
    ) : org.kopiaKt.snapshot.fs.File {
        override val type = org.kopiaKt.snapshot.fs.EntryType.FILE
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun open(): InputStream {
            throw IOException("File not found: $name (deleted between scan and upload)")
        }

        override fun close() {}
    }

    /**
     * Mock repository writer that records written objects.
     */
    private class MockRepositoryWriter : org.kopiaKt.core.repository.RepositoryWriter {
        val writtenObjects = mutableListOf<ByteArray>()
        private var nextId = 1

        private fun nextObjectId(): org.kopiaKt.core.content.ObjectId {
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
     * Mock object writer that buffers data and delegates to a callback on result().
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
