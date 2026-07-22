package org.kopiaKt.storage.sftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.HostKeyNotTrustedException
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.RetentionMode
import org.kopiaKt.core.blob.UnsupportedPutOptionException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.PublicKey
import java.security.SecureRandom
import java.time.Instant
import java.util.EnumSet

/**
 * SFTP-based blob storage implementation.
 *
 * This implementation is compatible with Go Kopia's SFTP storage backend,
 * allowing cross-compatibility between Go and Kotlin implementations.
 * Uses the same sharded directory structure as the filesystem backend.
 *
 * Features:
 * - SSH password authentication
 * - SSH key authentication (private key file or inline key data)
 * - Known hosts verification
 * - Sharded directory structure (compatible with Go filesystem/SFTP backend)
 * - Atomic writes via temp file + rename
 * - Connection pooling with automatic reconnection
 */
class SftpBlobStorage private constructor(
    private val options: SftpOptions,
    private val readOnly: Boolean,
    private val shardsConfig: SftpShardsConfig,
) : BlobStorage {

    companion object {
        private const val SFTP_STORAGE_TYPE = "sftp"
        private const val COMPLETE_BLOB_SUFFIX = ".f"
        private const val TEMP_FILE_RANDOM_SUFFIX_LEN = 8
        private const val MAX_NON_SHARDED_LENGTH = 20
        private const val SHARDS_FILE = ".shards"

        /** Upper bound on a `.shards` file we will read into memory (it is a tiny JSON blob). */
        private const val MAX_SHARDS_FILE_BYTES = 64 * 1024

        /** Chunk size for reading the (small) `.shards` file to EOF. */
        private const val READ_CHUNK_BYTES = 8 * 1024

        /** Maximum directory recursion depth to prevent infinite loops (mirrors WebDAV). */
        private const val MAX_WALK_DEPTH = 10

        private val LOGGER = java.util.logging.Logger.getLogger(SftpBlobStorage::class.java.name)

        /**
         * Applies the connect and socket-read timeouts from [options] to a freshly-created SSH client,
         * before it connects. Kept as a separate helper so the timeout wiring is unit-testable without
         * opening a real connection. A non-positive value leaves the sshj default in place.
         */
        internal fun applyConnectionTimeouts(ssh: SSHClient, options: SftpOptions) {
            if (options.connectTimeoutMillis > 0) {
                ssh.connectTimeout = options.connectTimeoutMillis
            }
            if (options.socketTimeoutMillis > 0) {
                ssh.timeout = options.socketTimeoutMillis
            }
        }

        /**
         * Creates a new SFTP blob storage instance.
         *
         * @param options SFTP connection options
         * @param isCreate Whether this is creating a new repository
         * @param readOnly If true, the storage will be in read-only mode
         * @return A new SftpBlobStorage instance
         */
        suspend fun create(
            options: SftpOptions,
            isCreate: Boolean = false,
            readOnly: Boolean = false,
        ): SftpBlobStorage = withContext(Dispatchers.IO) {
            // Fallback layout used ONLY when the repo has no `.shards` file (legacy). Go parity:
            // create → [1,3], open → [3,3]; a caller-supplied list (incl. empty = flat) wins.
            val fallback = SftpShardsConfig(
                default = SftpSharding.fallbackShards(isCreate, options.directoryShards),
            )

            // Bootstrap: ensure the root exists and read the repo's authoritative `.shards`. The repo's
            // `.shards` is the source of truth for its on-disk layout — including a flat (unsharded)
            // repo whose default is []. Ignoring it computes wrong paths and reads the repo as empty.
            val bootstrap = SftpBlobStorage(options, readOnly, fallback)
            val resolved = try {
                bootstrap.withSftpClient { sftp ->
                    try {
                        sftp.stat(options.path)
                    } catch (e: SFTPException) {
                        if (isNotExist(e)) mkdirAll(sftp, options.path) else throw e
                    }

                    readShardsConfig(sftp, options.path) ?: run {
                        // No `.shards` yet — persist ours only when legitimately creating the repo, so
                        // merely opening an existing (legacy) repo never mutates it. Fail loud if the
                        // create-time write fails: a repo without `.shards` is a latent Go-compat hazard
                        // (Go would infer a different layout for it).
                        if (!readOnly && isCreate) {
                            writeShardsConfig(sftp, options.path, fallback)
                        }
                        fallback
                    }
                }
            } catch (t: Throwable) {
                // Nothing else owns the bootstrap's open connection on the failure path — close it.
                bootstrap.close()
                throw t
            }

            // Build the storage with the resolved sharding, reusing the already-open connection.
            SftpBlobStorage(options, readOnly, resolved).also {
                it.cachedSshClient = bootstrap.cachedSshClient
                it.cachedSftpClient = bootstrap.cachedSftpClient
            }
        }

        /**
         * Creates a storage instance with pre-configured connections (for testing).
         */
        internal fun createWithConnections(
            options: SftpOptions,
            sshClient: SSHClient,
            sftpClient: SFTPClient,
            readOnly: Boolean = false,
            directoryShards: List<Int> = listOf(1, 3),
            maxNonShardedLength: Int = MAX_NON_SHARDED_LENGTH,
        ): SftpBlobStorage = SftpBlobStorage(
            options,
            readOnly,
            SftpShardsConfig(default = directoryShards, maxNonShardedLength = maxNonShardedLength),
        ).apply {
            this.cachedSshClient = sshClient
            this.cachedSftpClient = sftpClient
        }

        private fun isNotExist(e: Exception): Boolean {
            if (e is SFTPException) {
                return e.statusCode == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE
            }
            return e.message?.contains("does not exist") == true ||
                e.message?.contains("No such file") == true
        }

        private fun mkdirAll(sftp: SFTPClient, path: String) {
            val parts = path.split("/").filter { it.isNotEmpty() }
            var currentPath = if (path.startsWith("/")) "/" else ""

            for (part in parts) {
                currentPath = if (currentPath.isEmpty() || currentPath == "/") {
                    "$currentPath$part"
                } else {
                    "$currentPath/$part"
                }

                try {
                    sftp.stat(currentPath)
                } catch (e: SFTPException) {
                    if (isNotExist(e)) {
                        sftp.mkdir(currentPath)
                    } else {
                        throw e
                    }
                }
            }
        }

        /**
         * Reads and parses the repo's `.shards` file. Returns null ONLY when the file does not exist
         * (a legacy repo → caller falls back). A file that exists but is blank or unparseable THROWS:
         * silently guessing the layout would reintroduce the wrong-path / empty-repo bug for a
         * content-addressed store. The whole (tiny) file is read to EOF rather than trusting a reported
         * length, since some SFTP servers report length 0.
         */
        private fun readShardsConfig(sftp: SFTPClient, path: String): SftpShardsConfig? {
            val shardsPath = "$path/$SHARDS_FILE"
            val content = try {
                sftp.open(shardsPath, EnumSet.of(OpenMode.READ)).use { file ->
                    readToEnd(file, shardsPath)
                }
            } catch (e: SFTPException) {
                if (isNotExist(e)) return null else throw e
            }
            if (content.isBlank()) {
                throw IOException("$SHARDS_FILE at $shardsPath exists but is empty")
            }
            return SftpSharding.parse(content)
        }

        /** Reads [file] fully to EOF (not trusting its reported length), capped at [MAX_SHARDS_FILE_BYTES]. */
        private fun readToEnd(file: RemoteFile, shardsPath: String): String {
            val buffer = ByteArray(READ_CHUNK_BYTES)
            val out = ByteArrayOutputStream()
            var offset = 0L
            while (true) {
                val read = file.read(offset, buffer, 0, buffer.size)
                if (read <= 0) break
                out.write(buffer, 0, read)
                offset += read
                if (out.size() > MAX_SHARDS_FILE_BYTES) {
                    throw IOException("$SHARDS_FILE at $shardsPath exceeds $MAX_SHARDS_FILE_BYTES bytes")
                }
            }
            return out.toString(Charsets.UTF_8)
        }

        private fun writeShardsConfig(sftp: SFTPClient, path: String, config: SftpShardsConfig) {
            val bytes = SftpSharding.encode(config).toByteArray(Charsets.UTF_8)
            sftp.open("$path/$SHARDS_FILE", EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
                .use { file -> file.write(0, bytes, 0, bytes.size) }
        }
    }

    private val connectionMutex = Mutex()
    private var cachedSshClient: SSHClient? = null
    private var cachedSftpClient: SFTPClient? = null
    private val random = SecureRandom()

    private suspend fun <T> withSftpClient(block: (SFTPClient) -> T): T = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            val sftp = getOrCreateSftpClient()
            try {
                block(sftp)
            } catch (e: IOException) {
                // Connection might be lost, close and retry once
                closeCachedConnection()
                val newSftp = getOrCreateSftpClient()
                block(newSftp)
            }
        }
    }

    private fun getOrCreateSftpClient(): SFTPClient {
        cachedSftpClient?.let { return it }

        val ssh = createSshClient()
        cachedSshClient = ssh

        val sftp = ssh.newSFTPClient()
        cachedSftpClient = sftp

        return sftp
    }

    private fun createSshClient(): SSHClient {
        if (options.externalSSH) {
            throw UnsupportedOperationException("External SSH command is not supported in Kotlin implementation")
        }

        val ssh = SSHClient()
        applyConnectionTimeouts(ssh, options)

        // Configure host key verification
        val verifier = createHostKeyVerifier()
        ssh.addHostKeyVerifier(verifier)

        // Connect
        ssh.connect(options.host, options.port)

        // Authenticate
        try {
            when {
                options.password.isNotEmpty() -> {
                    ssh.authPassword(options.username, options.password)
                }
                options.keyData.isNotEmpty() -> {
                    val keyProvider = ssh.loadKeys(options.keyData, null, null)
                    ssh.authPublickey(options.username, keyProvider)
                }
                options.keyfile.isNotEmpty() -> {
                    val keyProvider = ssh.loadKeys(options.keyfile)
                    ssh.authPublickey(options.username, keyProvider)
                }
                else -> {
                    // Try default key locations
                    ssh.authPublickey(options.username)
                }
            }
        } catch (e: Exception) {
            ssh.close()
            throw InvalidCredentialsException("SSH authentication failed: ${e.message}")
        }

        return ssh
    }

    /**
     * Builds the SSH host-key verifier, **failing closed** by default: if no host-key material is
     * provided (known_hosts data/file or a pinned fingerprint) the connection is rejected rather than
     * trusting any key. Trusting an unknown host key exposes SFTP credentials and backup data to MITM.
     * The insecure "trust anything" path is reachable only when the caller explicitly opts in via
     * [SftpOptions.insecureSkipHostKeyVerification] (dev/testing only — must be gated out of release).
     */
    private fun createHostKeyVerifier(): HostKeyVerifier = when {
        options.knownHostsData.isNotEmpty() -> {
            // Create temporary file with known hosts data
            val tempFile = File.createTempFile("kopia-known-hosts", ".tmp")
            tempFile.deleteOnExit()
            tempFile.writeText(options.knownHostsData)
            OpenSSHKnownHosts(tempFile)
        }
        options.hostKeyFingerprint.isNotEmpty() ->
            FingerprintVerifier.getInstance(options.hostKeyFingerprint)
        File(options.effectiveKnownHostsFile()).exists() ->
            OpenSSHKnownHosts(File(options.effectiveKnownHostsFile()))
        options.insecureSkipHostKeyVerification ->
            // Explicit opt-in ONLY. Trusts any server key — MITM-exposed; never in release builds.
            PromiscuousVerifier()
        else -> throw HostKeyNotTrustedException(
            "SFTP host key for ${options.host}:${options.port} is not trusted: no knownHostsData, " +
                "no known_hosts file, and no hostKeyFingerprint. Provide one of those, or set " +
                "insecureSkipHostKeyVerification=true for local testing only.",
        )
    }

    private fun closeCachedConnection() {
        try {
            cachedSftpClient?.close()
        } catch (_: Exception) {
        }
        try {
            cachedSshClient?.disconnect()
        } catch (_: Exception) {
        }
        cachedSftpClient = null
        cachedSshClient = null
    }

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray = withSftpClient { sftp ->
        if (offset < 0) {
            throw InvalidBlobRangeException("Offset cannot be negative: $offset")
        }

        val fullPath = getFullPath(blobId)

        try {
            val file = sftp.open(fullPath, EnumSet.of(OpenMode.READ))
            file.use { remoteFile ->
                val fileSize = remoteFile.length()

                if (offset >= fileSize && fileSize > 0) {
                    throw InvalidBlobRangeException("Offset $offset is beyond file size $fileSize")
                }

                when {
                    length == 0L -> {
                        // Zero-length read - just verify file exists
                        ByteArray(0)
                    }
                    length < 0 -> {
                        // Read from offset to end
                        readFully(remoteFile, offset, fileSize - offset)
                    }
                    else -> {
                        // Read a specific length
                        readFully(remoteFile, offset, length)
                    }
                }
            }
        } catch (e: SFTPException) {
            if (isNotExist(e)) {
                throw BlobNotFoundException(blobId)
            }
            throw e
        }
    }

    /**
     * Reads exactly [bytesToRead] bytes from [remoteFile] starting at [fileOffset].
     *
     * Throws [InvalidBlobRangeException] on a short read so a truncated read never silently
     * masquerades as complete blob data — both getBlob length branches route through here. The
     * open-ended branch previously returned the partial buffer instead of failing, which for a
     * content-addressed backup store means a silently corrupt restore.
     *
     * [bytesToRead] is a Long computed and validated before narrowing to Int: a non-positive value
     * means a read at/past EOF and yields an empty result (matching Go kopia fs semantics), while a
     * value beyond [Int.MAX_VALUE] (a >2 GiB range that can't fit one JVM array) fails loudly rather
     * than overflowing to a negative Int and silently returning empty.
     */
    private fun readFully(remoteFile: RemoteFile, fileOffset: Long, bytesToRead: Long): ByteArray {
        if (bytesToRead <= 0) {
            return ByteArray(0)
        }
        if (bytesToRead > Int.MAX_VALUE) {
            throw InvalidBlobRangeException("Requested read is too large: $bytesToRead bytes")
        }
        val size = bytesToRead.toInt()
        val buffer = ByteArray(size)
        var totalRead = 0
        while (totalRead < size) {
            val read = remoteFile.read(fileOffset + totalRead, buffer, totalRead, size - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        if (totalRead < size) {
            throw InvalidBlobRangeException("Could only read $totalRead bytes, expected $size")
        }
        return buffer
    }

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? = withSftpClient { sftp ->
        val fullPath = getFullPath(blobId)

        try {
            val attrs = sftp.stat(fullPath)
            BlobMetadata(
                blobId = blobId,
                length = attrs.size,
                timestamp = Instant.ofEpochSecond(attrs.mtime),
            )
        } catch (e: SFTPException) {
            if (isNotExist(e)) {
                null
            } else {
                throw e
            }
        }
    }

    override suspend fun listBlobs(prefix: String): Flow<BlobMetadata> = flow {
        val results = withSftpClient { sftp ->
            val collected = mutableListOf<BlobMetadata>()
            walkDirectory(sftp, options.path, "", prefix, collected, depth = 0)
            collected
        }

        for (metadata in results) {
            emit(metadata)
        }
    }

    private fun walkDirectory(
        sftp: SFTPClient,
        dirPath: String,
        currentPrefix: String,
        filterPrefix: String,
        results: MutableList<BlobMetadata>,
        depth: Int,
    ) {
        // Bound recursion so a server-side symlink cycle can't StackOverflow the walk (mirrors
        // WebDAV). Real repos are only 1-2 shard levels deep, so the cap is never hit legitimately.
        if (depth > MAX_WALK_DEPTH) {
            LOGGER.warning("SFTP listing truncated at depth $MAX_WALK_DEPTH under $dirPath (possible cycle)")
            return
        }

        try {
            val entries = sftp.ls(dirPath)

            for (entry in entries) {
                val name = entry.name
                if (name == "." || name == "..") continue

                if (entry.isDirectory) {
                    // Recursively walk subdirectories that could match prefix
                    val newPrefix = currentPrefix + name

                    val shouldDescend = if (filterPrefix.length > newPrefix.length) {
                        filterPrefix.startsWith(newPrefix)
                    } else {
                        newPrefix.startsWith(filterPrefix)
                    }

                    if (shouldDescend) {
                        walkDirectory(
                            sftp,
                            "$dirPath/$name",
                            newPrefix,
                            filterPrefix,
                            results,
                            depth + 1,
                        )
                    }
                } else {
                    // This is a file - check if it matches our criteria
                    if (!name.endsWith(COMPLETE_BLOB_SUFFIX)) {
                        continue
                    }

                    // Extract blob ID from file name
                    val blobIdSuffix = name.removeSuffix(COMPLETE_BLOB_SUFFIX)
                    val fullBlobId = currentPrefix + blobIdSuffix

                    if (fullBlobId.startsWith(filterPrefix)) {
                        results.add(
                            BlobMetadata(
                                blobId = BlobId(fullBlobId),
                                length = entry.attributes.size,
                                timestamp = Instant.ofEpochSecond(entry.attributes.mtime),
                            ),
                        )
                    }
                }
            }
        } catch (e: SFTPException) {
            if (!isNotExist(e)) {
                throw e
            }
            // Directory doesn't exist - that's fine, no blobs to list
        }
    }

    override suspend fun putBlob(blobId: BlobId, data: ByteArray, options: PutBlobOptions) {
        if (readOnly) {
            throw IllegalStateException("Storage is read-only")
        }
        withSftpClient { sftp ->
            // SFTP doesn't support retention options
            if (options.retentionMode != RetentionMode.NONE) {
                throw UnsupportedPutOptionException("blob-retention")
            }

            val fullPath = getFullPath(blobId)
            val dirPath = getDirPath(blobId)

            // Check for existing blob if dontOverwrite is set
            if (options.dontOverwrite) {
                try {
                    sftp.stat(fullPath)
                    return@withSftpClient // Blob exists, don't overwrite
                } catch (e: SFTPException) {
                    if (!isNotExist(e)) {
                        throw e
                    }
                    // Not found is fine - we'll create it
                }
            }

            // Generate temp file name
            val randomSuffix = ByteArray(TEMP_FILE_RANDOM_SUFFIX_LEN)
            random.nextBytes(randomSuffix)
            val tempFile = "$fullPath.tmp.${randomSuffix.toHexString()}"

            try {
                // Ensure parent directory exists
                val fullDirPath = "${this@SftpBlobStorage.options.path}/$dirPath".trimEnd('/')
                if (fullDirPath != this@SftpBlobStorage.options.path) {
                    ensureDirectoryExists(sftp, fullDirPath)
                }

                // Write to temp file
                sftp.open(tempFile, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
                    file.write(0, data, 0, data.size)
                }

                // Rename temp file to final location (atomic). OVERWRITE is required: a plain
                // SSH_FXP_RENAME (no flags) is rejected by OpenSSH's sftp-server when the target
                // already exists (SFTP v3 link()→EEXIST), so overwriting an existing blob would fail.
                // sshj maps OVERWRITE to the posix-rename@openssh.com extension on a v3 server, which
                // replaces atomically — matching Go kopia's use of PosixRename here.
                sftp.rename(tempFile, fullPath, EnumSet.of(RenameFlags.OVERWRITE))

                // Set modification time if requested
                options.setModTime?.let { modTime ->
                    val attrs = FileAttributes.Builder()
                        .withAtimeMtime(
                            modTime.epochSecond,
                            modTime.epochSecond,
                        )
                        .build()
                    sftp.setattr(fullPath, attrs)
                }
            } catch (e: Exception) {
                // Clean up temp file if it exists
                try {
                    sftp.rm(tempFile)
                } catch (_: Exception) {
                }
                throw e
            }
        }
    }

    private fun ensureDirectoryExists(sftp: SFTPClient, path: String) {
        try {
            sftp.stat(path)
        } catch (e: SFTPException) {
            if (isNotExist(e)) {
                mkdirAll(sftp, path)
            } else {
                throw e
            }
        }
    }

    override suspend fun deleteBlob(blobId: BlobId) {
        if (readOnly) {
            throw IllegalStateException("Storage is read-only")
        }
        withSftpClient { sftp ->
            val fullPath = getFullPath(blobId)

            try {
                sftp.rm(fullPath)
            } catch (e: SFTPException) {
                // Ignore "not found" errors - blob is already deleted
                if (!isNotExist(e)) {
                    throw e
                }
            }
            Unit
        }
    }

    /**
     * SFTP storage does not support capacity queries.
     * SSHJ doesn't have a built-in statVFS method.
     * The statvfs@openssh.com extension would need to be called directly.
     */
    @Suppress("unused")
    suspend fun getCapacity(): Nothing = throw UnsupportedOperationException(
        "SFTP storage does not support capacity queries. " +
            "Use filesystem-level monitoring tools instead.",
    )

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = SFTP_STORAGE_TYPE,
        config = buildMap {
            put("host", options.host)
            put("port", options.port.toString())
            put("username", options.username)
            put("path", options.path)
        },
    )

    override fun displayName(): String = "SFTP ${options.username}@${options.host}"

    override fun isReadOnly(): Boolean = readOnly

    override suspend fun close() {
        connectionMutex.withLock {
            closeCachedConnection()
        }
    }

    override suspend fun flushCaches() {
        // SFTP operations are immediately persisted
    }

    /**
     * Gets the directory path for a blob (relative to root).
     */
    private fun getDirPath(blobId: BlobId): String = SftpSharding.shardedPath(blobId.value, shardsConfig).first

    /**
     * Gets the full file path for a blob (including root path and .f suffix).
     */
    private fun getFullPath(blobId: BlobId): String {
        val (dirPath, remainingId) = SftpSharding.shardedPath(blobId.value, shardsConfig)
        val fileName = "$remainingId$COMPLETE_BLOB_SUFFIX"
        return if (dirPath.isEmpty()) {
            "${options.path}/$fileName"
        } else {
            "${options.path}/$dirPath/$fileName"
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Host key verifier that accepts ALL host keys (no MITM protection).
 * Reachable only via an explicit [SftpOptions.insecureSkipHostKeyVerification] opt-in — the default
 * now fails closed. For local/testing use only; must never be enabled in release builds.
 */
private class PromiscuousVerifier : HostKeyVerifier {
    override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean = true

    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
}
