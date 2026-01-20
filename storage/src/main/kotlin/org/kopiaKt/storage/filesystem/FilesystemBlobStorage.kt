package org.kopiaKt.storage.filesystem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobVolume
import org.kopiaKt.core.blob.Capacity
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.PutBlobOptions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Filesystem-based blob storage implementation.
 *
 * This is the primary storage backend for local repositories and testing.
 * Blobs are stored in a sharded directory structure to avoid too many
 * files in a single directory.
 */
class FilesystemBlobStorage(
    private val basePath: Path,
    private val readOnly: Boolean = false
) : BlobStorage, BlobVolume {

    /**
     * Number of characters from blob ID to use for sharding.
     */
    private val shardLength = 1

    init {
        require(basePath.isDirectory()) { "Base path must be a directory: $basePath" }
    }

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val blobPath = getBlobPath(blobId)

            if (!blobPath.exists()) {
                throw BlobNotFoundException(blobId)
            }

            val bytes = blobPath.readBytes()

            if (offset == 0L && length == -1L) {
                bytes
            } else {
                val actualLength = if (length == -1L) bytes.size - offset.toInt() else length.toInt()
                bytes.copyOfRange(offset.toInt(), offset.toInt() + actualLength)
            }
        }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? =
        withContext(Dispatchers.IO) {
            val blobPath = getBlobPath(blobId)

            if (!blobPath.exists()) {
                return@withContext null
            }

            BlobMetadata(
                blobId = blobId,
                length = Files.size(blobPath),
                timestamp = blobPath.getLastModifiedTime().toInstant()
            )
        }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        val shardDirs = basePath.listDirectoryEntries()

        for (shardDir in shardDirs) {
            if (!shardDir.isDirectory()) continue

            val blobs = shardDir.listDirectoryEntries()
            for (blobPath in blobs) {
                val blobName = blobPath.name
                if (blobName.startsWith(prefix)) {
                    emit(
                        BlobMetadata(
                            blobId = BlobId(blobName),
                            length = Files.size(blobPath),
                            timestamp = blobPath.getLastModifiedTime().toInstant()
                        )
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) =
        withContext(Dispatchers.IO) {
            val blobPath = getBlobPath(blobId)

            if (options.dontOverwrite && blobPath.exists()) {
                return@withContext
            }

            // Ensure shard directory exists
            blobPath.parent.createDirectories()

            // Write atomically using temp file + rename
            val tempPath = blobPath.resolveSibling("${blobPath.name}.tmp.${System.nanoTime()}")

            try {
                Files.write(
                    tempPath,
                    data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                Files.move(tempPath, blobPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                tempPath.deleteIfExists()
                throw e
            }
        }

    override suspend fun deleteBlob(blobId: BlobId) = withContext(Dispatchers.IO) {
        val blobPath = getBlobPath(blobId)
        blobPath.deleteIfExists()
        Unit
    }

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = "filesystem",
        config = mapOf("path" to basePath.toString())
    )

    override fun displayName(): String = basePath.toString()

    override fun isReadOnly(): Boolean = readOnly

    override suspend fun getCapacity(): Capacity = withContext(Dispatchers.IO) {
        val fileStore = Files.getFileStore(basePath)
        Capacity(
            sizeBytes = fileStore.totalSpace,
            freeBytes = fileStore.usableSpace
        )
    }

    /**
     * Gets the filesystem path for a blob ID.
     *
     * Blobs are sharded by the first N characters of their ID.
     */
    private fun getBlobPath(blobId: BlobId): Path {
        val id = blobId.value
        val shard = if (id.length > shardLength) id.substring(0, shardLength) else id
        return basePath.resolve(shard).resolve(id)
    }

    companion object {
        /**
         * Creates a new filesystem storage at the given path.
         *
         * @param path Path to the storage directory
         * @param create If true, create the directory if it doesn't exist
         * @param readOnly If true, the storage will be in read-only mode
         */
        fun create(path: Path, create: Boolean = false, readOnly: Boolean = false): FilesystemBlobStorage {
            if (create && !path.exists()) {
                path.createDirectories()
            }
            return FilesystemBlobStorage(path, readOnly)
        }
    }
}
