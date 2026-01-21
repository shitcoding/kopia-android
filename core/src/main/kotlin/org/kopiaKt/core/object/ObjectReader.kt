package org.kopiaKt.core.`object`

import org.kopiaKt.core.compression.CompressorFactory
import org.kopiaKt.core.content.ContentManager
import org.kopiaKt.core.content.ContentNotFoundException
import org.kopiaKt.core.content.ObjectId
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Exception thrown when an object cannot be found in the repository.
 */
class ObjectNotFoundException(objectId: ObjectId) :
    Exception("Object not found: $objectId")

/**
 * Interface for reading repository objects.
 *
 * Supports reading, seeking, and getting the length of objects.
 * Objects may be direct (single content block) or indirect (multiple blocks with index).
 */
interface ObjectReader {
    /**
     * Reads up to the specified number of bytes starting at the given offset.
     *
     * @param offset The starting position in the object
     * @param length Maximum number of bytes to read, or -1 for remaining bytes
     * @return The bytes read
     */
    suspend fun read(offset: Long = 0, length: Int = -1): ByteArray

    /**
     * Returns the total length of the object in bytes.
     */
    suspend fun length(): Long

    /**
     * Closes the reader and releases resources.
     */
    fun close()
}

/**
 * Reads direct objects (no indirection level).
 *
 * Direct objects are stored in a single content block, optionally with
 * object-level compression (Z prefix).
 */
internal class DirectObjectReader(
    private val contentManager: ContentManager,
    private val compressorFactory: CompressorFactory,
    private val objectId: ObjectId
) : ObjectReader {

    private var cachedData: ByteArray? = null

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val data = getData()

        val start = offset.toInt().coerceIn(0, data.size)
        val end = if (length < 0) {
            data.size
        } else {
            (start + length).coerceIn(start, data.size)
        }

        return data.copyOfRange(start, end)
    }

    override suspend fun length(): Long {
        return getData().size.toLong()
    }

    override fun close() {
        cachedData = null
    }

    private suspend fun getData(): ByteArray {
        cachedData?.let { return it }

        val (contentId, isCompressed, ok) = objectId.getContentId()
        if (!ok) {
            throw ObjectNotFoundException(objectId)
        }

        try {
            var data = contentManager.getContent(contentId)

            // Decompress if the object has Z prefix (object-level compression)
            if (isCompressed) {
                data = compressorFactory.decompressByHeader(data)
            }

            cachedData = data
            return data
        } catch (e: ContentNotFoundException) {
            throw ObjectNotFoundException(objectId)
        }
    }
}

/**
 * Reads indirect objects (with indirection level >= 1).
 *
 * Indirect objects consist of an index block containing entries that
 * point to other objects. The reader supports seeking by using the
 * seek table to find the correct chunk.
 */
