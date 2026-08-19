package org.kopiaKt.core.`object`

import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.CompressorFactory
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.content.ContentManager
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.splitter.DefaultSplitterFactory
import org.kopiaKt.core.splitter.SplitterAlgorithms
import org.kopiaKt.core.splitter.SplitterFactory

/**
 * Manages content-addressable objects of arbitrary size.
 *
 * ObjectManager sits on top of ContentManager and provides:
 * - Support for large files via automatic chunking and indirect blocks
 * - Object-level compression (Z prefix)
 * - Streaming write API
 * - Object concatenation
 *
 * Small objects are stored in a single content block.
 * Large objects are split into chunks, with an indirect block containing
 * the index of all chunks. For very large objects, multiple levels of
 * indirection may be used.
 *
 * This implementation matches Go's repo/object package for full compatibility.
 *
 * @property contentManager The underlying content manager
 * @property splitterFactory Factory for creating splitters (defaults to DYNAMIC-4M-BUZHASH)
 * @property compressorFactory Factory for compression (optional)
 * @property defaultCompression Default compression for new objects
 */
class ObjectManager(
    private val contentManager: ContentManager,
    private val splitterFactory: SplitterFactory = DefaultSplitterFactory.getFactory(
        SplitterAlgorithms.DEFAULT_ALGORITHM,
    )!!,
    private val compressorFactory: CompressorFactory = DefaultCompressorFactory(),
    private val defaultCompression: CompressionAlgorithm? = null,
) {
    /**
     * Creates a new ObjectWriter for writing objects.
     *
     * @param options Writer options (compression, prefix, etc.)
     * @return A new ObjectWriter instance
     */
    fun newWriter(options: ObjectWriterOptions = ObjectWriterOptions()): ObjectWriter {
        // A per-write override, else the splitter the repository declares.
        //
        // An override naming something unknown falls back rather than throwing, which is Go:
        // `if opt.Splitter != "" { splitFactory = GetFactory(opt.Splitter) }; if splitFactory == nil
        // { splitFactory = om.newDefaultSplitter }` (`repo/object/object_manager.go:60-66`). The
        // override's source is a source's splitter POLICY, and neither Go's policy import nor this
        // app's `setPolicy` validates that field — so throwing would let one bad policy value fail
        // every backup of that source, where Go quietly writes with the repository's own splitter.
        // The repository's OWN splitter is a different matter and does throw (see
        // `DirectRepositoryImpl.splitterFactoryFor`), because writing with an algorithm other than
        // the one the repository names is precisely the defect task-78 fixed.
        val splitFactory = options.splitter?.let { DefaultSplitterFactory.getFactory(it) } ?: splitterFactory

        return DefaultObjectWriter(
            contentManager = contentManager,
            splitter = splitFactory.create(),
            compressorFactory = compressorFactory,
            metadataSplitterFactory = splitterFactory,
            options = options.copy(
                compression = options.compression ?: defaultCompression,
            ),
        )
    }

    /**
     * Opens an object for reading.
     *
     * @param objectId The object ID to open
     * @return An ObjectReader for the object
     * @throws ObjectNotFoundException if the object doesn't exist
     */
    fun openReader(objectId: ObjectId): ObjectReader {
        if (objectId == ObjectId.Empty) {
            return EmptyObjectReader
        }
        return openObject(contentManager, compressorFactory, objectId)
    }

    /**
     * Reads an entire object into memory.
     *
     * For large objects, consider using openReader() for streaming access.
     *
     * @param objectId The object ID to read
     * @return The complete object data
     * @throws ObjectNotFoundException if the object doesn't exist
     */
    suspend fun readObject(objectId: ObjectId): ByteArray {
        if (objectId == ObjectId.Empty) {
            return ByteArray(0)
        }
        val reader = openReader(objectId)
        try {
            return reader.read()
        } finally {
            reader.close()
        }
    }

    /**
     * Writes an object from a complete byte array.
     *
     * For large objects, consider using newWriter() for streaming writes.
     *
     * @param data The object data to write
     * @param options Writer options (compression, prefix, etc.)
     * @return The object ID
     */
    suspend fun writeObject(
        data: ByteArray,
        options: ObjectWriterOptions = ObjectWriterOptions(),
    ): ObjectId {
        // No early return for empty data. Go hashes the empty byte string and stores its content id,
        // so an empty file gets a real object id; handing back ObjectId.Empty put `obj: ""` in the
        // directory manifest, which Go refuses to restore -- one empty file made the entire snapshot
        // unrestorable by desktop Kopia.
        val writer = newWriter(options)
        try {
            writer.write(data)
            return writer.result()
        } finally {
            writer.close()
        }
    }

    /**
     * Loads the index entries from an indirect object.
     *
     * @param objectId The indirect object ID (must have indirection > 0)
     * @return List of index entries
     * @throws IllegalArgumentException if objectId is not indirect
     */
    suspend fun loadIndexObject(objectId: ObjectId): List<IndirectObjectEntry> = loadIndexObject(contentManager, compressorFactory, objectId)

    /**
     * Concatenates multiple objects into a single object.
     *
     * This is more efficient than reading and rewriting because it can
     * merge index entries without reading the underlying content.
     *
     * Useful for efficient parallel uploads of very large files.
     *
     * @param objectIds The objects to concatenate (in order)
     * @param metadataCompression Compression for the concatenated index
     * @return The object ID of the concatenated object
     * @throws IllegalArgumentException if objectIds is empty
     */
    suspend fun concatenate(
        objectIds: List<ObjectId>,
        metadataCompression: CompressionAlgorithm? = null,
    ): ObjectId {
        require(objectIds.isNotEmpty()) { "Cannot concatenate empty list of objects" }

        if (objectIds.size == 1) {
            return objectIds[0]
        }

        // Collect all index entries from all objects
        val concatenatedEntries = mutableListOf<IndirectObjectEntry>()
        var totalLength = 0L

        for (objectId in objectIds) {
            val entries = appendIndexEntriesForObject(objectId, totalLength)
            concatenatedEntries.addAll(entries)
            if (entries.isNotEmpty()) {
                totalLength = entries.last().start + entries.last().length
            }
        }

        // Write concatenated index as a new indirect object
        val writer = newWriter(
            ObjectWriterOptions(
                prefix = INDIRECT_CONTENT_PREFIX,
                description = "CONCATENATED INDEX",
                compression = metadataCompression,
                metadataCompression = metadataCompression,
            ),
        )

        try {
            val indirectObj = IndirectObject.create(concatenatedEntries)
            val jsonData = IndirectObject.encode(indirectObj)
            writer.write(jsonData)

            val contentObjectId = writer.result()

            // Add indirection level
            return contentObjectId.incrementIndirection()
        } finally {
            writer.close()
        }
    }

    /**
     * Appends index entries for an object to support concatenation.
     *
     * For indirect objects, extracts the existing index entries.
     * For direct objects, creates a single entry from the object.
     *
     * @param objectId The object to extract entries from
     * @param startingOffset The starting offset for the entries
     * @return List of entries with adjusted offsets
     */
    private suspend fun appendIndexEntriesForObject(
        objectId: ObjectId,
        startingOffset: Long,
    ): List<IndirectObjectEntry> {
        // Check if this is an indirect object
        val (indexObjectId, isIndirect) = objectId.indexObjectId()

        if (isIndirect) {
            // Load existing index and adjust offsets
            val existingEntries = loadIndexObject(objectId)
            return existingEntries.map { entry ->
                IndirectObjectEntry(
                    start = entry.start + startingOffset,
                    length = entry.length,
                    objectId = entry.objectId,
                )
            }
        }

        // Direct object - need to read its length
        val reader = openReader(objectId)
        try {
            val length = reader.length()
            return listOf(
                IndirectObjectEntry.create(
                    start = startingOffset,
                    length = length,
                    objectId = objectId,
                ),
            )
        } finally {
            reader.close()
        }
    }

    /**
     * Verifies that all content backing an object exists in the repository.
     *
     * @param objectId The object to verify
     * @return List of content IDs that back this object
     * @throws ObjectNotFoundException if any backing content is missing
     */
    suspend fun verifyObject(objectId: ObjectId): List<org.kopiaKt.core.content.ContentId> {
        // The empty object (e.g. a zero-byte file, whose DirEntry.obj is "") is backed by no content —
        // it has nothing to verify. Mirror readObject/openReader, which special-case ObjectId.Empty.
        // Without this, getContentInfo(ContentId.Empty) is null and verifyObject throws, which makes
        // snapshot GC's fail-closed walk abort on any snapshot containing an empty file. (task-9)
        if (objectId == ObjectId.Empty) {
            return emptyList()
        }

        val contentIds = mutableListOf<org.kopiaKt.core.content.ContentId>()

        iterateBackingContents(objectId) { contentId ->
            // Try to get content info to verify it exists
            contentManager.getContentInfo(contentId)
                ?: throw ObjectNotFoundException(objectId)
            contentIds.add(contentId)
        }

        return contentIds
    }

    /**
     * Iterates over all content IDs backing an object.
     *
     * @param objectId The object to iterate
     * @param callback Called for each content ID
     */
    private suspend fun iterateBackingContents(
        objectId: ObjectId,
        callback: suspend (org.kopiaKt.core.content.ContentId) -> Unit,
    ) {
        val (indexObjectId, isIndirect) = objectId.indexObjectId()

        if (isIndirect) {
            // First, iterate the index object's content
            iterateBackingContents(indexObjectId, callback)

            // Then iterate each entry's object
            val entries = loadIndexObject(objectId)
            for (entry in entries) {
                iterateBackingContents(entry.objectId.toObjectId(), callback)
            }
        } else {
            // Direct object - just the content ID
            val (contentId, _, ok) = objectId.getContentId()
            if (ok) {
                callback(contentId)
            }
        }
    }
}

/**
 * Empty object reader that always returns empty data.
 */
private object EmptyObjectReader : ObjectReader {
    override suspend fun read(offset: Long, length: Int): ByteArray = ByteArray(0)
    override suspend fun length(): Long = 0L
    override fun close() {}
}
