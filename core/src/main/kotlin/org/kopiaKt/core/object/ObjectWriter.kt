package org.kopiaKt.core.`object`

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.Compressor
import org.kopiaKt.core.compression.CompressorFactory
import org.kopiaKt.core.content.ContentManager
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.splitter.Splitter
import org.kopiaKt.core.splitter.SplitterFactory
import java.io.ByteArrayOutputStream

/**
 * Options for writing objects.
 */
data class ObjectWriterOptions(
    /**
     * Human-readable description for logging/debugging.
     */
    val description: String = "",

    /**
     * Content ID prefix for the written content.
     * Empty string or a single character ('g'..'z').
     */
    val prefix: Char? = null,

    /**
     * Compression algorithm to use for content.
     * If null, no compression is applied at the object level.
     */
    val compression: CompressionAlgorithm? = null,

    /**
     * Compression algorithm to use for metadata (indirect index blocks).
     * Typically matches the main compression algorithm.
     */
    val metadataCompression: CompressionAlgorithm? = null,

    /**
     * Custom splitter algorithm name. If null, uses the manager's default.
     */
    val splitter: String? = null,
)

/**
 * Interface for object writers that support Write, Checkpoint, and Result operations.
 *
 * Matches Go's Writer interface:
 * ```go
 * type Writer interface {
 *     io.WriteCloser
 *     Checkpoint() (ID, error)
 *     Result() (ID, error)
 * }
 * ```
 */
interface ObjectWriter {
    /**
     * Writes data to the object.
     *
     * @param data The bytes to write
     * @return The number of bytes written (always data.size)
     */
    suspend fun write(data: ByteArray): Int

    /**
     * Returns an object ID representing all data flushed to storage so far.
     * This may not include buffered data that hasn't reached a chunk boundary.
     * Returns Empty if nothing has been flushed yet.
     */
    suspend fun checkpoint(): ObjectId

    /**
     * Completes the write operation and returns the final object ID.
     * Flushes any remaining buffered data.
     */
    suspend fun result(): ObjectId

    /**
     * Closes the writer and releases resources.
     */
    suspend fun close()
}

/**
 * Default implementation of ObjectWriter.
 *
 * Handles:
 * - Splitting data into chunks using the configured splitter
 * - Writing chunks to ContentManager
 * - Creating indirect blocks when multiple chunks are written
 * - Optional compression at the object level
 *
 * Thread-safe via mutex.
 */
