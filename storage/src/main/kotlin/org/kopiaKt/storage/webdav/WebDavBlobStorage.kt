package org.kopiaKt.storage.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.UnsupportedPutOptionException
import org.kopiaKt.storage.tls.TlsTrust
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * WebDAV-based blob storage implementation.
 *
 * This implementation is compatible with Go Kopia's WebDAV storage backend,
 * allowing cross-compatibility between Go and Kotlin implementations.
 * Storage formats are compatible (both use sharded directory structure),
 * so a repository may be accessed using WebDAV or filesystem interchangeably.
 *
 * Uses OkHttp for HTTP transport, which is fully supported on Android
 * (unlike the previous Sardine/Apache HttpClient approach).
 *
 * Features:
 * - HTTP Basic authentication
 * - Sharded directory structure (compatible with Go filesystem backend)
 * - Atomic writes via temp file + rename
 * - Retries with exponential backoff (when wrapped in RetryingBlobStorage)
 */
class WebDavBlobStorage private constructor(
    private val client: OkHttpWebDavClient,
    private val options: WebDavOptions,
    private val shardingParams: ShardingParameters,
    private val readOnly: Boolean,
) : BlobStorage {

    companion object {
        private const val WEBDAV_STORAGE_TYPE = "webdav"
        private const val SHARDS_FILE = ".shards"
        private const val COMPLETE_BLOB_SUFFIX = ".f"
        private const val CONTENT_TYPE = "application/octet-stream"

        /** HTTP 416 Range Not Satisfiable */
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** Maximum directory recursion depth to prevent infinite loops. */
        private const val MAX_WALK_DEPTH = 10

        private val LOGGER = java.util.logging.Logger.getLogger(WebDavBlobStorage::class.java.name)

        /** RFC 1123 date format used by HTTP Last-Modified headers */
        private val HTTP_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.RFC_1123_DATE_TIME

        private val json = Json { ignoreUnknownKeys = true }

        /** Full-entropy source for non-atomic-write temp-file suffixes. */
        private val secureRandom = SecureRandom()

        /**
         * Creates a new WebDAV blob storage instance.
         *
         * @param options WebDAV connection options
         * @param isCreate Whether this is creating a new repository
         * @param readOnly If true, the storage will be in read-only mode
         * @return A new WebDavBlobStorage instance
         */
        suspend fun create(
            options: WebDavOptions,
            isCreate: Boolean = false,
            readOnly: Boolean = false,
        ): WebDavBlobStorage = withContext(Dispatchers.IO) {
            requireSupportedOptions(options)
            val client = createClient(options)
            val shardingParams = try {
                loadOrCreateShardingParams(client, options, isCreate)
            } catch (t: Throwable) {
                // Nothing else owns the client on this path, and callers only ever close a storage
                // they were handed -- so a failure here would strand its connection pool. This is
                // the wrong-credentials path, which Test Connection invites users to hit repeatedly.
                client.shutdown()
                throw t
            }
            WebDavBlobStorage(client, options, shardingParams, readOnly)
        }

        /**
         * Creates a new WebDAV blob storage with a custom client (for testing).
         */
        internal fun createWithClient(
            client: OkHttpWebDavClient,
            options: WebDavOptions,
            shardingParams: ShardingParameters = ShardingParameters(),
            readOnly: Boolean = false,
        ): WebDavBlobStorage {
            requireSupportedOptions(options)
            return WebDavBlobStorage(client, options, shardingParams, readOnly)
        }

        /**
         * Validates the options this backend must not silently ignore.
         *
         * `trustedServerCertificateFingerprint` IS supported (see [OkHttpWebDavClient]); it is only
         * checked here for well-formedness so a malformed pin fails at connect time with a clear
         * message instead of as an opaque TLS handshake error.
         */
        private fun requireSupportedOptions(options: WebDavOptions) {
            if (options.trustedServerCertificateFingerprint.isNotEmpty()) {
                TlsTrust.normalizeSha256Fingerprint(options.trustedServerCertificateFingerprint)
                // Fail closed instead of silently ignoring the pin: over http there is no TLS
                // handshake to pin, so the user would believe the connection is protected while
                // credentials travel in the clear.
                require(options.url.trim().startsWith("https://", ignoreCase = true)) {
                    "trustedServerCertificateFingerprint requires an https:// URL " +
                        "(a certificate pin has no effect over cleartext http)"
                }
            }
        }

        private fun createClient(options: WebDavOptions): OkHttpWebDavClient = OkHttpWebDavClient(
            username = options.username,
            password = options.password,
            trustedServerCertificateFingerprint = options.trustedServerCertificateFingerprint,
        )

        private suspend fun loadOrCreateShardingParams(
            client: OkHttpWebDavClient,
            options: WebDavOptions,
            isCreate: Boolean,
        ): ShardingParameters = withContext(Dispatchers.IO) {
            val shardsUrl = normalizeUrl(options.url) + SHARDS_FILE

            try {
                // Try to load existing sharding parameters
                val inputStream = client.get(shardsUrl)
                inputStream.use { stream ->
                    val content = stream.readBytes().toString(Charsets.UTF_8)
                    json.decodeFromString<ShardingParameters>(content)
                }
            } catch (e: WebDavException) {
                if (e.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    // No shards file exists, create default parameters
                    val defaultShards = if (isCreate) {
                        listOf(1, 3)
                    } else {
                        listOf(3, 3)
                    }

                    val params = ShardingParameters(
                        default = defaultShards,
                        maxNonShardedLength = options.maxNonShardedLength,
                    )

                    // Persist ONLY when legitimately creating the repository, matching SFTP.
                    // Opening must not write: the open-time guess is [3,3] and the create-time
                    // default is [1,3], so persisting it here would silently decide the layout of a
                    // repository that does not exist yet -- and merely testing a connection would
                    // leave a file on a server the user may never adopt.
                    if (isCreate) {
                        try {
                            val content = json.encodeToString(ShardingParameters.serializer(), params)
                            client.put(shardsUrl, content.toByteArray(Charsets.UTF_8))
                        } catch (_: Exception) {
                            // Ignore errors when persisting - it's not critical
                        }
                    }

                    params
                } else {
                    throw e
                }
            }
        }

        private fun normalizeUrl(url: String): String = if (url.endsWith("/")) url else "$url/"
    }

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray = withContext(Dispatchers.IO) {
        if (offset < 0) {
            throw InvalidBlobRangeException("Offset cannot be negative: $offset")
        }

        val filePath = getFilePath(blobId)
        val fileUrl = normalizeUrl(options.url) + filePath

        try {
            val inputStream: InputStream = when {
                length == 0L -> {
                    // Zero-length read - verify file exists by reading 1 byte range
                    try {
                        client.get(fileUrl, mapOf("Range" to "bytes=$offset-$offset")).use { }
                    } catch (e: WebDavException) {
                        if (e.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                            throw BlobNotFoundException(blobId)
                        }
                        throw e
                    }
                    return@withContext ByteArray(0)
                }
                length < 0 -> {
                    // Read from offset to end
                    if (offset > 0) {
                        client.get(fileUrl, mapOf("Range" to "bytes=$offset-"))
                    } else {
                        client.get(fileUrl)
                    }
                }
                else -> {
                    // Read specific range
                    client.get(fileUrl, mapOf("Range" to "bytes=$offset-${offset + length - 1}"))
                }
            }

            inputStream.use { stream ->
                val bytes = stream.readBytes()

                // A fixed-length ranged read must return exactly `length` bytes. An empty body
                // means the range is beyond EOF; any other wrong size means the server
                // mishandled the Range header (e.g. ignored it and returned the whole file — a
                // 200 instead of 206), in which case `bytes` is the wrong content entirely.
                // Returning it as-is would hand back silently-wrong data for a content-addressed
                // blob, so fail loudly instead.
                if (length > 0 && bytes.size.toLong() != length) {
                    if (bytes.isEmpty()) {
                        throw InvalidBlobRangeException(
                            "Requested offset $offset is beyond blob size",
                        )
                    }
                    throw InvalidBlobRangeException(
                        "Expected $length bytes for the range at offset $offset " +
                            "but the server returned ${bytes.size}",
                    )
                }

                bytes
            }
        } catch (e: WebDavException) {
            handleWebDavException(e, blobId)
        }
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? = withContext(Dispatchers.IO) {
        val filePath = getFilePath(blobId)
        val fileUrl = normalizeUrl(options.url) + filePath

        try {
            val resources = client.list(fileUrl, 0)
            if (resources.isEmpty()) {
                return@withContext null
            }

            val resource = resources.first()
            BlobMetadata(
                blobId = blobId,
                length = resource.contentLength,
                timestamp = resolveTimestamp(resource.lastModified, blobId.value),
            )
        } catch (e: WebDavException) {
            if (e.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                null
            } else {
                handleWebDavException(e, blobId)
            }
        }
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        val rootUrl = normalizeUrl(options.url)

        // Collect all metadata first in IO context, then emit
        val results = withContext(Dispatchers.IO) {
            val collected = mutableListOf<BlobMetadata>()
            walkDirectory(rootUrl, "", prefix, collected, depth = 0)
            collected
        }

        for (metadata in results) {
            emit(metadata)
        }
    }

    /**
     * Recursively walks the WebDAV directory tree to collect blob metadata.
     *
     * @param depth current recursion depth; capped at [MAX_WALK_DEPTH] to prevent
     *              infinite recursion from symlink loops or server bugs.
     */
    private fun walkDirectory(
        dirUrl: String,
        currentPrefix: String,
        filterPrefix: String,
        results: MutableList<BlobMetadata>,
        depth: Int,
    ) {
        if (depth > MAX_WALK_DEPTH) {
            LOGGER.warning("WebDAV listing truncated at depth $MAX_WALK_DEPTH under $dirUrl (possible cycle)")
            return
        }

        try {
            val resources = client.list(dirUrl, 1) // Depth 1 to list immediate children

            for (resource in resources) {
                // Skip the directory itself.
                // WebDAV servers may return absolute paths (e.g. "/") in href
                // while dirUrl is a full URL (e.g. "http://host:port/"),
                // so we compare only the path components.
                if (hrefMatchesUrl(resource.href, dirUrl)) {
                    continue
                }

                if (resource.isDirectory) {
                    // Recursively walk subdirectories that could match prefix
                    val subDirName = resource.name

                    // Skip empty, ".", or ".." directory names
                    if (subDirName.isEmpty() || subDirName == "." || subDirName == "..") {
                        continue
                    }

                    val newPrefix = currentPrefix + subDirName

                    val shouldDescend = if (filterPrefix.length > newPrefix.length) {
                        filterPrefix.startsWith(newPrefix)
                    } else {
                        newPrefix.startsWith(filterPrefix)
                    }

                    if (shouldDescend) {
                        walkDirectory(
                            "$dirUrl$subDirName/",
                            newPrefix,
                            filterPrefix,
                            results,
                            depth + 1,
                        )
                    }
                } else {
                    // This is a file - check if it matches our criteria
                    val fileName = resource.name
                    if (!fileName.endsWith(COMPLETE_BLOB_SUFFIX)) {
                        continue
                    }

                    // Extract blob ID from file name
                    val blobIdSuffix = fileName.removeSuffix(COMPLETE_BLOB_SUFFIX)
                    val fullBlobId = currentPrefix + blobIdSuffix

                    if (fullBlobId.startsWith(filterPrefix)) {
                        results.add(
                            BlobMetadata(
                                blobId = BlobId(fullBlobId),
                                length = resource.contentLength,
                                timestamp = resolveTimestamp(resource.lastModified, fullBlobId),
                            ),
                        )
                    }
                }
            }
        } catch (e: WebDavException) {
            if (e.statusCode != HttpURLConnection.HTTP_NOT_FOUND) {
                throw e
            }
            // Directory doesn't exist - that's fine, no blobs to list
        }
    }

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) = withContext(Dispatchers.IO) {
        if (readOnly) {
            throw IllegalStateException("Storage is read-only")
        }
        // WebDAV doesn't support retention options
        if (options.retentionMode != org.kopiaKt.core.blob.RetentionMode.NONE) {
            throw UnsupportedPutOptionException("blob-retention")
        }

        // WebDAV doesn't support setModTime
        if (options.setModTime != null) {
            throw UnsupportedPutOptionException("setModTime")
        }

        val filePath = getFilePath(blobId)
        val fileUrl = normalizeUrl(this@WebDavBlobStorage.options.url) + filePath
        val dirPath = getDirPath(blobId)
        val dirUrl = normalizeUrl(this@WebDavBlobStorage.options.url) + dirPath

        // Check for existing blob if dontOverwrite is set
        if (options.dontOverwrite) {
            try {
                val resources = client.list(fileUrl, 0)
                if (resources.isNotEmpty()) {
                    return@withContext // Blob exists, don't overwrite
                }
            } catch (e: WebDavException) {
                if (e.statusCode != HttpURLConnection.HTTP_NOT_FOUND) {
                    handleWebDavException(e, blobId)
                }
                // Not found is fine - we'll create it
            }
        }

        // Determine write path
        val writePath = if (this@WebDavBlobStorage.options.atomicWrites) {
            fileUrl
        } else {
            // A full 64-bit SecureRandom suffix (not Math.random) so two concurrent PUTs of the
            // same blob are vanishingly unlikely to collide on the temp-file name and clobber
            // each other's in-flight write.
            "$fileUrl-${System.currentTimeMillis()}-${secureRandom.nextLong().toULong()}"
        }

        try {
            // Try to write the file
            try {
                client.put(writePath, data, CONTENT_TYPE)
            } catch (e: WebDavException) {
                // If write failed, try creating parent directories and retry
                if (dirPath.isNotEmpty()) {
                    try {
                        mkdirAll(dirUrl)
                        client.put(writePath, data, CONTENT_TYPE)
                    } catch (retryError: Exception) {
                        throw e.withRepairFailure(retryError)
                    }
                } else {
                    throw e
                }
            }

            // If not atomic writes, rename temp file to final location
            if (!this@WebDavBlobStorage.options.atomicWrites) {
                client.move(writePath, fileUrl, true)
            }
        } catch (e: WebDavException) {
            handleWebDavException(e, blobId)
        }
    }

    override suspend fun deleteBlob(blobId: BlobId) = withContext(Dispatchers.IO) {
        if (readOnly) {
            throw IllegalStateException("Storage is read-only")
        }
        val filePath = getFilePath(blobId)
        val fileUrl = normalizeUrl(options.url) + filePath

        try {
            client.delete(fileUrl)
        } catch (e: WebDavException) {
            // Ignore "not found" errors - blob is already deleted
            if (e.statusCode != HttpURLConnection.HTTP_NOT_FOUND) {
                handleWebDavException(e, blobId)
            }
        }
        Unit
    }

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = WEBDAV_STORAGE_TYPE,
        config = buildMap {
            put("url", options.url)
            if (options.username.isNotEmpty()) put("username", options.username)
        },
    )

    override fun displayName(): String = "WebDAV: ${options.url}"

    override fun isReadOnly(): Boolean = readOnly

    override suspend fun close() {
        client.shutdown()
    }

    override suspend fun flushCaches() {
        // WebDAV operations are immediately persisted
    }

    /**
     * Compares a WebDAV href with a full URL by extracting and comparing their
     * path components. WebDAV servers typically return absolute paths in href
     * elements (e.g. "/", "/.shards") rather than full URLs, so a direct string
     * comparison against the request URL would fail.
     */
    private fun hrefMatchesUrl(href: String, url: String): Boolean {
        val hrefPath = try {
            URI(href).normalize().path.orEmpty().removeSuffix("/")
        } catch (_: Exception) {
            href.removeSuffix("/")
        }
        val urlPath = try {
            URI(url).normalize().path.orEmpty().removeSuffix("/")
        } catch (_: Exception) {
            url.removeSuffix("/")
        }
        return hrefPath == urlPath
    }

    /**
     * Creates all directories in the path, similar to mkdir -p.
     */
    private fun mkdirAll(dirUrl: String) {
        val rootUrl = normalizeUrl(options.url)
        val relativePath = dirUrl.removePrefix(rootUrl).removeSuffix("/")
        if (relativePath.isEmpty()) return

        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        var currentPath = rootUrl

        for (part in parts) {
            currentPath = "$currentPath$part/"
            try {
                // Check if directory exists
                client.list(currentPath, 0)
            } catch (e: WebDavException) {
                if (e.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    createCollection(currentPath)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * MKCOL on [url], reading its status codes the way RFC 4918 §9.3.1 actually defines them.
     *
     * They were the wrong way round here. **405 Method Not Allowed** means the URL is already
     * MAPPED — MKCOL may only be executed on an unmapped one — while **409 Conflict** means an
     * intermediate collection does not exist. Strictly, 405 says "mapped to something", not
     * necessarily to a collection; treating it as already-exists is still right here, because the
     * retried PUT is what arbitrates. If the URL is mapped to a plain file, or the server simply
     * does not implement MKCOL, that PUT fails and the write reports it — there is no path on which
     * a 405 turns a failed write into a silent success. The old code swallowed 409 as already-exists, so the
     * one status that means the directory could NOT be created was read as success: `mkdirAll`
     * returned having created nothing and the caller reported its original PUT failure instead of
     * the reason it could not be repaired. And it rethrew 405, aborting writes into collections that
     * were simply already present — which is every level of a path that another writer got to first.
     */
    private fun createCollection(url: String) {
        try {
            client.createDirectory(url)
        } catch (createError: WebDavException) {
            when (createError.statusCode) {
                HttpURLConnection.HTTP_BAD_METHOD -> Unit // already exists; keep walking down
                HttpURLConnection.HTTP_CONFLICT -> throw WebDavException(
                    "Cannot create $url: a parent collection does not exist on the server " +
                        "(MKCOL 409). Check that ${options.url} is still present.",
                    createError.statusCode,
                    createError,
                )
                else -> throw createError
            }
        }
    }

    /**
     * Records why a write could not be repaired on the failure actually being reported.
     *
     * `putBlob` reports the original write error — that is the operation the caller asked for — but
     * used to discard the mkdir failure entirely, which is how a missing parent collection surfaced
     * as an unexplained PUT error (task-68). Self-suppression is an `IllegalArgumentException`, hence
     * the identity check.
     */
    private fun WebDavException.withRepairFailure(repairError: Throwable): WebDavException = apply {
        if (repairError !== this) addSuppressed(repairError)
    }

    /**
     * Gets the shards to use for a blob ID based on prefix overrides.
     */
    private fun getShardsForBlobId(blobId: BlobId): List<Int> {
        val id = blobId.value
        for (override in shardingParams.overrides) {
            if (id.startsWith(override.prefix)) {
                return override.shards
            }
        }
        return shardingParams.default
    }

    /**
     * Gets the directory path and remaining blob ID for sharding.
     */
    private fun getShardedPath(blobId: BlobId): Pair<String, String> {
        var id = blobId.value
        var shardPath = ""

        // Short blob IDs are not sharded
        if (id.length <= shardingParams.maxNonShardedLength) {
            return Pair(shardPath, id)
        }

        for (size in getShardsForBlobId(blobId)) {
            if (id.length <= size) {
                break
            }
            shardPath = if (shardPath.isEmpty()) {
                id.substring(0, size)
            } else {
                "$shardPath/${id.substring(0, size)}"
            }
            id = id.substring(size)
        }

        return Pair(shardPath, id)
    }

    /**
     * Gets the directory path for a blob.
     */
    private fun getDirPath(blobId: BlobId): String = getShardedPath(blobId).first

    /**
     * Gets the full file path for a blob (including .f suffix).
     */
    private fun getFilePath(blobId: BlobId): String {
        val (dirPath, remainingId) = getShardedPath(blobId)
        val fileName = "$remainingId$COMPLETE_BLOB_SUFFIX"
        return if (dirPath.isEmpty()) fileName else "$dirPath/$fileName"
    }

    /**
     * Parses a Last-Modified date string (RFC 1123 format) into an [Instant].
     *
     * @return the parsed instant, or null if the string is null or unparseable
     */
    private fun parseLastModified(dateStr: String?): Instant? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            ZonedDateTime.parse(dateStr, HTTP_DATE_FORMAT).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Resolves a blob's timestamp from the server's Last-Modified header, falling back to
     * [Instant.now] when it is absent or unparseable.
     *
     * `now()` is a deliberate, conservative choice: a fabricated "just now" makes the blob look
     * *young* to age-based maintenance, so it is never prematurely reaped, whereas a zero/epoch
     * sentinel would look ancient and could be deleted early. The fallback is logged (not silent)
     * because it means the server omitted a timestamp — rare, since WebDAV `getlastmodified` is
     * near-universal, but worth surfacing since fabricated times feed maintenance.
     */
    private fun resolveTimestamp(lastModified: String?, blobId: String): Instant {
        parseLastModified(lastModified)?.let { return it }
        LOGGER.fine("WebDAV blob $blobId has no parseable Last-Modified; using current time for maintenance")
        return Instant.now()
    }

    private fun handleWebDavException(e: WebDavException, blobId: BlobId): Nothing {
        when (e.statusCode) {
            // Carry the original: on a WRITE this conversion is the last thing that happens, so
            // without it the "a parent collection does not exist" diagnosis that mkdirAll now
            // attaches (see withRepairFailure) is dropped on the floor for any server dialect that
            // answers 404 rather than 409 to a PUT into a missing collection. Both reviewers found
            // this. The TYPE is deliberately unchanged, so retry and terminal classification are
            // exactly as before.
            HttpURLConnection.HTTP_NOT_FOUND -> throw BlobNotFoundException(blobId, e)
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                throw InvalidCredentialsException(e.message ?: "Authentication failed")
            HTTP_RANGE_NOT_SATISFIABLE ->
                throw InvalidBlobRangeException("Invalid range for blob: $blobId")
            else -> throw e
        }
    }
}