internal class IndirectObjectReader(
    private val contentManager: ContentManager,
    private val compressorFactory: CompressorFactory,
    private val objectId: ObjectId
) : ObjectReader {

    private var seekTable: List<IndirectObjectEntry>? = null
    private var totalLength: Long = -1L

    // Cache for loaded chunks
    private val chunkCache = mutableMapOf<Int, ByteArray>()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        ensureSeekTableLoaded()

        val table = seekTable!!
        if (table.isEmpty()) {
            return ByteArray(0)
        }

        val actualLength = if (length < 0) {
            (totalLength - offset).toInt()
        } else {
            length
        }

        if (offset >= totalLength || actualLength <= 0) {
            return ByteArray(0)
        }

        val output = ByteArrayOutputStream(actualLength)
        var remaining = actualLength.coerceAtMost((totalLength - offset).toInt())
        var currentOffset = offset

        while (remaining > 0) {
            // Find the chunk containing currentOffset
            val chunkIndex = findChunkIndexForOffset(currentOffset)
            if (chunkIndex < 0 || chunkIndex >= table.size) {
                break
            }

            val entry = table[chunkIndex]
            val chunkData = loadChunk(chunkIndex, entry)

            // Calculate position within this chunk
            val positionInChunk = (currentOffset - entry.start).toInt()
            val bytesToRead = minOf(remaining, chunkData.size - positionInChunk)

            output.write(chunkData, positionInChunk, bytesToRead)

            currentOffset += bytesToRead
            remaining -= bytesToRead
        }

        return output.toByteArray()
    }

    override suspend fun length(): Long {
        ensureSeekTableLoaded()
        return totalLength
    }

    override fun close() {
        seekTable = null
        chunkCache.clear()
    }

    /**
     * Returns the seek table entries for this indirect object.
     */
    suspend fun getSeekTable(): List<IndirectObjectEntry> {
        ensureSeekTableLoaded()
        return seekTable!!
    }

    /**
     * Loads the seek table from the index object.
     */
    private suspend fun ensureSeekTableLoaded() {
        if (seekTable != null) return

        val (indexObjectId, ok) = objectId.indexObjectId()
        if (!ok) {
            throw IllegalStateException("Expected indirect object ID but got: $objectId")
        }

        val entries = loadIndexObject(indexObjectId)
        seekTable = entries
        totalLength = if (entries.isEmpty()) 0L else entries.last().endOffset()
    }

    /**
     * Recursively loads an index object.
     */
    private suspend fun loadIndexObject(indexObjectId: ObjectId): List<IndirectObjectEntry> {
        // Read the index object content
        val reader = openObject(indexObjectId)
        try {
            val data = reader.read()
            val indirectObj = IndirectObject.decode(data)
            return indirectObj.entries
        } finally {
            reader.close()
        }
    }

    /**
     * Opens an object for reading (may be direct or indirect).
     */
    private fun openObject(oid: ObjectId): ObjectReader {
        return if (oid.indirection > 0) {
            IndirectObjectReader(contentManager, compressorFactory, oid)
        } else {
            DirectObjectReader(contentManager, compressorFactory, oid)
        }
    }

    /**
     * Finds the chunk index containing the given byte offset.
     * Uses binary search for efficiency.
     */
    private fun findChunkIndexForOffset(offset: Long): Int {
        val table = seekTable ?: return -1

        var left = 0
        var right = table.size - 1

        while (left <= right) {
            val mid = (left + right) / 2
            val entry = table[mid]

            when {
                offset < entry.start -> right = mid - 1
                offset >= entry.endOffset() -> left = mid + 1
                else -> return mid
            }
        }

        return -1
    }

    /**
     * Loads a chunk by index, caching the result.
     */
    private suspend fun loadChunk(index: Int, entry: IndirectObjectEntry): ByteArray {
        chunkCache[index]?.let { return it }

        val chunkObjectId = entry.objectId.toObjectId()
        val reader = openObject(chunkObjectId)
        try {
            val data = reader.read()
            chunkCache[index] = data
            return data
        } finally {
            reader.close()
        }
    }
}

/**
 * Opens an object for reading.
 *
 * @param contentManager The content manager to use for reading content
 * @param compressorFactory Factory for decompressing object-level compression
 * @param objectId The object ID to open
 * @return An ObjectReader for the object
 */
fun openObject(
    contentManager: ContentManager,
    compressorFactory: CompressorFactory,
    objectId: ObjectId
): ObjectReader {
    return if (objectId.indirection > 0) {
        IndirectObjectReader(contentManager, compressorFactory, objectId)
    } else {
        DirectObjectReader(contentManager, compressorFactory, objectId)
    }
}

/**
 * Loads the index entries from an indirect object.
 *
 * @param contentManager The content manager to use for reading
 * @param compressorFactory Factory for decompression
 * @param objectId The indirect object ID (must have indirection > 0)
 * @return List of index entries
 */
suspend fun loadIndexObject(
    contentManager: ContentManager,
    compressorFactory: CompressorFactory,
    objectId: ObjectId
): List<IndirectObjectEntry> {
    require(objectId.indirection > 0) {
        "Expected indirect object ID but got direct: $objectId"
    }

    val reader = IndirectObjectReader(contentManager, compressorFactory, objectId)
    try {
        return reader.getSeekTable()
    } finally {
        reader.close()
    }
}
