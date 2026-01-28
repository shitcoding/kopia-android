package org.kopiaKt.core.content

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.compression.COMPRESSION_HEADER_SIZE
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.CompressorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.encryption.Encryptor
import org.kopiaKt.core.encryption.EncryptorFactory
import org.kopiaKt.core.hashing.ContentHasher
import org.kopiaKt.core.hashing.ContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.index.IndexBlobEncryption
import org.kopiaKt.core.pack.PackBlobBuilder
import org.kopiaKt.core.pack.PackBlobPostamble
import org.kopiaKt.core.pack.PackBlobReader
import org.kopiaKt.core.pack.PackIndex
import org.kopiaKt.core.pack.PackIndexFactory
import org.kopiaKt.core.pack.PackIndexV1
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Core content-addressable storage manager.
 *
 * ContentManager orchestrates the full content pipeline:
 * - Hashing (producing content IDs)
 * - Optional compression
 * - Encryption
 * - Pack blob building
 * - Index management
 * - Deduplication
 *
 * Content flow on write:
 * 1. Hash the content to produce ContentId
 * 2. Check for deduplication
 * 3. Optionally compress
 * 4. Encrypt with ContentId-derived IV
 * 5. Add to current pack blob
 * 6. Auto-flush when pack size limit reached
 *
 * Content flow on read:
 * 1. Look up ContentId in indexes
 * 2. Fetch pack blob from storage
 * 3. Extract content at offset
 * 4. Decrypt
 * 5. Decompress if needed
 *
 * @property storage The blob storage backend
 * @property hasherFactory Factory for creating content hashers
 * @property hashAlgorithm The hash algorithm to use
 * @property hashSecret Secret for keyed hashing
 * @property encryptorFactory Factory for creating encryptors
 * @property encryptionAlgorithm The encryption algorithm to use
 * @property encryptionKey The encryption key
 * @property compressorFactory Factory for creating compressors
 * @property defaultCompression Default compression algorithm
 * @property maxPackSize Maximum pack blob size before auto-flush (bytes)
 */
