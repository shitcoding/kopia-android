package org.kopiaKt.storage.filesystem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobVolume
import org.kopiaKt.core.blob.Capacity
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.PutBlobOptions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Filesystem-based blob storage implementation.
 *
 * This is the primary storage backend for local repositories and testing.
 * Blobs are stored in a sharded directory structure to avoid too many
 * files in a single directory.
 *
 * Compatible with Go Kopia filesystem storage format:
 * - All blob files have ".f" suffix
 * - Short blob IDs (< maxNonShardedLength) are stored at root
 * - Longer blob IDs use multi-level sharding (default [1, 3])
 */
class FilesystemBlobStorage private constructor(
    private val basePath: Path,
    private val readOnly: Boolean,
    private val shards: List<Int>,
    private val maxNonShardedLength: Int,
) : BlobStorage,
    BlobVolume {

    init {
        require(basePath.isDirectory()) { "Base path must be a directory: $basePath" }
    }

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray = withContext(Dispatchers.IO) {
        val blobPath = getBlobPath(blobId)

        if (!blobPath.exists()) {
            throw BlobNotFoundException(blobId)
        }

        // If reading the full blob, use readBytes()
        if (offset == 0L && length == -1L) {
            return@withContext blobPath.readBytes()
        }

        // Validate parameters first
        if (offset < 0) {
            throw InvalidBlobRangeException("Offset must be non-negative: $offset")
        }
        if (length < -1) {
            throw InvalidBlobRangeException("Length must be >= -1: $length")
        }

        // For partial reads, use FileInputStream to avoid loading entire file into memory
        val fileSize = Files.size(blobPath)

        // Check offset bounds before computing actualLength
        if (offset > fileSize) {
            throw InvalidBlobRangeException(
                "Offset beyond end of file: offset=$offset, fileSize=$fileSize",
            )
        }

        val actualLength = if (length == -1L) {
            (fileSize - offset).toInt()
        } else {
            length.toInt()
        }

        // Validate final bounds
        if (offset + actualLength > fileSize) {
            throw InvalidBlobRangeException(
                "Read beyond end of file: offset=$offset, length=$actualLength, fileSize=$fileSize",
            )
        }

        // Read only the requested portion using FileInputStream
        Files.newInputStream(blobPath).use { input ->
            // Skip to offset - must skip exact amount
            var skipped = 0L
            while (skipped < offset) {
                val skip = input.skip(offset - skipped)
                if (skip == 0L) {
                    throw InvalidBlobRangeException(
                        "Failed to skip to offset $offset (skipped only $skipped bytes)",
                    )
                }
                skipped += skip
            }

            // Read exact amount - must read full buffer
            val buffer = ByteArray(actualLength)
            var totalRead = 0
            while (totalRead < actualLength) {
                val read = input.read(buffer, totalRead, actualLength - totalRead)
                if (read == -1) {
                    throw InvalidBlobRangeException(
                        "Unexpected EOF: requested $actualLength bytes, read only $totalRead bytes",
                    )
                }
                totalRead += read
            }
            buffer
        }
    }

    /**
     * Checks if a blob exists.
     */
    suspend fun contains(blobId: BlobId): Boolean = withContext(Dispatchers.IO) {
        getBlobPath(blobId).exists()
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? = withContext(Dispatchers.IO) {
        val blobPath = getBlobPath(blobId)

        if (!blobPath.exists()) {
            return@withContext null
        }

        BlobMetadata(
            blobId = blobId,
            length = Files.size(blobPath),
            timestamp = blobPath.getLastModifiedTime().toInstant(),
        )
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        listBlobsRecursively(basePath, prefix, "")
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<BlobMetadata>.listBlobsRecursively(
        dir: Path,
        filterPrefix: String,
        currentPrefix: String,
    ) {
        if (!dir.exists()) return

        val entries = dir.listDirectoryEntries()

        for (entry in entries) {
            val name = entry.name

            // Skip special files
            if (name == SHARDS_FILE || name.startsWith(".")) continue

            if (entry.isDirectory()) {
                // Recurse into shard directory
                listBlobsRecursively(entry, filterPrefix, currentPrefix + name)
            } else if (entry.isRegularFile() && name.endsWith(COMPLETE_BLOB_SUFFIX)) {
                // Extract blob ID from file name (remove .f suffix)
                val blobIdSuffix = name.removeSuffix(COMPLETE_BLOB_SUFFIX)
                val fullBlobId = currentPrefix + blobIdSuffix

                if (fullBlobId.startsWith(filterPrefix)) {
                    emit(
                        BlobMetadata(
                            blobId = BlobId(fullBlobId),
                            length = Files.size(entry),
                            timestamp = entry.getLastModifiedTime().toInstant(),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) = withContext(Dispatchers.IO) {
        if (readOnly) {
            throw IllegalStateException("Storage is read-only")
        }
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
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.move(tempPath, blobPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tempPath.deleteIfExists()
            throw e
        }
    }

    override suspend fun deleteBlob(blobId: BlobId) = withContext(Dispatchers.IO) {
        if (readOnly) {
            throw IllegalStateException("Storage is read-only")
        }
        val blobPath = getBlobPath(blobId)
        blobPath.deleteIfExists()
        Unit
    }

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = "filesystem",
        config = mapOf("path" to basePath.toString()),
    )

    override fun displayName(): String = basePath.toString()

    override fun isReadOnly(): Boolean = readOnly

    override suspend fun getCapacity(): Capacity = withContext(Dispatchers.IO) {
        val fileStore = Files.getFileStore(basePath)
        Capacity(
            sizeBytes = fileStore.totalSpace,
            freeBytes = fileStore.usableSpace,
        )
    }

    /**
     * Gets the filesystem path for a blob ID.
     *
     * Follows Go Kopia's sharding rules:
     * - Blob IDs shorter than maxNonShardedLength are stored at root
     * - Longer IDs use multi-level sharding based on the shards config
     * - All files have .f suffix
     */
    private fun getBlobPath(blobId: BlobId): Path {
        val id = blobId.value

        // Short blob IDs (e.g., kopia.repository) are stored at root
        if (id.length < maxNonShardedLength) {
            return basePath.resolve("$id$COMPLETE_BLOB_SUFFIX")
        }

        // Apply multi-level sharding
        val (dirPath, remainingId) = getShardedPath(id)
        val fileName = "$remainingId$COMPLETE_BLOB_SUFFIX"

        return if (dirPath.isEmpty()) {
            basePath.resolve(fileName)
        } else {
            basePath.resolve(dirPath).resolve(fileName)
        }
    }

    /**
     * Computes the sharded directory path and remaining blob ID.
     */
    private fun getShardedPath(blobId: String): Pair<String, String> {
        var offset = 0
        val pathParts = mutableListOf<String>()

        for (shardLen in shards) {
            if (offset + shardLen > blobId.length) break
            pathParts.add(blobId.substring(offset, offset + shardLen))
            offset += shardLen
        }

        val dirPath = pathParts.joinToString("/")
        val remainingId = blobId.substring(offset)

        return dirPath to remainingId
    }

    companion object {
        private const val SHARDS_FILE = ".shards"
        private const val COMPLETE_BLOB_SUFFIX = ".f"
        private val DEFAULT_SHARDS = listOf(1, 3)
        private const val DEFAULT_MAX_NON_SHARDED_LENGTH = 20

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Creates or opens a filesystem storage at the given path.
         *
         * @param path Path to the storage directory
         * @param create If true, create the directory and shards file if they don't exist
         * @param readOnly If true, the storage will be in read-only mode
         */
        fun create(path: Path, create: Boolean = false, readOnly: Boolean = false): FilesystemBlobStorage {
            if (create && !path.exists()) {
                path.createDirectories()
            }

            // Read or create shards configuration
            val shardsFile = path.resolve(SHARDS_FILE)
            val shardsConfig = if (shardsFile.exists()) {
                try {
                    json.decodeFromString<ShardsConfig>(shardsFile.readText())
                } catch (e: Exception) {
                    ShardsConfig(DEFAULT_SHARDS, DEFAULT_MAX_NON_SHARDED_LENGTH)
                }
            } else {
                val config = ShardsConfig(DEFAULT_SHARDS, DEFAULT_MAX_NON_SHARDED_LENGTH)
                // Always write .shards for writable storage so that Go CLI
                // (which defaults to [3,3] sharding) uses our sharding config.
                if (!readOnly && path.exists()) {
                    shardsFile.writeText(json.encodeToString(config))
                }
                config
            }

            return FilesystemBlobStorage(
                basePath = path,
                readOnly = readOnly,
                shards = shardsConfig.default,
                maxNonShardedLength = shardsConfig.maxNonShardedLength,
            )
        }

        /**
         * Constructor for backwards compatibility with tests.
         * Creates a storage with default sharding.
         */
        operator fun invoke(path: Path, readOnly: Boolean = false): FilesystemBlobStorage = create(path, create = false, readOnly = readOnly)
    }
}

/**
 * Sharding configuration stored in .shards file.
 * Compatible with Go Kopia format.
 */
@Serializable
internal data class ShardsConfig(
    val default: List<Int> = listOf(1, 3),
    val maxNonShardedLength: Int = 20,
)
