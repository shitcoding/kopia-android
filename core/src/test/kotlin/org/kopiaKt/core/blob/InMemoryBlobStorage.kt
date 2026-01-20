package org.kopiaKt.core.blob

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of BlobStorage for testing purposes.
 *
 * This provides a simple, thread-safe storage that keeps all data in memory.
 * Useful for unit testing and as a mock implementation.
 */
class InMemoryBlobStorage(
    private val name: String = "in-memory"
) : BlobStorage {

    private data class StoredBlob(
        val data: ByteArray,
        val timestamp: Instant
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StoredBlob) return false
            return data.contentEquals(other.data) && timestamp == other.timestamp
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }

    private val blobs = ConcurrentHashMap<BlobId, StoredBlob>()

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray {
        val stored = blobs[blobId] ?: throw BlobNotFoundException(blobId)

        val data = stored.data

        return when {
            offset == 0L && length == -1L -> data.copyOf()
            length == -1L -> data.copyOfRange(offset.toInt(), data.size)
            length == 0L -> ByteArray(0)
            else -> data.copyOfRange(offset.toInt(), (offset + length).toInt())
        }
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? {
        val stored = blobs[blobId] ?: return null
        return BlobMetadata(
            blobId = blobId,
            length = stored.data.size.toLong(),
            timestamp = stored.timestamp
        )
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        for ((blobId, stored) in blobs) {
            if (blobId.value.startsWith(prefix)) {
                emit(
                    BlobMetadata(
                        blobId = blobId,
                        length = stored.data.size.toLong(),
                        timestamp = stored.timestamp
                    )
                )
            }
        }
    }

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
        if (options.dontOverwrite && blobs.containsKey(blobId)) {
            return
        }

        blobs[blobId] = StoredBlob(
            data = data.copyOf(),
            timestamp = options.getModTime ?: Instant.now()
        )
    }

    override suspend fun deleteBlob(blobId: BlobId) {
        blobs.remove(blobId)
    }

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = "memory",
        config = mapOf("name" to name)
    )

    override fun displayName(): String = "InMemory($name)"

    /**
     * Clears all stored blobs. Useful for test cleanup.
     */
    fun clear() {
        blobs.clear()
    }

    /**
     * Returns the number of blobs currently stored.
     */
    fun size(): Int = blobs.size

    /**
     * Checks if a blob exists without retrieving it.
     */
    fun contains(blobId: BlobId): Boolean = blobs.containsKey(blobId)
}
