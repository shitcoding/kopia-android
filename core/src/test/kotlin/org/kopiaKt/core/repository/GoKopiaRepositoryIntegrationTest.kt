package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.PutBlobOptions
import java.io.File
import java.time.Instant

/**
 * Simple file-based blob storage for testing.
 * Reads blobs from a directory structure matching Kopia's format.
 */
class TestFilesystemBlobStorage(private val rootDir: File) : BlobStorage {
    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray {
        val file = findBlobFile(blobId)
        if (file == null || !file.exists()) {
            throw BlobNotFoundException(blobId)
        }
        val data = file.readBytes()
        return when {
            offset == 0L && length == -1L -> data
            length == -1L -> data.sliceArray(offset.toInt() until data.size)
            else -> data.sliceArray(offset.toInt() until (offset + length).toInt())
        }
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? {
        val file = findBlobFile(blobId)
        if (file == null || !file.exists()) {
            return null
        }
        return BlobMetadata(
            blobId = blobId,
            length = file.length(),
            timestamp = Instant.ofEpochMilli(file.lastModified()),
        )
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        listBlobsRecursive(rootDir, prefix).forEach { emit(it) }
    }

    private fun listBlobsRecursive(dir: File, prefix: String): List<BlobMetadata> {
        val results = mutableListOf<BlobMetadata>()
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                results.addAll(listBlobsRecursive(file, prefix))
            } else if (file.isFile) {
                val blobId = fileToBlobId(file)
                if (blobId != null && blobId.value.startsWith(prefix)) {
                    results.add(
                        BlobMetadata(
                            blobId = blobId,
                            length = file.length(),
                            timestamp = Instant.ofEpochMilli(file.lastModified()),
                        ),
                    )
                }
            }
        }
        return results
    }

    private fun fileToBlobId(file: File): BlobId? {
        val name = file.name
        // Kopia blob files end with .f
        if (!name.endsWith(".f")) return null

        // The blob ID is derived from the file path
        // For format blobs like kopia.repository.f -> kopia.repository
        // For sharded blobs like p/ac0/17c27f... -> pac017c27f...
        val relativePath = file.relativeTo(rootDir).path
        val parts = relativePath.split(File.separator)

        return when {
            parts.size == 1 -> {
                // Top-level file like kopia.repository.f
                BlobId(name.removeSuffix(".f"))
            }
            parts.size >= 2 -> {
                // Sharded file like p/ac0/17c27f...f or x/n0_/...
                val prefix = parts[0]
                val fileName = parts.last().removeSuffix(".f")
                // Reconstruct blob ID: prefix + shard + filename
                val shard = if (parts.size >= 3) parts[1] else ""
                BlobId(prefix + shard + fileName)
            }
            else -> null
        }
    }

    private fun findBlobFile(id: BlobId): File? {
        val name = id.value

        // Check for top-level files (format blobs)
        val topLevel = File(rootDir, "$name.f")
        if (topLevel.exists()) return topLevel

        // For sharded blobs, extract prefix and shard
        if (name.isEmpty()) return null

        val prefix = name[0]
        val rest = name.substring(1)

        // Shard is typically first 3 chars after prefix
        val shardDir = if (rest.length >= 3) rest.substring(0, 3) else rest
        val fileName = if (rest.length > 3) rest.substring(3) else ""

        // Handle special prefixes like x/n0_
        val prefixDir = File(rootDir, prefix.toString())
        if (!prefixDir.exists()) return null

        // Try to find the file
        prefixDir.listFiles()?.forEach { shard ->
            if (shard.isDirectory) {
                shard.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".f")) {
                        val fileBlobId = fileToBlobId(file)
                        if (fileBlobId == id) {
                            return file
                        }
                    }
                }
            }
        }

        return null
    }

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions): Unit = throw UnsupportedOperationException("Read-only storage")

    override suspend fun deleteBlob(blobId: BlobId): Unit = throw UnsupportedOperationException("Read-only storage")

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo("filesystem", mapOf("path" to rootDir.absolutePath))

    override fun displayName(): String = "Filesystem: ${rootDir.absolutePath}"
}

/** Kopia's master key and HMAC secret are both 256-bit. */
private const val KEY_LENGTH_BYTES = 32

/**
 * Integration tests for reading a repository created by Go Kopia.
 *
 * These tests verify that KopiaKt can correctly read repositories created
 * by the reference Go implementation of Kopia.
 */
class GoKopiaRepositoryIntegrationTest {

    private val testRepoPath = "../testvectors/test_repository"
    private val testPassword = "test123"

    private fun testRepoExists(): Boolean {
        val repoDir = File(testRepoPath)
        return repoDir.exists() && File(repoDir, "kopia.repository.f").exists()
    }

    @Test
    @DisplayName("Read manifests from Go Kopia repository")
    fun `read manifests from go kopia repository`(): Unit = runTest {
        val repoDir = File(testRepoPath)
        assumeTrue(testRepoExists()) {
            "Go Kopia test repository not found at $testRepoPath — run create_test_repo.sh first"
        }

        val storage = TestFilesystemBlobStorage(repoDir)

        // Read the format blob and unwrap the repository config the same way open() does,
        // so a break in the key-derivation path is attributed here rather than to open().
        val formatBlob = storage.getBlob(org.kopiaKt.core.blob.BlobId("kopia.repository"), 0, -1)
        val formatJson = org.kopiaKt.core.format.KopiaRepositoryJson.parse(formatBlob)
        assertThat(formatJson.uniqueID).isNotEmpty()

        val formatEncryptionKey = formatJson.deriveFormatEncryptionKeyFromPassword(testPassword)
        val config = formatJson.decryptRepositoryConfig(formatEncryptionKey)
        assertThat(config.masterKey).hasLength(KEY_LENGTH_BYTES)
        assertThat(config.secret).hasLength(KEY_LENGTH_BYTES)
        assertThat(config.hash).isNotEmpty()
        assertThat(config.encryption).isNotEmpty()

        val repo = DirectRepositoryImpl.open(storage, testPassword)
        repo.refresh()

        val snapshots = repo.findManifests(mapOf("type" to "snapshot"))
        assertThat(snapshots).isNotEmpty()

        repo.close()
    }

    @Test
    @DisplayName("Find all manifest types in Go Kopia repository")
    fun `find all manifest types`(): Unit = runTest {
        val repoDir = File(testRepoPath)
        assumeTrue(testRepoExists()) {
            "Go Kopia test repository not found at $testRepoPath — run create_test_repo.sh first"
        }

        val storage = TestFilesystemBlobStorage(repoDir)

        val repo = DirectRepositoryImpl.open(storage, testPassword)
        repo.refresh()

        // Find all manifests (empty labels matches all)
        val allManifests = repo.findManifests(emptyMap())
        assertThat(allManifests).isNotEmpty()

        // The fixture repository is created by Go kopia, so at least the snapshot type must
        // round-trip through the manifest labels.
        val byType = allManifests.groupBy { it.labels["type"] }
        assertThat(byType.keys).contains("snapshot")

        repo.close()
    }
}
