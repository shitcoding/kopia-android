package org.kopiaKt.android.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
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
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.RetentionMode
import org.kopiaKt.core.blob.UnsupportedPutOptionException
import java.io.IOException
import java.io.InputStream
import java.time.Instant

/**
 * Storage Access Framework (SAF) based blob storage for Android.
 *
 * This allows storing backups on external SD cards, USB storage,
 * and other storage providers accessible via SAF.
 *
 * Features:
 * - Sharded directory structure for performance
 * - Atomic writes (temp file + rename)
 * - Storage capacity queries
 * - Permission persistence support
 * - Read-only mode
 */
class SafBlobStorage private constructor(
    private val context: Context,
    private val rootUri: Uri,
    private val options: SafOptions,
    private val shardingParams: SafShardingParameters,
    private val readOnlyMode: Boolean,
) : BlobStorage,
    BlobVolume {

    private val rootDocument: DocumentFile by lazy {
        DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IllegalArgumentException("Invalid root URI: $rootUri")
    }

    /**
     * Cache of shard directories to avoid repeated lookups.
     * Maps shard path (e.g., "p" or "p/ack") to DocumentFile.
     */
    private val shardDirCache = java.util.concurrent.ConcurrentHashMap<String, DocumentFile>()

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray = withContext(Dispatchers.IO) {
        // Validate parameters
        if (offset < 0) {
            throw InvalidBlobRangeException("Negative offset not allowed: $offset")
        }
        if (length < -1) {
            throw InvalidBlobRangeException("Invalid length: $length")
        }

        if (length == 0L) {
            return@withContext byteArrayOf()
        }
        // A single ByteArray cannot exceed Int.MAX_VALUE. A ranged read is a content within a pack
        // (bounded, tens of MB), so a request larger than that cannot be buffered and is a bug/attack.
        // (offset stays a Long throughout — it is only ever passed to skip(), never narrowed to Int.)
        if (length > Int.MAX_VALUE) {
            throw InvalidBlobRangeException("Requested length too large to buffer: $length for blob $blobId")
        }

        val blobFile = findBlobFile(blobId)
            ?: throw BlobNotFoundException(blobId)

        context.contentResolver.openInputStream(blobFile.uri)?.use { stream ->
            // True ranged read: skip to the (Long) offset, then read ONLY the requested bytes. Never
            // buffer the whole blob — the previous readBytes() OOM'd on large packs — and never narrow
            // the offset to Int, which returned WRONG data for offsets >= 2 GiB. See task-14.
            val skipped = skipFully(stream, offset)
            if (skipped < offset) {
                // Reached EOF before the offset: the range starts past the end of the blob. NOTE: this
                // only fires for streams whose skip() clamps at EOF. The real device stream
                // (FileInputStream-family, from ContentResolver) lseeks and over-reports skip past EOF,
                // so an out-of-range offset instead surfaces as a 0-byte read caught by the short-read
                // check below. Both guards together cover a bad packOffset on a truncated pack.
                throw InvalidBlobRangeException(
                    "Offset $offset exceeds blob size ($skipped bytes) for blob $blobId",
                )
            }
            if (length == -1L) {
                stream.readBytes() // remaining bytes after the offset
            } else {
                val data = readUpTo(stream, length.toInt())
                // A fixed-length read must return exactly `length` bytes. Fewer means the blob is
                // truncated past the offset (corrupt pack, or a bad packOffset/packedLength); fail loud
                // rather than hand a short buffer upward, matching the SFTP/WebDAV/filesystem backends
                // (task-15). Downstream decrypt would otherwise fail confusingly on the wrong length.
                if (data.size < length.toInt()) {
                    throw InvalidBlobRangeException(
                        "Short read for blob $blobId: requested $length bytes at offset $offset " +
                            "but only ${data.size} were available",
                    )
                }
                data
            }
        } ?: throw IOException("Could not open blob: $blobId")
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? = withContext(Dispatchers.IO) {
        val blobFile = findBlobFile(blobId) ?: return@withContext null

        BlobMetadata(
            blobId = blobId,
            length = blobFile.length(),
            timestamp = Instant.ofEpochMilli(blobFile.lastModified()),
        )
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        listBlobsRecursive(rootDocument, prefix, "")
            .collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    private fun listBlobsRecursive(
        dir: DocumentFile,
        prefix: String,
        currentPath: String,
    ): Flow<BlobMetadata> = flow {
        for (file in dir.listFiles()) {
            val fileName = file.name ?: continue

            if (file.isDirectory) {
                // Recurse into subdirectories
                listBlobsRecursive(file, prefix, "$currentPath$fileName/")
                    .collect { emit(it) }
            } else if (fileName.endsWith(COMPLETE_BLOB_SUFFIX)) {
                // Remove suffix to get blob ID
                val blobId = fileName.removeSuffix(COMPLETE_BLOB_SUFFIX)
                val fullBlobId = reconstructBlobId(currentPath, blobId)

                if (fullBlobId.startsWith(prefix)) {
                    emit(
                        BlobMetadata(
                            blobId = BlobId(fullBlobId),
                            length = file.length(),
                            timestamp = Instant.ofEpochMilli(file.lastModified()),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) = withContext(Dispatchers.IO) {
        if (readOnlyMode) {
            throw IOException("Storage is in read-only mode")
        }

        // Check for unsupported options
        if (options.retentionMode != RetentionMode.NONE) {
            throw UnsupportedPutOptionException("retentionMode")
        }

        val existingFile = findBlobFile(blobId)

        if (options.dontOverwrite && existingFile != null) {
            return@withContext
        }

        // Get or create shard directory structure
        val (shardDir, fileName) = getBlobPathComponents(blobId)
        val targetDir = getOrCreateShardDir(shardDir)

        if (this@SafBlobStorage.options.atomicWrites) {
            // Atomic write: create temp file, write, then rename
            val tempFileName = "$fileName$TEMP_BLOB_INFIX${System.nanoTime()}"
            val tempFile = targetDir.createFile("application/octet-stream", tempFileName)
                ?: throw IOException("Could not create temp file: $tempFileName")

            try {
                // Write data to temp file
                context.contentResolver.openOutputStream(tempFile.uri)?.use { stream ->
                    stream.write(data)
                } ?: throw IOException("Could not write to temp file: $tempFileName")

                // Replace the old blob only once the new bytes are safely written. SAF has no
                // atomic replace, so a delete-then-rename window cannot be removed entirely — but
                // deleting up front left the window open for the whole write, and a kill inside it
                // destroyed the blob (losing kopia.repository makes the repository unopenable).
                existingFile?.delete()

                // Rename to final name
                val finalFileName = "$fileName$COMPLETE_BLOB_SUFFIX"
                if (!tempFile.renameTo(finalFileName)) {
                    throw IOException("Could not rename temp file to: $finalFileName")
                }

                // Set modification time if requested
                val modTime = options.setModTime
                if (modTime != null) {
                    // Note: DocumentFile.setLastModified() is not available
                    // We use DocumentsContract directly for this
                    setLastModified(tempFile.uri, modTime.toEpochMilli())
                }
            } catch (e: Exception) {
                // Clean up temp file on error
                tempFile.delete()
                throw e
            }
        } else {
            // Direct write (non-atomic): the replacement must be removed first because the new file
            // is created under the final name. This path is inherently non-atomic; atomicWrites is
            // the option that bounds the window.
            existingFile?.delete()

            val finalFileName = "$fileName$COMPLETE_BLOB_SUFFIX"
            val blobFile = targetDir.createFile("application/octet-stream", finalFileName)
                ?: throw IOException("Could not create blob file: $finalFileName")

            context.contentResolver.openOutputStream(blobFile.uri)?.use { stream ->
                stream.write(data)
            } ?: throw IOException("Could not write to blob: $blobId")

            // Set modification time if requested
            val modTime = options.setModTime
            if (modTime != null) {
                setLastModified(blobFile.uri, modTime.toEpochMilli())
            }
        }
    }

    /**
     * Attempts to set the last modified time of a document.
     * This may fail silently on some storage providers.
     */
    private fun setLastModified(uri: Uri, timeMillis: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val values = android.content.ContentValues().apply {
                    put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, timeMillis)
                }
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (_: Exception) {
            // Ignore - not all providers support setting modification time
        }
    }

    override suspend fun deleteBlob(blobId: BlobId) = withContext(Dispatchers.IO) {
        if (readOnlyMode) {
            throw IOException("Storage is in read-only mode")
        }

        findBlobFile(blobId)?.delete()
        Unit
    }

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = "saf",
        config = mapOf(
            "uri" to rootUri.toString(),
            "shards" to shardingParams.default.joinToString(","),
        ),
    )

    override fun displayName(): String = "SAF: ${rootUri.path ?: rootUri.toString()}"

    override fun isReadOnly(): Boolean = readOnlyMode

    @Suppress("DEPRECATION")
    override suspend fun getCapacity(): Capacity = withContext(Dispatchers.IO) {
        // Try to get capacity from StorageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

            if (storageManager != null) {
                val volumes = storageManager.storageVolumes
                val matchingVolume = findMatchingStorageVolume(volumes)

                if (matchingVolume != null) {
                    try {
                        val uuid = matchingVolume.uuid?.let { java.util.UUID.fromString(it) }
                            ?: StorageManager.UUID_DEFAULT

                        // getAllocatableBytes reports free/allocatable space, not the total
                        // volume size, so it must only be used for freeBytes.
                        @Suppress("NewApi")
                        val freeBytes = storageManager.getAllocatableBytes(uuid)

                        // Derive the real total from StatFs over the SAME volume's
                        // directory (available on API 30+). If we can't resolve the
                        // matched volume's real path, fall through rather than pair its
                        // free space with an unrelated volume's total (e.g. /data).
                        val statPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            matchingVolume.directory?.path
                        } else {
                            null
                        }
                        if (statPath != null) {
                            val totalBytes = StatFs(statPath).totalBytes
                            return@withContext Capacity(
                                sizeBytes = totalBytes,
                                freeBytes = freeBytes,
                            )
                        }
                    } catch (_: Exception) {
                        // Fall through to alternative method
                    }
                }
            }
        }

        // Fallback: use DocumentFile-based estimation
        // This is less accurate but works on older Android versions
        val rootFile = rootDocument
        if (rootFile.canRead()) {
            // We can't accurately determine capacity from SAF alone
            // Return a placeholder that indicates unknown capacity
            Capacity(
                sizeBytes = -1L,
                freeBytes = -1L,
            )
        } else {
            throw IOException("Cannot read from storage")
        }
    }

    /**
     * Finds the StorageVolume that matches our root URI.
     */
    private fun findMatchingStorageVolume(volumes: List<StorageVolume>): StorageVolume? {
        val uriPath = rootUri.path ?: return null

        // Try to match based on path components
        return volumes.find { volume ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val volumeDir = volume.directory
                volumeDir != null && uriPath.contains(volumeDir.name)
            } else {
                // On older versions, check the description
                val desc = volume.getDescription(context)
                desc != null && uriPath.contains(desc, ignoreCase = true)
            }
        }
    }

    /**
     * Skips [n] bytes from [stream], returning the number reported skipped. [InputStream.skip] may skip
     * fewer than requested and may legitimately return 0 for a non-file-backed stream, so loop and fall
     * back to a single read to guarantee forward progress. [n] is a Long and is never narrowed — this is
     * what makes offsets >= 2 GiB correct.
     *
     * WARNING: for FileInputStream-family streams (what ContentResolver returns for a SAF file document)
     * skip() lseeks and can report skipping PAST end-of-file, so the return value is NOT a reliable EOF
     * signal. The caller must confirm the requested bytes actually exist via the subsequent read (a
     * short/empty read then means the offset+length ran past the blob).
     */
    private fun skipFully(stream: InputStream, n: Long): Long {
        var remaining = n
        while (remaining > 0) {
            val s = stream.skip(remaining)
            if (s > 0) {
                remaining -= s
            } else if (stream.read() < 0) {
                break // EOF
            } else {
                remaining -= 1
            }
        }
        return n - remaining
    }

    /**
     * Reads up to [len] bytes from [stream] into a fresh array (fewer only if EOF is hit first). Uses a
     * read loop because a single [InputStream.read] may return fewer bytes than requested.
     */
    private fun readUpTo(stream: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val r = stream.read(buf, read, len - read)
            if (r < 0) break
            read += r
        }
        return if (read == len) buf else buf.copyOf(read)
    }

    /**
     * Finds the DocumentFile for a blob, or null if not found.
     */
    private fun findBlobFile(blobId: BlobId): DocumentFile? {
        val (shardPath, fileName) = getBlobPathComponents(blobId)
        val shardDir = findShardDir(shardPath) ?: return null
        return shardDir.findFile("$fileName$COMPLETE_BLOB_SUFFIX")
    }

    /**
     * Gets the shard path and file name for a blob ID.
     *
     * For example, with shards [1, 3] and blob ID "pack-abc123":
     * - If length > maxNonShardedLength: shardPath = "p/ack", fileName = "-abc123"
     * - Otherwise: shardPath = "", fileName = "pack-abc123"
     */
    private fun getBlobPathComponents(blobId: BlobId): Pair<String, String> {
        val id = blobId.value

        // Check for prefix-specific sharding
        val shards = shardingParams.overrides
            .find { id.startsWith(it.prefix) }
            ?.shards
            ?: shardingParams.default

        // Short blob IDs are not sharded
        if (id.length <= shardingParams.maxNonShardedLength) {
            return "" to id
        }

        // Build shard path
        val shardParts = mutableListOf<String>()
        var consumed = 0

        for (shardLen in shards) {
            if (consumed + shardLen > id.length) break
            shardParts.add(id.substring(consumed, consumed + shardLen))
            consumed += shardLen
        }

        val shardPath = shardParts.joinToString("/")
        val fileName = id.substring(consumed)

        return shardPath to fileName
    }

    /**
     * Reconstructs the full blob ID from shard path and file name.
     */
    private fun reconstructBlobId(shardPath: String, fileName: String): String = if (shardPath.isEmpty()) {
        fileName
    } else {
        shardPath.replace("/", "") + fileName
    }

    /**
     * Finds a shard directory by path, or null if not found.
     */
    private fun findShardDir(shardPath: String): DocumentFile? {
        if (shardPath.isEmpty()) {
            return rootDocument
        }

        // Check cache first
        val cached = shardDirCache[shardPath]
        if (cached != null) {
            return cached
        }

        // Navigate through the path
        var current: DocumentFile = rootDocument
        for (part in shardPath.split("/")) {
            val next = current.findFile(part) ?: return null
            if (!next.isDirectory) return null
            current = next
        }

        // Cache the result
        shardDirCache[shardPath] = current
        return current
    }

    /**
     * Gets or creates a shard directory by path.
     */
    private fun getOrCreateShardDir(shardPath: String): DocumentFile {
        if (shardPath.isEmpty()) {
            return rootDocument
        }

        // Check cache first
        val cached = shardDirCache[shardPath]
        if (cached != null) {
            return cached
        }

        // Navigate/create through the path
        var current: DocumentFile = rootDocument
        val parts = shardPath.split("/")
        var currentPath = ""

        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"

            // Check cache for this intermediate path
            val cachedIntermediate = shardDirCache[currentPath]
            if (cachedIntermediate != null) {
                current = cachedIntermediate
            } else {
                val existing = current.findFile(part)
                current = if (existing?.isDirectory == true) {
                    existing
                } else {
                    current.createDirectory(part)
                        ?: throw IOException("Could not create shard directory: $currentPath")
                }

                // Cache the intermediate result
                shardDirCache[currentPath] = current
            }
        }

        return current
    }

    /**
     * Clears the shard directory cache.
     * Call this if external changes may have modified the directory structure.
     */
    fun clearCache() {
        shardDirCache.clear()
    }

    override suspend fun flushCaches() {
        clearCache()
    }

    companion object {
        /**
         * Suffix added to blob files to indicate they are complete.
         * This matches the WebDAV implementation.
         */
        private const val COMPLETE_BLOB_SUFFIX = ".f"

        /** Infix marking an in-progress atomic write. Such files are never valid blobs. */
        internal const val TEMP_BLOB_INFIX = ".tmp."

        /**
         * Creates SAF blob storage from a granted tree URI.
         *
         * The URI should be obtained via Intent.ACTION_OPEN_DOCUMENT_TREE
         * and persisted via ContentResolver.takePersistableUriPermission().
         *
         * @param context Android context
         * @param treeUri Tree URI for the storage location
         * @param options Configuration options
         * @throws SecurityException if the app doesn't have persisted permission for the URI
         * @throws IllegalArgumentException if the URI is invalid
         */
        fun create(
            context: Context,
            treeUri: Uri,
            options: SafOptions = SafOptions(treeUri = treeUri),
        ): SafBlobStorage {
            // Verify we have permission
            val permissions = context.contentResolver.persistedUriPermissions
            val hasReadPermission = permissions.any { it.uri == treeUri && it.isReadPermission }
            val hasWritePermission = permissions.any { it.uri == treeUri && it.isWritePermission }

            if (!hasReadPermission) {
                throw SecurityException("No persisted read permission for URI: $treeUri")
            }

            if (!hasWritePermission && !options.readOnly) {
                throw SecurityException("No persisted write permission for URI: $treeUri (use readOnly=true)")
            }

            // Load or create sharding parameters
            val shardingParams = loadShardingParams(context, treeUri)
                ?: SafShardingParameters(
                    default = options.directoryShards,
                    maxNonShardedLength = options.maxNonShardedLength,
                )

            return SafBlobStorage(
                context = context,
                rootUri = treeUri,
                options = options,
                shardingParams = shardingParams,
                readOnlyMode = options.readOnly,
            )
        }

        /**
         * Creates SAF blob storage for testing with custom sharding parameters.
         */
        internal fun createForTesting(
            context: Context,
            treeUri: Uri,
            options: SafOptions,
            shardingParams: SafShardingParameters,
            skipPermissionCheck: Boolean = false,
        ): SafBlobStorage {
            if (!skipPermissionCheck) {
                val permissions = context.contentResolver.persistedUriPermissions
                val hasReadPermission = permissions.any { it.uri == treeUri && it.isReadPermission }

                if (!hasReadPermission) {
                    throw SecurityException("No persisted read permission for URI: $treeUri")
                }
            }

            return SafBlobStorage(
                context = context,
                rootUri = treeUri,
                options = options,
                shardingParams = shardingParams,
                readOnlyMode = options.readOnly,
            )
        }

        /**
         * Loads sharding parameters from the .shards file in the storage root.
         */
        private fun loadShardingParams(context: Context, treeUri: Uri): SafShardingParameters? {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val shardsFile = root.findFile(".shards") ?: return null

            return try {
                context.contentResolver.openInputStream(shardsFile.uri)?.use { stream ->
                    val json = stream.bufferedReader().readText()
                    kotlinx.serialization.json.Json.decodeFromString(
                        SafShardingParameters.serializer(),
                        json,
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Saves sharding parameters to the .shards file in the storage root.
         */
        suspend fun saveShardingParams(
            context: Context,
            treeUri: Uri,
            params: SafShardingParameters,
        ) = withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalArgumentException("Invalid root URI: $treeUri")

            // Delete existing file if present
            root.findFile(".shards")?.delete()

            // Create new file
            val shardsFile = root.createFile("application/json", ".shards")
                ?: throw IOException("Could not create .shards file")

            context.contentResolver.openOutputStream(shardsFile.uri)?.use { stream ->
                val json = kotlinx.serialization.json.Json.encodeToString(
                    SafShardingParameters.serializer(),
                    params,
                )
                stream.write(json.toByteArray())
            } ?: throw IOException("Could not write .shards file")
        }
    }
}
