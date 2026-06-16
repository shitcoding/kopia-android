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
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobMetadata
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.blob.ConnectionInfo
import org.kopiaKt.core.blob.InvalidBlobRangeException
import org.kopiaKt.core.blob.InvalidCredentialsException
import org.kopiaKt.core.blob.PutBlobOptions
import org.kopiaKt.core.blob.RetentionMode
import org.kopiaKt.core.blob.UnsupportedPutOptionException
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
    private val directoryShards: List<Int>
) : BlobStorage {

    companion object {
        private const val SFTP_STORAGE_TYPE = "sftp"
        private const val COMPLETE_BLOB_SUFFIX = ".f"
        private const val TEMP_FILE_RANDOM_SUFFIX_LEN = 8
        private const val MAX_NON_SHARDED_LENGTH = 20

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
            readOnly: Boolean = false
        ): SftpBlobStorage = withContext(Dispatchers.IO) {
            val directoryShards = if (isCreate) {
                listOf(1, 3)
            } else {
                options.directoryShards.ifEmpty { listOf(3, 3) }
            }

            val storage = SftpBlobStorage(options, readOnly, directoryShards)

            // Verify connection and create root path if needed
            storage.withSftpClient { sftp ->
                try {
                    sftp.stat(options.path)
                } catch (e: SFTPException) {
                    if (isNotExist(e)) {
                        mkdirAll(sftp, options.path)
                    } else {
                        throw e
                    }
                }
            }

            storage
        }

        /**
         * Creates a storage instance with pre-configured connections (for testing).
         */
        internal fun createWithConnections(
            options: SftpOptions,
            sshClient: SSHClient,
            sftpClient: SFTPClient,
            readOnly: Boolean = false,
            directoryShards: List<Int> = listOf(1, 3)
        ): SftpBlobStorage {
            return SftpBlobStorage(options, readOnly, directoryShards).apply {
                this.cachedSshClient = sshClient
                this.cachedSftpClient = sftpClient
            }
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

    private fun createHostKeyVerifier(): HostKeyVerifier {
        return when {
            options.knownHostsData.isNotEmpty() -> {
                // Create temporary file with known hosts data
                val tempFile = File.createTempFile("kopia-known-hosts", ".tmp")
                tempFile.deleteOnExit()
                tempFile.writeText(options.knownHostsData)
                OpenSSHKnownHosts(tempFile)
            }
            else -> {
                val knownHostsPath = options.effectiveKnownHostsFile()
                val knownHostsFile = File(knownHostsPath)
                if (knownHostsFile.exists()) {
                    OpenSSHKnownHosts(knownHostsFile)
                } else {
                    // Accept all host keys (not recommended for production)
                    PromiscuousVerifier()
                }
            }
        }
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

    override suspend fun getBlob(blobId: BlobId, offset: Long, length: Long): ByteArray =
        withSftpClient { sftp ->
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
                            val bytesToRead = (fileSize - offset).toInt()
                            val buffer = ByteArray(bytesToRead)
                            if (bytesToRead > 0) {
                                var totalRead = 0
                                while (totalRead < bytesToRead) {
                                    val read = remoteFile.read(
                                        offset + totalRead,
                                        buffer,
                                        totalRead,
                                        bytesToRead - totalRead
                                    )
                                    if (read <= 0) break
                                    totalRead += read
                                }
                                if (totalRead < bytesToRead) {
                                    buffer.copyOf(totalRead)
                                } else {
                                    buffer
                                }
                            } else {
                                ByteArray(0)
                            }
                        }
                        else -> {
                            // Read specific length
                            val bytesToRead = length.toInt()
                            val buffer = ByteArray(bytesToRead)
                            var totalRead = 0
                            while (totalRead < bytesToRead) {
                                val read = remoteFile.read(
                                    offset + totalRead,
                                    buffer,
                                    totalRead,
                                    bytesToRead - totalRead
                                )
                                if (read <= 0) break
                                totalRead += read
                            }
                            if (totalRead < bytesToRead) {
                                throw InvalidBlobRangeException(
                                    "Could only read $totalRead bytes, expected $bytesToRead"
                                )
                            }
                            buffer
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

    override suspend fun getBlobMetadata(blobId: BlobId): BlobMetadata? =
        withSftpClient { sftp ->
            val fullPath = getFullPath(blobId)

            try {
                val attrs = sftp.stat(fullPath)
                BlobMetadata(
                    blobId = blobId,
                    length = attrs.size,
                    timestamp = Instant.ofEpochSecond(attrs.mtime)
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
            walkDirectory(sftp, options.path, "", prefix, collected)
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
        results: MutableList<BlobMetadata>
    ) {
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
                            results
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
                                timestamp = Instant.ofEpochSecond(entry.attributes.mtime)
                            )
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

                // Rename temp file to final location (atomic)
                sftp.rename(tempFile, fullPath)

                // Set modification time if requested
                options.setModTime?.let { modTime ->
                    val attrs = FileAttributes.Builder()
                        .withAtimeMtime(
                            modTime.epochSecond,
                            modTime.epochSecond
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
    suspend fun getCapacity(): Nothing {
        throw UnsupportedOperationException(
            "SFTP storage does not support capacity queries. " +
                "Use filesystem-level monitoring tools instead."
        )
    }

    override fun connectionInfo(): ConnectionInfo = ConnectionInfo(
        type = SFTP_STORAGE_TYPE,
        config = buildMap {
            put("host", options.host)
            put("port", options.port.toString())
            put("username", options.username)
            put("path", options.path)
        }
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
     * Gets the sharded path components for a blob ID.
     */
    private fun getShardedPath(blobId: BlobId): Pair<String, String> {
        var id = blobId.value
        var shardPath = ""

        // Short blob IDs are not sharded
        if (id.length <= MAX_NON_SHARDED_LENGTH) {
            return Pair(shardPath, id)
        }

        for (size in directoryShards) {
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
     * Gets the directory path for a blob (relative to root).
     */
    private fun getDirPath(blobId: BlobId): String {
        return getShardedPath(blobId).first
    }

    /**
     * Gets the full file path for a blob (including root path and .f suffix).
     */
    private fun getFullPath(blobId: BlobId): String {
        val (dirPath, remainingId) = getShardedPath(blobId)
        val fileName = "$remainingId$COMPLETE_BLOB_SUFFIX"
        return if (dirPath.isEmpty()) {
            "${options.path}/$fileName"
        } else {
            "${options.path}/$dirPath/$fileName"
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}

/**
 * Host key verifier that accepts all host keys.
 * Used when no known_hosts file is available.
 * NOT RECOMMENDED FOR PRODUCTION USE.
 */
private class PromiscuousVerifier : HostKeyVerifier {
    override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean = true

    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
}