class ContentManager(
    private val storage: BlobStorage,
    hasherFactory: ContentHasherFactory,
    hashAlgorithm: HashAlgorithm,
    hashSecret: ByteArray,
    encryptorFactory: EncryptorFactory,
    encryptionAlgorithm: EncryptionAlgorithm,
    encryptionKey: ByteArray,
    private val compressorFactory: CompressorFactory,
    private val defaultCompression: CompressionAlgorithm = CompressionAlgorithm.NONE,
    private val maxPackSize: Int = DEFAULT_MAX_PACK_SIZE
) {
    private val hasher: ContentHasher = hasherFactory.create(hashAlgorithm, hashSecret)
    private val encryptor: Encryptor = encryptorFactory.create(encryptionAlgorithm, encryptionKey)
    private val indexBlobEncryption: IndexBlobEncryption = IndexBlobEncryption(encryptor)

    private val mutex = Mutex()

    // Pending content (not yet flushed)
    private val pendingContents = mutableMapOf<ContentId, PendingContent>()

    // Current pack blob being built
    private var currentPackBuilder: PackBlobBuilder? = null
    private var currentPackBlobId: BlobId? = null

    // Committed indexes (loaded from storage)
    private val committedIndexes = mutableListOf<PackIndex>()
    private val committedContents = mutableMapOf<ContentId, ContentInfo>()

    // Written packs (flushed but not yet in committed index)
    private val writtenPacks = mutableMapOf<BlobId, ByteArray>()
    private val writtenContents = mutableMapOf<ContentId, ContentInfo>()

    // Session ID for pack blob naming
    private val sessionId = generateSessionId()

    // Statistics
    private val _stats = ContentManagerStats()
    val stats: ContentManagerStats get() = _stats

    /**
     * Writes content and returns its content ID.
     *
     * @param data The content data to write
     * @param prefix Optional content ID prefix (e.g., 'm' for manifests)
     * @param compression Compression algorithm (null = use default)
     * @return The content ID
     */
    suspend fun writeContent(
        data: ByteArray,
        prefix: Char? = null,
        compression: CompressionAlgorithm? = null
    ): ContentId = mutex.withLock {
        // Step 1: Hash the content
        val hashBytes = hasher.hashContent(data)
        val contentId = ContentId.fromHash(prefix, hashBytes)
        _stats.hashedBytes.addAndGet(data.size.toLong())

        // Step 2: Check for deduplication
        if (contentExists(contentId)) {
            _stats.deduplicatedContents.incrementAndGet()
            _stats.deduplicatedBytes.addAndGet(data.size.toLong())
            return contentId
        }

        // Step 3: Optionally compress
        val compressionToUse = compression ?: defaultCompression
        val (compressedData, actualCompressionHeaderId) = maybeCompress(data, compressionToUse)

        // Step 4: Encrypt
        val encryptedData = encryptor.encrypt(compressedData, contentId)

        // Step 5: Add to pack blob
        addToPackUnlocked(contentId, encryptedData, data.size.toUInt(), actualCompressionHeaderId)

        _stats.writtenContents.incrementAndGet()
        _stats.writtenBytes.addAndGet(encryptedData.size.toLong())

        contentId
    }

    /**
     * Reads content by ID.
     *
     * @param contentId The content ID to read
     * @return The content data
     * @throws ContentNotFoundException if content doesn't exist
     */
    suspend fun getContent(contentId: ContentId): ByteArray = mutex.withLock {
        // Check pending content first
        pendingContents[contentId]?.let { pending ->
            return decryptAndDecompress(pending.encryptedData, contentId, pending.compressionHeaderId)
        }

        // Check written packs (flushed but not reloaded)
        writtenContents[contentId]?.let { info ->
            val packData = writtenPacks[info.packBlobId]
                ?: throw ContentNotFoundException(contentId)
            val encryptedData = PackBlobReader.extractContent(packData, info)
            return decryptAndDecompress(encryptedData, contentId, info.compressionHeaderId)
        }

        // Check committed indexes
        committedContents[contentId]?.let { info ->
            val packData = fetchPackBlob(info.packBlobId)
            val encryptedData = PackBlobReader.extractContent(packData, info)
            return decryptAndDecompress(encryptedData, contentId, info.compressionHeaderId)
        }

        throw ContentNotFoundException(contentId)
    }

    /**
     * Gets content metadata without reading the actual content.
     *
     * @param contentId The content ID to look up
     * @return The content info, or null if not found
     */
    suspend fun getContentInfo(contentId: ContentId): ContentInfo? = mutex.withLock {
        pendingContents[contentId]?.info
            ?: writtenContents[contentId]
            ?: committedContents[contentId]
    }

    /**
     * Iterates over all content IDs with a specific prefix.
     *
     * This is used by ManifestManager to find all manifest content.
     *
     * @param prefix The content ID prefix to filter by (e.g., 'm' for manifests)
     * @param callback Called for each content ID matching the prefix
     */
    suspend fun iterateContents(
        prefix: Char,
        callback: suspend (ContentId) -> Unit
    ) {
        // Collect content IDs under the lock
        val contentIds = mutex.withLock {
            val ids = mutableListOf<ContentId>()

            // Iterate pending contents
            for ((contentId, _) in pendingContents) {
                if (contentId.prefix == prefix) {
                    ids.add(contentId)
                }
            }

            // Iterate written contents
            for ((contentId, _) in writtenContents) {
                if (contentId.prefix == prefix) {
                    ids.add(contentId)
                }
            }

            // Iterate committed contents
            for ((contentId, _) in committedContents) {
                if (contentId.prefix == prefix) {
                    ids.add(contentId)
                }
            }

            ids
        }

        // Call callback outside the lock to avoid deadlock
        for (contentId in contentIds) {
            callback(contentId)
        }
    }

    /**
     * Flushes all pending content to storage.
     *
     * This writes the current pack blob and index to storage.
     */
    suspend fun flush() = mutex.withLock {
        flushCurrentPackUnlocked()
        flushIndexUnlocked()
    }

    /**
     * Refreshes the committed index from storage.
     *
     * Call this to load indexes written by other sessions or after restart.
     */
    suspend fun refresh() = mutex.withLock {
        loadCommittedIndexes()
    }

    // ===== Private Implementation =====

    private fun contentExists(contentId: ContentId): Boolean {
        return pendingContents.containsKey(contentId)
            || writtenContents.containsKey(contentId)
            || committedContents.containsKey(contentId)
    }

    private fun maybeCompress(
        data: ByteArray,
        compression: CompressionAlgorithm
    ): Pair<ByteArray, Int> {
        if (compression == CompressionAlgorithm.NONE || data.isEmpty()) {
            return data to 0
        }

        try {
            val compressor = compressorFactory.create(compression)
            val compressed = compressor.compress(data)

            // Only use compression if it actually reduces size
            // Account for header overhead
            if (compressed.size < data.size) {
                return compressed to compression.headerId
            }
        } catch (e: Exception) {
            // Compression failed, use uncompressed
        }

        return data to 0
    }

    private suspend fun decryptAndDecompress(
        encryptedData: ByteArray,
        contentId: ContentId,
        compressionHeaderId: Int
    ): ByteArray {
        val decrypted = encryptor.decrypt(encryptedData, contentId)

        if (compressionHeaderId == 0) {
            return decrypted
        }

        return compressorFactory.decompressByHeader(decrypted)
    }

    // Track prefix type of current pack (null means no pack, true = has prefix, false = no prefix)
    private var currentPackHasPrefix: Boolean? = null

    private suspend fun addToPackUnlocked(
        contentId: ContentId,
        encryptedData: ByteArray,
        originalLength: UInt,
        compressionHeaderId: Int
    ) {
        val contentHasPrefix = contentId.prefix != null

        // Create pack builder if needed
        if (currentPackBuilder == null) {
            currentPackBlobId = generatePackBlobId(contentId.prefix)
            currentPackBuilder = PackBlobBuilder(
                packBlobId = currentPackBlobId!!,
                encryptionOverhead = encryptor.overhead,
                timestampSeconds = System.currentTimeMillis() / 1000
            )
            currentPackHasPrefix = contentHasPrefix
        }

        val builder = currentPackBuilder!!

        // Check if prefix type changed - must flush and start new pack
        // V1 index requires all entries to have same key size
        val prefixMismatch = currentPackHasPrefix != contentHasPrefix

        // Check if we need to flush before adding (size limit or prefix mismatch)
        if (builder.currentSize() + encryptedData.size > maxPackSize || prefixMismatch) {
            flushCurrentPackUnlocked()
            // Create new pack
            currentPackBlobId = generatePackBlobId(contentId.prefix)
            currentPackBuilder = PackBlobBuilder(
                packBlobId = currentPackBlobId!!,
                encryptionOverhead = encryptor.overhead,
                timestampSeconds = System.currentTimeMillis() / 1000
            )
            currentPackHasPrefix = contentHasPrefix
        }

        // Add content to pack
        val packOffset = currentPackBuilder!!.currentSize().toUInt()
        currentPackBuilder!!.addContent(
            contentId = contentId,
            encryptedData = encryptedData,
            originalLength = originalLength,
            compressionHeaderId = compressionHeaderId
        )

        // Track as pending
        val info = ContentInfo(
            contentId = contentId,
            packBlobId = currentPackBlobId!!,
            timestampSeconds = System.currentTimeMillis() / 1000,
            originalLength = originalLength,
            packedLength = encryptedData.size.toUInt(),
            packOffset = packOffset,
            compressionHeaderId = compressionHeaderId
        )
        pendingContents[contentId] = PendingContent(
            info = info,
            encryptedData = encryptedData,
            compressionHeaderId = compressionHeaderId
        )
    }

    private suspend fun flushCurrentPackUnlocked() {
        val builder = currentPackBuilder ?: return
        if (builder.contentCount() == 0) return

        val packBlobId = currentPackBlobId!!
        val (packData, contentInfos) = builder.build()

        // Write pack blob to storage
        storage.putBlob(packBlobId, packData)

        // Move pending to written
        for (info in contentInfos) {
            pendingContents.remove(info.contentId)
            writtenContents[info.contentId] = info
        }
        writtenPacks[packBlobId] = packData

        // Reset current pack
        currentPackBuilder = null
        currentPackBlobId = null
        currentPackHasPrefix = null
    }

    private suspend fun flushIndexUnlocked() {
        if (writtenContents.isEmpty()) return

        // Group entries by whether they have a prefix (needed for V1 index format
        // which requires all entries to have the same key size)
        val entriesByPrefixType = writtenContents.values.groupBy { it.contentId.prefix != null }

        for ((_, entries) in entriesByPrefixType) {
            if (entries.isEmpty()) continue

            // Build index blob for this group
            val indexData = PackIndexV1.build(entries)

            // Generate index blob ID
            val indexBlobId = generateIndexBlobId()

            // Encrypt index blob before writing
            val encryptedIndexData = indexBlobEncryption.encrypt(indexData, indexBlobId)

            // Write encrypted index blob to storage
            storage.putBlob(indexBlobId, encryptedIndexData)
        }

        // Move written to committed
        for ((contentId, info) in writtenContents) {
            committedContents[contentId] = info
        }
        writtenContents.clear()
        writtenPacks.clear()
    }

    private suspend fun loadCommittedIndexes() {
        // Clear existing
        committedIndexes.forEach { it.close() }
        committedIndexes.clear()
        committedContents.clear()

        // Load index blobs from storage
        storage.listBlobs(INDEX_BLOB_PREFIX).collect { metadata ->
            try {
                val encryptedIndexData = storage.getBlob(metadata.blobId)

                // Decrypt the index blob before parsing
                val indexData = indexBlobEncryption.decrypt(encryptedIndexData, metadata.blobId)

                val index = PackIndexFactory.open(indexData, encryptor.overhead.toUInt())
                committedIndexes.add(index)

                // Build lookup map
                index.iterate().forEach { info ->
                    committedContents[info.contentId] = info
                }
            } catch (e: Exception) {
                // Skip invalid index blobs - log to stderr for debugging if needed
            }
        }
    }

    private suspend fun fetchPackBlob(packBlobId: BlobId): ByteArray {
        return storage.getBlob(packBlobId)
    }

    private fun generatePackBlobId(prefix: Char?): BlobId {
        val blobPrefix = if (prefix != null && prefix in SPECIAL_PREFIXES) {
            PACK_BLOB_PREFIX_SPECIAL
        } else {
            PACK_BLOB_PREFIX_REGULAR
        }

        val randomPart = generateRandomHex(16)
        return BlobId("$blobPrefix$randomPart-$sessionId")
    }

    private fun generateIndexBlobId(): BlobId {
        val randomPart = generateRandomHex(16)
        return BlobId("$INDEX_BLOB_PREFIX$randomPart-$sessionId")
    }

    private fun generateSessionId(): String {
        return generateRandomHex(8)
    }

    private fun generateRandomHex(bytes: Int): String {
        val randomBytes = ByteArray(bytes)
        secureRandom.nextBytes(randomBytes)
        return randomBytes.joinToString("") { "%02x".format(it) }
    }

    private data class PendingContent(
        val info: ContentInfo,
        val encryptedData: ByteArray,
        val compressionHeaderId: Int
    )

    companion object {
        const val DEFAULT_MAX_PACK_SIZE = 20 * 1024 * 1024 // 20MB
        const val PACK_BLOB_PREFIX_REGULAR = "p"
        const val PACK_BLOB_PREFIX_SPECIAL = "q"
        const val INDEX_BLOB_PREFIX = "x"

        private val SPECIAL_PREFIXES = setOf('m', 'x') // manifest, index content

        private val secureRandom = SecureRandom()
    }
}

/**
 * Statistics for ContentManager operations.
 */
class ContentManagerStats {
    val hashedBytes = AtomicLong(0)
    val writtenContents = AtomicLong(0)
    val writtenBytes = AtomicLong(0)
    val deduplicatedContents = AtomicLong(0)
    val deduplicatedBytes = AtomicLong(0)
}

/**
 * Exception thrown when content is not found.
 */
class ContentNotFoundException(contentId: ContentId) :
    Exception("Content not found: $contentId")
