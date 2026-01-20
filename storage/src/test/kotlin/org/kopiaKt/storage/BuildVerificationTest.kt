package org.kopiaKt.storage

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Build verification tests for the storage module.
 */
class BuildVerificationTest {

    private lateinit var tempDir: Path
    private lateinit var storage: FilesystemBlobStorage

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("kopiaKt-test-")
        storage = FilesystemBlobStorage.create(tempDir)
    }

    @AfterEach
    fun tearDown() {
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `storage can be created`() {
        assertThat(storage.displayName()).isEqualTo(tempDir.toString())
        assertThat(storage.connectionInfo().type).isEqualTo("filesystem")
    }

    @Test
    fun `put and get blob works`() = runBlocking {
        val blobId = BlobId("test-blob-123")
        val data = "Hello, KopiaKt!".toByteArray()

        storage.putBlob(blobId, data)

        val retrieved = storage.getBlob(blobId)
        assertThat(retrieved).isEqualTo(data)
    }

    @Test
    fun `get nonexistent blob throws BlobNotFoundException`() = runBlocking {
        val blobId = BlobId("nonexistent")

        assertThrows<BlobNotFoundException> {
            runBlocking { storage.getBlob(blobId) }
        }
    }

    @Test
    fun `getBlobMetadata returns metadata for existing blob`() = runBlocking {
        val blobId = BlobId("metadata-test")
        val data = ByteArray(1024) { it.toByte() }

        storage.putBlob(blobId, data)

        val metadata = storage.getBlobMetadata(blobId)
        assertThat(metadata).isNotNull()
        assertThat(metadata!!.blobId).isEqualTo(blobId)
        assertThat(metadata.length).isEqualTo(1024)
    }

    @Test
    fun `getBlobMetadata returns null for nonexistent blob`() = runBlocking {
        val metadata = storage.getBlobMetadata(BlobId("nonexistent"))
        assertThat(metadata).isNull()
    }

    @Test
    fun `deleteBlob removes blob`() = runBlocking {
        val blobId = BlobId("delete-test")
        val data = "to be deleted".toByteArray()

        storage.putBlob(blobId, data)
        assertThat(storage.getBlobMetadata(blobId)).isNotNull()

        storage.deleteBlob(blobId)
        assertThat(storage.getBlobMetadata(blobId)).isNull()
    }

    @Test
    fun `listBlobs returns blobs with matching prefix`() = runBlocking {
        storage.putBlob(BlobId("pack-001"), ByteArray(10))
        storage.putBlob(BlobId("pack-002"), ByteArray(20))
        storage.putBlob(BlobId("index-001"), ByteArray(30))

        val packBlobs = storage.listBlobs("pack-").toList()
        assertThat(packBlobs.map { it.blobId.value }).containsExactly("pack-001", "pack-002")

        val indexBlobs = storage.listBlobs("index-").toList()
        assertThat(indexBlobs.map { it.blobId.value }).containsExactly("index-001")

        val allBlobs = storage.listBlobs("").toList()
        assertThat(allBlobs).hasSize(3)
    }

    @Test
    fun `putBlob with dontOverwrite does not overwrite existing blob`() = runBlocking {
        val blobId = BlobId("no-overwrite-test")
        val originalData = "original".toByteArray()
        val newData = "new".toByteArray()

        storage.putBlob(blobId, originalData)
        storage.putBlob(blobId, newData, PutBlobOptions(dontOverwrite = true))

        val retrieved = storage.getBlob(blobId)
        assertThat(retrieved).isEqualTo(originalData)
    }

    @Test
    fun `putBlob without dontOverwrite overwrites existing blob`() = runBlocking {
        val blobId = BlobId("overwrite-test")
        val originalData = "original".toByteArray()
        val newData = "new".toByteArray()

        storage.putBlob(blobId, originalData)
        storage.putBlob(blobId, newData)

        val retrieved = storage.getBlob(blobId)
        assertThat(retrieved).isEqualTo(newData)
    }

    @Test
    fun `getBlob with offset and length returns partial data`() = runBlocking {
        val blobId = BlobId("partial-test")
        val data = "Hello, World!".toByteArray()

        storage.putBlob(blobId, data)

        // Get "World"
        val partial = storage.getBlob(blobId, offset = 7, length = 5)
        assertThat(String(partial)).isEqualTo("World")
    }

    @Test
    fun `getBlob with offset and no length returns rest of data`() = runBlocking {
        val blobId = BlobId("partial-rest-test")
        val data = "Hello, World!".toByteArray()

        storage.putBlob(blobId, data)

        // Get "World!"
        val partial = storage.getBlob(blobId, offset = 7)
        assertThat(String(partial)).isEqualTo("World!")
    }
}
