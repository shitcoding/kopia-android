package org.kopiaKt.core.content

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobStorage
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
import org.kopiaKt.core.pack.PackBlobReader
import org.kopiaKt.core.pack.PackIndex
import org.kopiaKt.core.pack.PackIndexFactory
import org.kopiaKt.core.pack.PackIndexV1
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

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
    private val maxPackSize: Int = DEFAULT_MAX_PACK_SIZE,
    private val epochsEnabled: Boolean = false,
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

    // Whether the most recent loadCommittedIndexes read every index blob successfully. False if any
    // index blob was unreadable and skipped — the committed view is then PARTIAL (hidden content),
    // which a destructive caller (snapshot GC delete) must treat as fail-closed. See [isIndexLoadComplete].
    @Volatile
    private var indexLoadComplete = true

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
        compression: CompressionAlgorithm? = null,
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

        // Step 5: Add to pack blob. If we are resurrecting previously-deleted content (contentExists was
        // false because the current entry is a tombstone), stamp a timestamp strictly greater than that
        // tombstone so the new live entry wins the merge — Go carries the deleted entry's previousWriteTime
        // into the rewrite (contentWriteTime(prev)).
        val superseded = currentInfoUnlocked(contentId)
        val writeTime = if (superseded != null && superseded.deleted) {
            contentWriteTime(superseded.timestampSeconds)
        } else {
            System.currentTimeMillis() / 1000
        }
        addToPackUnlocked(contentId, encryptedData, data.size.toUInt(), actualCompressionHeaderId, writeTime)

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
        // Resolve the winning entry across layers: a newer tombstone must hide an older live entry, so
        // plain layer precedence is unsafe (must match getContentInfo — see currentInfoUnlocked).
        val info = currentInfoUnlocked(contentId) ?: throw ContentNotFoundException(contentId)
        if (info.deleted) throw ContentNotFoundException(contentId)

        // If the winner is the pending entry, its bytes are in memory (not yet in a pack blob).
        val pending = pendingContents[contentId]
        if (pending != null && pending.info === info) {
            return decryptAndDecompress(pending.encryptedData, contentId, pending.compressionHeaderId)
        }

        // Otherwise the winner lives in a pack blob: in memory (written this session) or in storage
        // (committed, or an undelete re-pointing a live entry at the original committed pack).
        val encryptedData = writtenPacks[info.packBlobId]?.let { PackBlobReader.extractContent(it, info) }
            ?: storage.getBlob(info.packBlobId, info.packOffset.toLong(), info.packedLength.toLong())
        return decryptAndDecompress(encryptedData, contentId, info.compressionHeaderId)
    }

    /**
     * Gets content metadata without reading the actual content.
     *
     * @param contentId The content ID to look up
     * @return The content info, or null if not found
     */
    suspend fun getContentInfo(contentId: ContentId): ContentInfo? = mutex.withLock {
        // A tombstone (deleted=true) means the content is not present.
        currentInfoUnlocked(contentId)?.takeUnless { it.deleted }
    }

    /**
     * The current (winning) index entry for [contentId] across the pending / written / committed
     * layers, or null if unknown. The returned entry MAY be a tombstone (deleted=true) — callers that
     * want only live content must filter it. The winner is the [contentInfoGreaterThan] max across the
     * layers (NOT plain layer precedence): a [refresh] can load committed data newer than a stale
     * written/pending entry, so we compare by timestamp/deleted rather than trusting layer order.
     * Call under [mutex].
     */
    private fun currentInfoUnlocked(contentId: ContentId): ContentInfo? {
        val candidates = listOfNotNull(
            pendingContents[contentId]?.info,
            writtenContents[contentId],
            committedContents[contentId],
        )
        return candidates.reduceOrNull { a, b -> if (contentInfoGreaterThan(a, b)) a else b }
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
        callback: suspend (ContentId) -> Unit,
    ) {
        // Collect the LIVE, de-duplicated content IDs with this prefix under the lock. committedContents
        // can now hold tombstones, so a raw concatenation would leak deleted ids and duplicates across
        // layers — callers (e.g. ManifestManager) expect current live content ids only.
        val contentIds = mutex.withLock {
            val merged = HashMap<ContentId, ContentInfo>()
            fun consider(info: ContentInfo) {
                if (info.contentId.prefix != prefix) return
                val existing = merged[info.contentId]
                if (existing == null || contentInfoGreaterThan(info, existing)) {
                    merged[info.contentId] = info
                }
            }
            committedContents.values.forEach(::consider)
            writtenContents.values.forEach(::consider)
            pendingContents.values.forEach { consider(it.info) }
            merged.values.filterNot { it.deleted }.map { it.contentId }
        }

        // Call callback outside the lock to avoid deadlock
        for (contentId in contentIds) {
            callback(contentId)
        }
    }

    /**
     * Iterates the merge-resolved [ContentInfo] for every content id (newest entry per id across the
     * pending, written, and committed layers). Surfaces tombstones (deleted=true) only when
     * [includeDeleted] is true. Used by snapshot GC Phase 2 to find unreferenced content. (task-9)
     *
     * @param includeDeleted whether to yield tombstone entries as well as live ones
     * @param callback invoked once per content id with its current [ContentInfo]
     */
    suspend fun iterateContentInfos(
        includeDeleted: Boolean,
        callback: suspend (ContentInfo) -> Unit,
    ) {
        val infos = mutex.withLock {
            // Winner-per-id via contentInfoGreaterThan across all layers (see currentInfoUnlocked for
            // why plain layer precedence is unsound). The winner may be a tombstone.
            val merged = HashMap<ContentId, ContentInfo>()
            fun consider(info: ContentInfo) {
                val existing = merged[info.contentId]
                if (existing == null || contentInfoGreaterThan(info, existing)) {
                    merged[info.contentId] = info
                }
            }
            committedContents.values.forEach(::consider)
            writtenContents.values.forEach(::consider)
            pendingContents.values.forEach { consider(it.info) }
            merged.values.toList()
        }
        for (info in infos) {
            if (!includeDeleted && info.deleted) continue
            callback(info)
        }
    }

    /**
     * Soft-deletes [contentId] by writing a tombstone: a clone of the current entry with deleted=true
     * and a strictly-increasing timestamp (Go-compatible). No-op if the content is unknown or already
     * deleted. The bytes are NOT removed; once flushed, the tombstone supersedes the live entry via the
     * merge. Reversible with [undeleteContent]. (task-9 GC Phase 2)
     */
    suspend fun deleteContent(contentId: ContentId) = mutex.withLock {
        // If the content is still in the current unflushed pack, flush that pack first: otherwise the
        // tombstone we put in writtenContents would be overwritten by the live entry when the pending
        // pack flushes (flushCurrentPackUnlocked re-writes writtenContents from the pack's contents).
        // GC never deletes pending content, so this path is only for correctness of the API.
        if (pendingContents.containsKey(contentId)) {
            flushCurrentPackUnlocked()
        }
        val current = currentInfoUnlocked(contentId) ?: return@withLock
        if (current.deleted) return@withLock
        // NOTE: a tombstone cloned from content-level-compressed content (compressionHeaderId != 0)
        // cannot be indexed by the V1 index builder used in flushIndexUnlocked (V1 rejects compression).
        // This codebase writes content-level-uncompressed content (object-level 'Z' compression keeps
        // compressionHeaderId == 0), so it is inert today; deleting Go-written compressed content needs
        // the V2 index write (task-13 / task-9 follow-up).
        writtenContents[contentId] = current.copy(
            deleted = true,
            timestampSeconds = contentWriteTime(current.timestampSeconds),
        )
    }

    /**
     * Revives a soft-deleted [contentId] by writing a live entry (deleted=false) with a
     * strictly-increasing timestamp. No-op if the content is unknown or already live. GC calls this
     * when content that was deleted turns out to still be referenced.
     *
     * Note: Go re-writes the content into a fresh pack blob; we re-point to the ORIGINAL pack (the
     * tombstone kept its pack info), valid because this GC soft-deletes index entries only and never
     * physically removes/compacts pack blobs, so the original pack still exists. (task-9)
     */
    suspend fun undeleteContent(contentId: ContentId) = mutex.withLock {
        val current = currentInfoUnlocked(contentId) ?: return@withLock
        if (!current.deleted) return@withLock
        writtenContents[contentId] = current.copy(
            deleted = false,
            timestampSeconds = contentWriteTime(current.timestampSeconds),
        )
    }

    /**
     * Go kopia's index merge order (contentInfoGreaterThan): `a` supersedes `b` iff `a` has a higher
     * timestamp; on a tie, a non-deleted entry beats a deleted one; on a further tie (both deleted, same
     * timestamp) the higher packBlobId wins — a deterministic, semantically-neutral tie-break.
     */
    private fun contentInfoGreaterThan(a: ContentInfo, b: ContentInfo): Boolean {
        if (a.timestampSeconds != b.timestampSeconds) return a.timestampSeconds > b.timestampSeconds
        if (a.deleted != b.deleted) return !a.deleted
        return a.packBlobId.value > b.packBlobId.value
    }

    /**
     * Timestamp for a (re)write of an EXISTING content id: strictly greater than its previous timestamp
     * even at 1-second wall-clock resolution (Go's contentWriteTime = max(now, prev+1)). Guarantees a
     * later delete/undelete always wins the merge over the entry it supersedes.
     */
    private fun contentWriteTime(previousUnixSeconds: Long): Long {
        val now = System.currentTimeMillis() / 1000
        return if (now > previousUnixSeconds) now else previousUnixSeconds + 1
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
        // Dedup only against a LIVE entry. If the current entry is a tombstone, the caller is
        // re-writing previously-deleted content (e.g. GC removed it, a new snapshot references it
        // again) — it must fall through and write a fresh live entry (resurrect), matching Go. Treating
        // a tombstone as "exists" would leave the content deleted: data loss.
        return currentInfoUnlocked(contentId)?.deleted == false
    }

    private fun maybeCompress(
        data: ByteArray,
        compression: CompressionAlgorithm,
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
        compressionHeaderId: Int,
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
        compressionHeaderId: Int,
        writeTime: Long,
    ) {
        val contentHasPrefix = contentId.prefix != null

        // Create pack builder if needed
        if (currentPackBuilder == null) {
            currentPackBlobId = generatePackBlobId(contentId.prefix)
            currentPackBuilder = PackBlobBuilder(
                packBlobId = currentPackBlobId!!,
                encryptionOverhead = encryptor.overhead,
                timestampSeconds = System.currentTimeMillis() / 1000,
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
                timestampSeconds = System.currentTimeMillis() / 1000,
            )
            currentPackHasPrefix = contentHasPrefix
        }

        // Add content to pack
        val packOffset = currentPackBuilder!!.currentSize().toUInt()
        currentPackBuilder!!.addContent(
            contentId = contentId,
            encryptedData = encryptedData,
            originalLength = originalLength,
            compressionHeaderId = compressionHeaderId,
        )

        // Track as pending. writeTime carries a resurrect's strictly-increasing timestamp (must beat the
        // tombstone it supersedes) — the pack-level builder timestamp is discarded on flush, so
        // flushCurrentPackUnlocked preserves this per-content value when it is higher.
        val info = ContentInfo(
            contentId = contentId,
            packBlobId = currentPackBlobId!!,
            timestampSeconds = writeTime,
            originalLength = originalLength,
            packedLength = encryptedData.size.toUInt(),
            packOffset = packOffset,
            compressionHeaderId = compressionHeaderId,
        )
        pendingContents[contentId] = PendingContent(
            info = info,
            encryptedData = encryptedData,
            compressionHeaderId = compressionHeaderId,
        )
    }

    private suspend fun flushCurrentPackUnlocked() {
        val builder = currentPackBuilder ?: return
        if (builder.contentCount() == 0) return

        val packBlobId = currentPackBlobId!!
        // Encrypt the local (recovery) index so KopiaKt pack blobs are Go-compatible (task-13):
        // the IV is the repo hash of the plaintext index and the stored index is ciphertext.
        val (packData, contentInfos) = builder.buildEncrypted(hasher, encryptor)

        // Write pack blob to storage
        storage.putBlob(packBlobId, packData)

        // Move pending to written. The builder stamps one pack-level timestamp on every entry; preserve a
        // higher per-content pending timestamp (a resurrect writes contentWriteTime(tombstone) so its live
        // entry beats the tombstone it supersedes — the pack-level value would lose the merge).
        for (builderInfo in contentInfos) {
            val pendingTs = pendingContents[builderInfo.contentId]?.info?.timestampSeconds
            val info = if (pendingTs != null && pendingTs > builderInfo.timestampSeconds) {
                builderInfo.copy(timestampSeconds = pendingTs)
            } else {
                builderInfo
            }
            pendingContents.remove(builderInfo.contentId)
            writtenContents[builderInfo.contentId] = info
        }
        writtenPacks[packBlobId] = packData

        // Reset current pack
        currentPackBuilder = null
        currentPackBlobId = null
        currentPackHasPrefix = null
    }

    private suspend fun flushIndexUnlocked() {
        if (writtenContents.isEmpty()) return

        // In epoch mode, all index blobs written by this flush go into the current write epoch (Go reads
        // that epoch's uncompacted blobs). Discover it once. Legacy mode ignores this.
        val writeEpoch = if (epochsEnabled) discoverCurrentWriteEpoch() else 0

        // Group entries by whether they have a prefix (needed for V1 index format
        // which requires all entries to have the same key size).
        //
        // Unprefixed FIRST, and that ordering is a data-safety property, not tidiness. Each group is
        // a separate blob write and a process can die between them. Unprefixed entries are file
        // content; prefixed ones ('k' directory manifests, 'm' snapshot manifests) are what POINTS
        // at that content. Writing the pointers first and dying leaves a visible manifest whose
        // contents are unindexed — a snapshot that looks restorable and is not, and which the next
        // run happily reuses because reuse matches on metadata, never on whether the content is
        // still there. This order can only ever leave content indexed but unreferenced, which is
        // just garbage, and which the next flush or run cleans up.
        val entriesByPrefixType = writtenContents.values
            .groupBy { it.contentId.prefix != null }
            .toSortedMap()

        for ((_, entries) in entriesByPrefixType) {
            if (entries.isEmpty()) continue

            // Build index blob for this group
            val indexData = PackIndexV1.build(entries)

            // Generate index blob ID
            val indexBlobId = generateIndexBlobId(writeEpoch)

            // Encrypt index blob before writing
            val encryptedIndexData = indexBlobEncryption.encrypt(indexData, indexBlobId)

            // Write encrypted index blob to storage
            storage.putBlob(indexBlobId, encryptedIndexData)
        }

        // Move written to committed using the same winner rule as loadCommittedIndexes — a plain
        // overwrite could replace a newer committed entry with a stale written one until the next
        // refresh (contentInfoGreaterThan keeps the correct winner).
        for ((contentId, info) in writtenContents) {
            val existing = committedContents[contentId]
            if (existing == null || contentInfoGreaterThan(info, existing)) {
                committedContents[contentId] = info
            }
        }
        writtenContents.clear()
        writtenPacks.clear()
    }

    /**
     * Whether the most recent index load read every index blob (no blob was skipped as unreadable).
     * A false result means the committed content view is partial — content may be hidden — so a
     * destructive caller (snapshot GC delete) MUST NOT act on it. See task-9.
     */
    fun isIndexLoadComplete(): Boolean = indexLoadComplete

    private suspend fun loadCommittedIndexes() {
        // Clear existing
        committedIndexes.forEach { it.close() }
        committedIndexes.clear()
        committedContents.clear()
        // Assume complete until an index blob fails to load below.
        indexLoadComplete = true

        // Load index blobs from storage
        // Support both old 'n' prefix and new 'x' prefix for backward compatibility
        for (prefix in INDEX_BLOB_PREFIXES) {
            storage.listBlobs(prefix).collect { metadata ->
                // Epoch marker / deletion-watermark blobs (xe<n> / xw<n>) share the "x" uber-prefix but
                // are plaintext control blobs, not index blobs. Skip them WITHOUT attempting to decrypt
                // (which would fail) and WITHOUT flagging the load incomplete (they are expected, not
                // corruption). See task-20.
                if (isEpochControlBlob(metadata.blobId.value)) {
                    return@collect
                }
                try {
                    val encryptedIndexData = storage.getBlob(metadata.blobId)

                    // Decrypt the index blob before parsing
                    val indexData = indexBlobEncryption.decrypt(encryptedIndexData, metadata.blobId)

                    val index = PackIndexFactory.open(indexData, encryptor.overhead.toUInt())
                    committedIndexes.add(index)

                    // Keep the WINNING entry per content id across all index blobs using Go kopia's
                    // contentInfoGreaterThan rule (see [contentInfoGreaterThan]). The winner MAY be a
                    // tombstone (deleted=true): getContentInfo filters those out and iterateContentInfos
                    // surfaces them. The previous "remove on ANY deleted" was load-order-dependent and
                    // would drop a live entry that a GC undelete wrote with a newer timestamp — a
                    // data-loss bug once GC can delete/undelete.
                    index.iterate().forEach { info ->
                        val existing = committedContents[info.contentId]
                        if (existing == null || contentInfoGreaterThan(info, existing)) {
                            committedContents[info.contentId] = info
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // never swallow coroutine cancellation
                } catch (e: Exception) {
                    // Skip an unreadable index blob so one bad blob can't fail the whole load, but
                    // log it AND mark the load incomplete — silently dropping an index hides data loss
                    // (a decrypt/parse failure means its contents disappear from the lookup, which could
                    // make a live snapshot's content look unreferenced to GC). isIndexLoadComplete()
                    // now returns false so a destructive GC delete run fails closed instead of deleting
                    // over a partial view.
                    indexLoadComplete = false
                    logger.log(
                        Level.WARNING,
                        "Skipping unreadable index blob ${metadata.blobId.value}: ${e.message}",
                        e,
                    )
                }
            }
        }
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

    private fun generateIndexBlobId(epoch: Int): BlobId {
        // 16 random bytes (32 hex) form the blob's hash segment. It also seeds the encryption IV
        // (IndexBlobEncryption derives the IV from the hex before the first dash), so both Kotlin (write)
        // and Go (read) derive the SAME IV from the name — no content re-hash / verification is done on
        // either side (proven by the legacy-mode round-trip), so a random hash segment is Go-compatible.
        val randomPart = generateRandomHex(16)
        return if (epochsEnabled) {
            // Go uncompacted per-epoch index blob: xn<epoch>_<hash>-s<session>-c<shardCount>. Kotlin writes
            // one index blob per prefix group, i.e. a complete single-shard set (-c1). Go's epoch reader
            // reads every xn blob unconditionally (uncompacted blobs are NOT complete-set filtered), so a
            // single -c1 blob in the current epoch is always seen and merged — and two such blobs from the
            // same flush/session are both read.
            //
            // ponytail: uncompacted-only. This helper must NOT be reused to name COMPACTION blobs
            // (xs<epoch>_ / xr<e1>_<e2>_): Go DOES complete-set-filter those by the shared "s<session>-c<N>"
            // set, so a second same-session "-c1" blob would be dropped as an incomplete set → invisible.
            // Kotlin does not write compaction blobs today (epoch advancement/compaction is deferred to Go
            // maintenance); a Kotlin-only epoch repo accumulates xn<epoch>_ blobs until Go first compacts it.
            BlobId("$EPOCH_UNCOMPACTED_INDEX_PREFIX${epoch}_$randomPart-s$sessionId-c1")
        } else {
            // Legacy (V0/pre-epoch) single index blob: n<hash>-<session>.
            BlobId("$INDEX_BLOB_PREFIX_OLD$randomPart-$sessionId")
        }
    }

    /**
     * The epoch number a writer must write index blobs into = max(epoch markers), or 0 if none exist
     * (Go's loadWriteEpoch; a fresh epoch repo has no markers and stays at epoch 0). Only meaningful when
     * [epochsEnabled]. Lists the plaintext "xe<n>" marker blobs and takes the highest number.
     */
    private suspend fun discoverCurrentWriteEpoch(): Int {
        var maxEpoch = 0
        storage.listBlobs(EPOCH_MARKER_PREFIX).collect { metadata ->
            // A real marker is exactly "xe<decimal>"; skip anything else (e.g. a legacy x<hash> blob whose
            // hash happens to start with 'e').
            val n = metadata.blobId.value.removePrefix(EPOCH_MARKER_PREFIX).toIntOrNull()
            if (n != null && n > maxEpoch) {
                maxEpoch = n
            }
        }
        return maxEpoch
    }

    private fun generateSessionId(): String = generateRandomHex(8)

    private fun generateRandomHex(bytes: Int): String {
        val randomBytes = ByteArray(bytes)
        secureRandom.nextBytes(randomBytes)
        return randomBytes.joinToString("") { "%02x".format(it) }
    }

    private data class PendingContent(
        val info: ContentInfo,
        val encryptedData: ByteArray,
        val compressionHeaderId: Int,
    )

    companion object {
        const val DEFAULT_MAX_PACK_SIZE = 20 * 1024 * 1024 // 20MB
        const val PACK_BLOB_PREFIX_REGULAR = "p"
        const val PACK_BLOB_PREFIX_SPECIAL = "q"

        // Index blob prefixes (Go compatibility). Epoch repos use the "x" uber-prefix with a second
        // letter: xn = uncompacted per-epoch index, xs = single-epoch compaction, xr = range checkpoint,
        // xe = epoch marker (plaintext), xw = deletion watermark (plaintext). Legacy (V0/pre-epoch) repos
        // use a single "n<hash>" index blob. See internal/epoch/epoch_manager.go.
        const val INDEX_BLOB_PREFIX = "x" // epoch uber-prefix (all epoch index blobs read under it)
        const val INDEX_BLOB_PREFIX_OLD = "n" // legacy (V0) single index blob prefix
        const val EPOCH_UNCOMPACTED_INDEX_PREFIX = "xn" // Go UncompactedIndexBlobPrefix
        const val EPOCH_MARKER_PREFIX = "xe" // Go EpochMarkerIndexBlobPrefix (plaintext)

        // Both prefixes for reading (backward compatibility): "x" covers all epoch index blobs
        // (xn/xs/xr) AND legacy Kotlin "x<hash>" blobs; "n" covers legacy V0 blobs.
        private val INDEX_BLOB_PREFIXES = listOf(INDEX_BLOB_PREFIX, INDEX_BLOB_PREFIX_OLD)

        // Epoch marker / deletion-watermark blobs are plaintext control blobs, NOT index blobs. They match
        // "xe<decimal>" / "xw<decimal>" exactly (no dash, no hash). They must be skipped when loading index
        // blobs — decrypting them as indexes fails, and must NOT flag the load incomplete. A legacy
        // "x<hash>" blob is never all-digits after the second char, so this never skips a real index blob.
        private val EPOCH_MARKER_REGEX = Regex("^x[ew]\\d+$")

        fun isEpochControlBlob(blobId: String): Boolean = EPOCH_MARKER_REGEX.matches(blobId)

        private val SPECIAL_PREFIXES = setOf('m', 'x') // manifest, index content

        private val secureRandom = SecureRandom()

        private val logger = Logger.getLogger(ContentManager::class.java.name)
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
class ContentNotFoundException(contentId: ContentId) : Exception("Content not found: $contentId")