internal class DefaultObjectWriter(
    private val contentManager: ContentManager,
    private val splitter: Splitter,
    private val compressorFactory: CompressorFactory,
    private val metadataSplitterFactory: SplitterFactory,
    private val options: ObjectWriterOptions,
) : ObjectWriter {

    private val mutex = Mutex()

    // Buffer for data waiting to be chunked
    private val buffer = ByteArrayOutputStream()

    // Index entries for chunks written so far
    private val indirectIndex = mutableListOf<IndirectObjectEntry>()

    // Position tracking
    private var currentPosition = 0L
    private var totalLength = 0L

    // Compressor for object-level compression (Z prefix)
    private val compressor: Compressor? = options.compression?.let {
        if (it != CompressionAlgorithm.NONE) compressorFactory.create(it) else null
    }

    // Compressor for metadata (indirect index) compression
    private val metadataCompressor: Compressor? = options.metadataCompression?.let {
        if (it != CompressionAlgorithm.NONE) compressorFactory.create(it) else null
    }

    override suspend fun write(data: ByteArray): Int = mutex.withLock {
        totalLength += data.size

        var offset = 0
        while (offset < data.size) {
            val remaining = data.copyOfRange(offset, data.size)
            val splitPoint = splitter.nextSplitPoint(remaining)

            if (splitPoint < 0) {
                // No split point found, buffer all remaining data
                buffer.write(remaining)
                break
            } else {
                // Found a split point, write to buffer and flush
                buffer.write(remaining, 0, splitPoint)
                flushBufferUnlocked()
                offset += splitPoint
            }
        }

        data.size
    }

    override suspend fun checkpoint(): ObjectId = mutex.withLock {
        checkpointUnlocked()
    }

    override suspend fun result(): ObjectId = mutex.withLock {
        // Flush any remaining buffered data
        if (buffer.size() > 0 || indirectIndex.isEmpty()) {
            flushBufferUnlocked()
        }

        checkpointUnlocked()
    }

    override suspend fun close() {
        splitter.close()
    }

    /**
     * Flushes the current buffer to a content chunk.
     * Must be called with mutex held.
     */
    private suspend fun flushBufferUnlocked() {
        if (buffer.size() == 0) return

        val data = buffer.toByteArray()
        buffer.reset()

        // Apply object-level compression if enabled
        val (contentBytes, isCompressed) = maybeCompress(data)

        // Write content to ContentManager
        val contentId = contentManager.writeContent(
            data = contentBytes,
            prefix = options.prefix,
        )

        // Create object ID (with Z prefix if compressed at object level)
        val objectId = if (isCompressed) {
            ObjectId.compressed(contentId)
        } else {
            ObjectId.direct(contentId)
        }

        // Add entry to indirect index
        val entry = IndirectObjectEntry.create(
            start = currentPosition,
            length = data.size.toLong(),
            objectId = objectId,
        )
        indirectIndex.add(entry)

        currentPosition += data.size
    }

    /**
     * Creates a checkpoint object ID from current state.
     * Must be called with mutex held.
     */
    private suspend fun checkpointUnlocked(): ObjectId {
        if (indirectIndex.isEmpty()) {
            return ObjectId.Empty
        }

        if (indirectIndex.size == 1) {
            // Single chunk - return the object ID directly
            return indirectIndex[0].objectId.toObjectId()
        }

        // Multiple chunks - create indirect object
        return writeIndirectObject(indirectIndex)
    }

    /**
     * Writes an indirect object containing the given entries.
     * Returns the object ID with incremented indirection level.
     */
    private suspend fun writeIndirectObject(entries: List<IndirectObjectEntry>): ObjectId {
        val indirectObj = IndirectObject.create(entries)
        val jsonData = IndirectObject.encode(indirectObj)

        // Optionally compress metadata
        val (contentBytes, isCompressed) = maybeCompressMetadata(jsonData)

        // Write with 'x' prefix to force into metadata (q) blobs
        val prefix = if (options.prefix != null && options.prefix != '\u0000') {
            options.prefix
        } else {
            INDIRECT_CONTENT_PREFIX
        }

        val contentId = contentManager.writeContent(
            data = contentBytes,
            prefix = prefix,
        )

        // Create the base object ID
        val baseObjectId = if (isCompressed) {
            ObjectId.compressed(contentId)
        } else {
            ObjectId.direct(contentId)
        }

        // Increment indirection level
        return baseObjectId.incrementIndirection()
    }

    /**
     * Compresses data if a compressor is configured and compression helps.
     * Returns (compressed data, true) if compression was applied, otherwise (original data, false).
     */
    private fun maybeCompress(data: ByteArray): Pair<ByteArray, Boolean> {
        val comp = compressor ?: return data to false

        return try {
            val compressed = comp.compress(data)
            if (compressed.size < data.size) {
                compressed to true
            } else {
                data to false
            }
        } catch (e: Exception) {
            // Compression failed, use uncompressed
            data to false
        }
    }

    /**
     * Compresses metadata if a metadata compressor is configured.
     */
    private fun maybeCompressMetadata(data: ByteArray): Pair<ByteArray, Boolean> {
        val comp = metadataCompressor ?: return data to false

        return try {
            val compressed = comp.compress(data)
            if (compressed.size < data.size) {
                compressed to true
            } else {
                data to false
            }
        } catch (e: Exception) {
            data to false
        }
    }
}
