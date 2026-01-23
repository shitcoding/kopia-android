package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
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
            timestamp = Instant.ofEpochMilli(file.lastModified())
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
                    results.add(BlobMetadata(
                        blobId = blobId,
                        length = file.length(),
                        timestamp = Instant.ofEpochMilli(file.lastModified())
                    ))
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

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
        throw UnsupportedOperationException("Read-only storage")
    }

    override suspend fun deleteBlob(blobId: BlobId) {
        throw UnsupportedOperationException("Read-only storage")
    }

    override fun connectionInfo(): ConnectionInfo {
        return ConnectionInfo("filesystem", mapOf("path" to rootDir.absolutePath))
    }

    override fun displayName(): String = "Filesystem: ${rootDir.absolutePath}"
}

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
    fun `read manifests from go kopia repository`() = runTest {
        val repoDir = File(testRepoPath)
        if (!repoDir.exists()) {
            println("Test repository not found at $testRepoPath. Run create_test_repo.sh first.")
            return@runTest
        }

        println("DEBUG: Opening repository at ${repoDir.absolutePath}")

        val storage = TestFilesystemBlobStorage(repoDir)

        try {
            // First, manually read the format blob and decrypt to see the masterKey
            val formatBlob = storage.getBlob(org.kopiaKt.core.blob.BlobId("kopia.repository"), 0, -1)
            val formatJson = org.kopiaKt.core.format.KopiaRepositoryJson.parse(formatBlob)
            println("DEBUG: Format uniqueID: ${formatJson.uniqueID.joinToString("") { "%02x".format(it) }}")
            println("DEBUG: Format keyAlgo: ${formatJson.keyDerivationAlgorithm}")

            // Derive the format encryption key
            val formatEncryptionKey = formatJson.deriveFormatEncryptionKeyFromPassword(testPassword)
            println("DEBUG: formatEncryptionKey: ${formatEncryptionKey.joinToString("") { "%02x".format(it) }}")

            // Decrypt the config
            val config = formatJson.decryptRepositoryConfig(formatEncryptionKey)
            println("DEBUG: config.masterKey: ${config.masterKey.joinToString("") { "%02x".format(it) }}")
            println("DEBUG: config.secret (hmac): ${config.secret.joinToString("") { "%02x".format(it) }}")
            println("DEBUG: config.hash: ${config.hash}")
            println("DEBUG: config.encryption: ${config.encryption}")

            val repo = DirectRepositoryImpl.open(storage, testPassword)
            println("DEBUG: Repository opened successfully")

            // Refresh to load manifests
            repo.refresh()
            println("DEBUG: Repository refreshed")

            // Find all snapshot manifests
            val snapshots = repo.findManifests(mapOf("type" to "snapshot"))
            println("DEBUG: Found ${snapshots.size} snapshot manifests")

            for (snapshot in snapshots) {
                println("DEBUG: Snapshot ID=${snapshot.id}, labels=${snapshot.labels}, modTime=${snapshot.modTime}")
            }

            assertThat(snapshots).isNotEmpty()

            repo.close()
        } catch (e: Exception) {
            println("DEBUG: Exception: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @Test
    @DisplayName("Find all manifest types in Go Kopia repository")
    fun `find all manifest types`() = runTest {
        val repoDir = File(testRepoPath)
        if (!repoDir.exists()) {
            println("Test repository not found at $testRepoPath. Run create_test_repo.sh first.")
            return@runTest
        }

        val storage = TestFilesystemBlobStorage(repoDir)

        try {
            val repo = DirectRepositoryImpl.open(storage, testPassword)
            repo.refresh()

            // Find all manifests (empty labels matches all)
            val allManifests = repo.findManifests(emptyMap())
            println("DEBUG: Found ${allManifests.size} total manifests")

            // Group by type
            val byType = allManifests.groupBy { it.labels["type"] }
            for ((type, manifests) in byType) {
                println("DEBUG: Type '$type': ${manifests.size} manifests")
            }

            assertThat(allManifests).isNotEmpty()

            repo.close()
        } catch (e: Exception) {
            println("DEBUG: Exception: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
