package org.kopiaKt.core.repository

import kotlinx.coroutines.sync.Mutex
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobReader
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.BlobVolume
import org.kopiaKt.core.blob.RepositoryUnavailableException
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.CompressorFactory
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.content.ContentManager
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.crypto.HkdfSha256KeyDerivation
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.format.FormatBlobManager
import org.kopiaKt.core.format.KopiaRepositoryJson
import org.kopiaKt.core.format.OpenRepositoryResult
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.manifest.EntryMetadata
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.manifest.ManifestManager
import org.kopiaKt.core.`object`.ObjectManager
import org.kopiaKt.core.`object`.ObjectReader
import org.kopiaKt.core.`object`.ObjectWriter
import org.kopiaKt.core.`object`.ObjectWriterOptions
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct repository implementation that directly manipulates blob storage.
 *
 * This is the primary implementation of Repository for local/direct access.
 * It manages all the underlying components (ContentManager, ObjectManager, ManifestManager)
 * and orchestrates their interactions.
 *
 * Thread safety: This class uses internal synchronization for concurrent access.
 */
class DirectRepositoryImpl private constructor(
    private val blobStorage: BlobStorage,
    private val contentManager: ContentManager,
    private val objectManager: ObjectManager,
    private val manifestManager: ManifestManager,
    private val config: RepositoryConfig,
    private val uniqueId: ByteArray,
    private val formatEncryptionKey: ByteArray,
    private var clientOptions: ClientOptions,
    private val clock: Clock,
    private val compressorFactory: CompressorFactory,
    private val isWriter: Boolean = false,
    private val afterFlushCallbacks: MutableList<suspend (RepositoryWriter) -> Unit> = mutableListOf(),
) : DirectRepositoryWriter {

    private val closed = AtomicBoolean(false)
    private val writerIdCounter = AtomicInteger(0)
    private val mutex = Mutex()

    // ===== Repository Interface =====

    override fun openObject(objectId: ObjectId): ObjectReader {
        checkNotClosed()
        return objectManager.openReader(objectId)
    }

    override suspend fun readObject(objectId: ObjectId): ByteArray {
        checkNotClosed()
        return objectManager.readObject(objectId)
    }

    override suspend fun verifyObject(objectId: ObjectId): List<ContentId> {
        checkNotClosed()
        return objectManager.verifyObject(objectId)
    }

    override suspend fun <T> getManifest(id: ManifestId, serializer: kotlinx.serialization.KSerializer<T>): Pair<T, EntryMetadata> {
        checkNotClosed()
        return manifestManager.getWithSerializer(id, serializer)
    }

    override suspend fun findManifests(labels: Map<String, String>): List<EntryMetadata> {
        checkNotClosed()
        return manifestManager.find(labels)
    }

    override suspend fun contentInfo(contentId: ContentId): ContentInfo? {
        checkNotClosed()
        return contentManager.getContentInfo(contentId)
    }

    override fun time(): Instant = Instant.now(clock)

    override fun clientOptions(): ClientOptions = clientOptions

    override suspend fun newWriter(options: WriteSessionOptions): RepositoryWriter = newDirectWriter(options)

    override fun updateDescription(description: String) {
        clientOptions = clientOptions.copy(description = description)
    }

    override suspend fun refresh() {
        checkNotClosed()
        contentManager.refresh()
        manifestManager.refresh()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Cleanup resources
            // Note: blobStorage is not owned by this instance in writer case
        }
    }

    // ===== RepositoryWriter Interface =====

    override fun newObjectWriter(options: ObjectWriterOptions): ObjectWriter {
        checkNotClosed()
        checkWritable()
        return objectManager.newWriter(options)
    }

    override suspend fun writeObject(data: ByteArray, options: ObjectWriterOptions): ObjectId {
        checkNotClosed()
        checkWritable()
        return objectManager.writeObject(data, options)
    }

    override suspend fun concatenateObjects(objectIds: List<ObjectId>, options: ConcatenateOptions): ObjectId {
        checkNotClosed()
        checkWritable()
        val compression = options.compressor?.let { name ->
            CompressionAlgorithm.entries.find { it.name.equals(name, ignoreCase = true) }
        }
        return objectManager.concatenate(objectIds, compression)
    }

    override suspend fun <T> putManifest(labels: Map<String, String>, payload: T, serializer: kotlinx.serialization.KSerializer<T>): ManifestId {
        checkNotClosed()
        checkWritable()
        return manifestManager.putWithSerializer(labels, payload, serializer)
    }

    override suspend fun <T> replaceManifests(labels: Map<String, String>, payload: T, serializer: kotlinx.serialization.KSerializer<T>): ManifestId {
        checkNotClosed()
        checkWritable()
        // Delete existing manifests with same labels
        val existing = manifestManager.find(labels)
        for (meta in existing) {
            manifestManager.delete(meta.id)
        }
        // Add small delay to ensure new manifest has a later timestamp
        if (existing.isNotEmpty()) {
            kotlinx.coroutines.delay(MIN_REPLACE_MANIFEST_TIME_DELTA_MS)
        }
        return manifestManager.putWithSerializer(labels, payload, serializer)
    }

    override suspend fun deleteManifest(id: ManifestId) {
        checkNotClosed()
        checkWritable()
        manifestManager.delete(id)
    }

    override fun onSuccessfulFlush(callback: suspend (RepositoryWriter) -> Unit) {
        afterFlushCallbacks.add(callback)
    }

    override suspend fun flush() {
        checkNotClosed()
        checkWritable()

        // Flush manifests first (they write to content manager)
        manifestManager.flush()

        // Then flush content manager (writes to blob storage)
        contentManager.flush()

        // Invoke after-flush callbacks
        for (callback in afterFlushCallbacks) {
            callback(this)
        }
    }

    // ===== DirectRepository Interface =====

    override fun objectFormat(): ObjectFormatInfo = ObjectFormatInfo(splitter = config.splitter)

    override fun blobReader(): BlobReader = blobStorage

    override fun blobVolume(): BlobVolume? = blobStorage as? BlobVolume

    override fun uniqueId(): ByteArray = uniqueId.copyOf()

    override fun deriveKey(purpose: String, keyLength: Int): ByteArray {
        // Use master key if password change is supported, otherwise use format encryption key
        val primaryKey = if (config.enablePasswordChange) {
            config.masterKey
        } else {
            formatEncryptionKey
        }

        return HkdfSha256KeyDerivation().derive(
            masterKey = primaryKey,
            salt = uniqueId,
            info = purpose.toByteArray(Charsets.UTF_8),
            length = keyLength,
        )
    }

    override suspend fun iterateContentInfos(
        includeDeleted: Boolean,
        callback: suspend (ContentInfo) -> Unit,
    ) {
        checkNotClosed()
        contentManager.iterateContentInfos(includeDeleted, callback)
    }

    override fun lastLoadWasComplete(): Boolean {
        // Both the content index load AND the manifest load must have been complete. A skip in either
        // hides content/snapshots and makes a partial view unsafe for destructive GC. See task-9.
        return contentManager.isIndexLoadComplete() && manifestManager.isManifestLoadComplete()
    }

    override suspend fun newDirectWriter(options: WriteSessionOptions): DirectRepositoryWriter {
        checkNotClosed()
        check(!clientOptions.readOnly) { "Repository is read-only" }
        requireStorageStillHoldsThisRepository()

        val writerId = writerIdCounter.incrementAndGet()
        val writerName = "writer-$writerId:${options.purpose}"

        // Create a new content manager for this writer session. `onUpload` rides with it because
        // the bytes it reports are this session's blob writes -- it is what makes "Uploaded Bytes"
        // on the Tasks screen the amount that actually left the device.
        val writerContentManager = createContentManager(blobStorage, config, options.onUpload)

        // Create new object and manifest managers backed by the writer's content manager
        val writerObjectManager = ObjectManager(
            contentManager = writerContentManager,
            compressorFactory = compressorFactory,
        )

        val writerManifestManager = ManifestManager(
            contentManager = writerContentManager,
            clock = clock,
        )

        // Load existing manifests
        writerManifestManager.refresh()

        return DirectRepositoryImpl(
            blobStorage = blobStorage,
            contentManager = writerContentManager,
            objectManager = writerObjectManager,
            manifestManager = writerManifestManager,
            config = config,
            uniqueId = uniqueId,
            formatEncryptionKey = formatEncryptionKey,
            clientOptions = clientOptions,
            clock = clock,
            compressorFactory = compressorFactory,
            isWriter = true,
        )
    }

    override fun supportsPasswordChange(): Boolean = config.enablePasswordChange

    // ===== DirectRepositoryWriter Interface =====

    /**
     * Checks, once per write session, that the storage still holds the repository we opened.
     *
     * The format blob is read at connect and never again, while this app keeps one repository
     * connection for a whole session -- so if the storage is swapped underneath it, nothing notices.
     * Measured on a phone (task-65): the repository directory was moved away, the write path's
     * `mkdir -p` recreated it, a run wrote 2.34 GB into it and reported "Backed up 200 files
     * (2.34 GB)", and Go then said "repository not initialized in the provided storage". The
     * dashboard afterwards showed that ghost as the source's only snapshot while the real ones
     * vanished, because retention's `repository.refresh()` re-read from the recreated path.
     *
     * Here rather than in each backend, for three reasons:
     * - It is a repository-level question. A [BlobStorage] is a plain blob store and several are
     *   used as exactly that; giving them an opinion about repository format breaks that.
     * - It covers every backend in one place -- including S3, which has no root directory to check
     *   and so cannot be defended by the backends' own guards.
     * - It catches the storage being **replaced** as well as removed, which a directory-existence
     *   check cannot: a sync client swapping in a fresh empty folder leaves a directory there.
     *
     * One blob read per write session -- noise beside the packs the session is about to upload, and
     * it also covers the case where a run writes nothing at all (`ignoreIdenticalSnapshots`), which
     * would otherwise report success against a repository that is not there.
     */
    private suspend fun requireStorageStillHoldsThisRepository() {
        // Backends differ: some answer null for a missing blob, some throw. Both mean the same here.
        val present = try {
            blobStorage.getBlobMetadata(BlobId(KopiaRepositoryJson.FORMAT_BLOB_ID)) != null
        } catch (@Suppress("SwallowedException") e: BlobNotFoundException) {
            false
        }
        if (!present) {
            throw RepositoryUnavailableException(
                "The backup destination is no longer the repository it was connected to. It may " +
                    "have been moved, deleted, replaced, or its storage unmounted — reconnect to " +
                    "it and try again.",
            )
        }
    }

    override fun blobStorage(): BlobStorage = blobStorage

    override suspend fun deleteContent(contentId: ContentId) {
        checkNotClosed()
        checkWritable()
        contentManager.deleteContent(contentId)
    }

    override suspend fun undeleteContent(contentId: ContentId) {
        checkNotClosed()
        checkWritable()
        contentManager.undeleteContent(contentId)
    }

    // ===== Private Helpers =====

    private fun checkNotClosed() {
        check(!closed.get()) { "Repository has been closed" }
    }

    private fun checkWritable() {
        check(isWriter || !clientOptions.readOnly) { "Repository is read-only" }
    }

    companion object {
        private const val MIN_REPLACE_MANIFEST_TIME_DELTA_MS = 100L

        /**
         * Opens an existing repository with the given password.
         *
         * @param blobStorage The blob storage backend
         * @param password The repository password
         * @param clientOptions Client options for this connection
         * @param clock Clock for timestamps (default: system UTC)
         * @return The opened DirectRepository
         * @throws FormatBlobNotFoundException if format blob doesn't exist
         * @throws InvalidPasswordException if password is incorrect
         */
        suspend fun open(
            blobStorage: BlobStorage,
            password: String,
            clientOptions: ClientOptions = ClientOptions.withDefaults(),
            clock: Clock = Clock.systemUTC(),
        ): DirectRepositoryImpl {
            val formatBlobManager = FormatBlobManager(blobStorage)
            val result = formatBlobManager.openRepository(password)

            return createFromConfig(
                blobStorage = blobStorage,
                result = result,
                clientOptions = clientOptions,
                clock = clock,
            )
        }

        /**
         * Creates a new repository with the given configuration.
         *
         * @param blobStorage The blob storage backend
         * @param password The repository password
         * @param config The repository configuration
         * @param clientOptions Client options for this connection
         * @param clock Clock for timestamps (default: system UTC)
         * @return The created DirectRepository
         * @throws RepositoryAlreadyExistsException if repository exists
         */
        suspend fun create(
            blobStorage: BlobStorage,
            password: String,
            config: RepositoryConfig,
            clientOptions: ClientOptions = ClientOptions.withDefaults(),
            clock: Clock = Clock.systemUTC(),
            keyDerivationAlgorithm: String = KopiaRepositoryJson.DEFAULT_KEY_DERIVATION_ALGORITHM,
        ): DirectRepositoryImpl {
            val formatBlobManager = FormatBlobManager(blobStorage)
            val result = formatBlobManager.createRepository(
                password = password,
                config = config,
                keyDerivationAlgorithm = keyDerivationAlgorithm,
            )

            return createFromConfig(
                blobStorage = blobStorage,
                result = OpenRepositoryResult(
                    formatJson = result.formatJson,
                    config = result.config,
                    formatEncryptionKey = result.formatEncryptionKey,
                ),
                clientOptions = clientOptions,
                clock = clock,
            )
        }

        /**
         * Connects to an existing repository (alias for open).
         */
        suspend fun connect(
            blobStorage: BlobStorage,
            password: String,
            clientOptions: ClientOptions = ClientOptions.withDefaults(),
            clock: Clock = Clock.systemUTC(),
        ): DirectRepositoryImpl = open(blobStorage, password, clientOptions, clock)

        /**
         * Initializes a new repository (alias for create).
         */
        suspend fun initialize(
            blobStorage: BlobStorage,
            password: String,
            config: RepositoryConfig,
            clientOptions: ClientOptions = ClientOptions.withDefaults(),
            clock: Clock = Clock.systemUTC(),
        ): DirectRepositoryImpl = create(blobStorage, password, config, clientOptions, clock)

        private suspend fun createFromConfig(
            blobStorage: BlobStorage,
            result: OpenRepositoryResult,
            clientOptions: ClientOptions,
            clock: Clock,
        ): DirectRepositoryImpl {
            val config = result.config
            val compressorFactory = DefaultCompressorFactory()

            // Create content manager
            val contentManager = createContentManager(blobStorage, config)

            // Load existing content indexes
            contentManager.refresh()

            // Create object manager
            val objectManager = ObjectManager(
                contentManager = contentManager,
                compressorFactory = compressorFactory,
            )

            // Create manifest manager
            val manifestManager = ManifestManager(
                contentManager = contentManager,
                clock = clock,
            )

            // Load existing manifests
            manifestManager.refresh()

            return DirectRepositoryImpl(
                blobStorage = blobStorage,
                contentManager = contentManager,
                objectManager = objectManager,
                manifestManager = manifestManager,
                config = config,
                uniqueId = result.formatJson.uniqueID,
                formatEncryptionKey = result.formatEncryptionKey,
                clientOptions = clientOptions,
                clock = clock,
                compressorFactory = compressorFactory,
                isWriter = false,
            )
        }

        private fun createContentManager(
            blobStorage: BlobStorage,
            config: RepositoryConfig,
            onUpload: ((Long) -> Unit)? = null,
        ): ContentManager {
            val hasherFactory = org.kopiaKt.core.hashing.DefaultContentHasherFactory()
            val encryptorFactory = org.kopiaKt.core.encryption.DefaultEncryptorFactory()
            val compressorFactory = DefaultCompressorFactory()

            // Map hash algorithm name to enum
            val hashAlgorithm = HashAlgorithm.fromString(config.hash)
                ?: throw IllegalArgumentException(HashAlgorithm.unsupportedMessage(config.hash))

            // Map encryption algorithm name to enum
            val encryptionAlgorithm = EncryptionAlgorithm.fromString(config.encryption)
                ?: throw IllegalArgumentException("Unknown encryption algorithm: ${config.encryption}")

            // Parse default compression
            val defaultCompression = CompressionAlgorithm.NONE

            return ContentManager(
                storage = blobStorage,
                hasherFactory = hasherFactory,
                hashAlgorithm = hashAlgorithm,
                hashSecret = config.secret,
                encryptorFactory = encryptorFactory,
                encryptionAlgorithm = encryptionAlgorithm,
                encryptionKey = config.masterKey,
                compressorFactory = compressorFactory,
                defaultCompression = defaultCompression,
                maxPackSize = config.maxPackSize,
                epochsEnabled = config.isEpochIndexEnabled(),
                onUpload = onUpload,
            )
        }
    }
}

