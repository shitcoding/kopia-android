package org.kopiaKt.android.storage

import android.content.Context
import android.net.Uri
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
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.PutBlobOptions
import java.io.IOException
import java.time.Instant

/**
 * Storage Access Framework (SAF) based blob storage for Android.
 *
 * This allows storing backups on external SD cards and other
 * storage providers accessible via SAF.
 */
class SafBlobStorage(
    private val context: Context,
    private val rootUri: Uri
) : BlobStorage {

    /**
     * Number of characters from blob ID to use for sharding.
     */
    private val shardLength = 1

    private val rootDocument: DocumentFile by lazy {
        DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IllegalArgumentException("Invalid root URI: $rootUri")
    }

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val blobFile = findBlobFile(blobId)
                ?: throw BlobNotFoundException(blobId)

            context.contentResolver.openInputStream(blobFile.uri)?.use { stream ->
                val bytes = stream.readBytes()

                if (offset == 0L && length == -1L) {
                    bytes
                } else {
                    val actualLength = if (length == -1L) bytes.size - offset.toInt() else length.toInt()
                    bytes.copyOfRange(offset.toInt(), offset.toInt() + actualLength)
                }
            } ?: throw IOException("Could not open blob: $blobId")
        }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? =
        withContext(Dispatchers.IO) {
            val blobFile = findBlobFile(blobId) ?: return@withContext null

            BlobMetadata(
                blobId = blobId,
                length = blobFile.length(),
                timestamp = Instant.ofEpochMilli(blobFile.lastModified())
            )
        }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        for (shardDir in rootDocument.listFiles()) {
            if (!shardDir.isDirectory) continue

            for (blobFile in shardDir.listFiles()) {
                val name = blobFile.name ?: continue
                if (name.startsWith(prefix)) {
                    emit(
                        BlobMetadata(
                            blobId = BlobId(name),
                            length = blobFile.length(),
                            timestamp = Instant.ofEpochMilli(blobFile.lastModified())
                        )
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) =
        withContext(Dispatchers.IO) {
            val existingFile = findBlobFile(blobId)

            if (options.dontOverwrite && existingFile != null) {
                return@withContext
            }

            // Get or create shard directory
            val shardName = getShardName(blobId)
            val shardDir = rootDocument.findFile(shardName)
                ?: rootDocument.createDirectory(shardName)
                ?: throw IOException("Could not create shard directory: $shardName")

            // Delete existing file if present
            existingFile?.delete()

            // Create new file
            val blobFile = shardDir.createFile("application/octet-stream", blobId.value)
                ?: throw IOException("Could not create blob file: $blobId")

            // Write data
            context.contentResolver.openOutputStream(blobFile.uri)?.use { stream ->
                stream.write(data)
            } ?: throw IOException("Could not write to blob: $blobId")
        }

    override suspend fun deleteBlob(blobId: BlobId) = withContext(Dispatchers.IO) {
        findBlobFile(blobId)?.delete()
        Unit
    }

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = "saf",
        config = mapOf("uri" to rootUri.toString())
    )

    override fun displayName(): String = rootUri.path ?: rootUri.toString()

    /**
     * Finds the DocumentFile for a blob, or null if not found.
     */
    private fun findBlobFile(blobId: BlobId): DocumentFile? {
        val shardName = getShardName(blobId)
        val shardDir = rootDocument.findFile(shardName) ?: return null
        return shardDir.findFile(blobId.value)
    }

    /**
     * Gets the shard directory name for a blob ID.
     */
    private fun getShardName(blobId: BlobId): String {
        val id = blobId.value
        return if (id.length > shardLength) id.substring(0, shardLength) else id
    }

    companion object {
        /**
         * Creates SAF blob storage from a granted tree URI.
         *
         * The URI should be obtained via Intent.ACTION_OPEN_DOCUMENT_TREE
         * and persisted via ContentResolver.takePersistableUriPermission().
         */
        fun create(context: Context, treeUri: Uri): SafBlobStorage {
            // Verify we have permission
            val permissions = context.contentResolver.persistedUriPermissions
            val hasPermission = permissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            }

            if (!hasPermission) {
                throw SecurityException("No persisted permission for URI: $treeUri")
            }

            return SafBlobStorage(context, treeUri)
        }
    }
}