/**
 * Executes a write session with automatic flush.
 *
 * @param repository The repository to write to
 * @param options Write session options
 * @param block The code to execute in the write session
 */
suspend fun <T> writeSession(
    repository: Repository,
    options: WriteSessionOptions = WriteSessionOptions(),
    block: suspend (RepositoryWriter) -> T,
): T {
    val writer = repository.newWriter(options)
    var success = false
    try {
        val result = block(writer)
        writer.flush()
        success = true
        return result
    } finally {
        if (!success && options.flushOnFailure) {
            try {
                writer.flush()
            } catch (e: Exception) {
                // Ignore flush errors on failure
            }
        }
        writer.close()
    }
}

/**
 * Executes a direct write session with automatic flush.
 *
 * @param repository The direct repository to write to
 * @param options Write session options
 * @param block The code to execute in the write session
 */
suspend fun <T> directWriteSession(
    repository: DirectRepository,
    options: WriteSessionOptions = WriteSessionOptions(),
    block: suspend (DirectRepositoryWriter) -> T,
): T {
    val writer = repository.newDirectWriter(options)
    var success = false
    try {
        val result = block(writer)
        writer.flush()
        success = true
        return result
    } finally {
        if (!success && options.flushOnFailure) {
            try {
                writer.flush()
            } catch (e: Exception) {
                // Ignore flush errors on failure
            }
        }
        writer.close()
    }
}
