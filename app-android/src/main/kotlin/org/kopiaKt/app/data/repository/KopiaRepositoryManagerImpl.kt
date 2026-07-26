package org.kopiaKt.app.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.kopiaKt.android.storage.SafBlobStorage
import org.kopiaKt.android.storage.SafOptions
import org.kopiaKt.app.BuildConfig
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.RepositoryConnection
import org.kopiaKt.app.domain.model.StorageType
import org.kopiaKt.app.domain.repository.ConnectionState
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.app.domain.repository.RepositoryCreateOptions
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.repository.ClientOptions
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import org.kopiaKt.storage.s3.RetryingBlobStorage
import org.kopiaKt.storage.s3.S3BlobStorage
import org.kopiaKt.storage.s3.S3Options
import org.kopiaKt.storage.sftp.SftpBlobStorage
import org.kopiaKt.storage.sftp.SftpOptions
import org.kopiaKt.storage.webdav.WebDavBlobStorage
import org.kopiaKt.storage.webdav.WebDavOptions
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.Path

@Singleton
class KopiaRepositoryManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : KopiaRepositoryManager {

    private companion object {
        /** Length in bytes of the repository secret and master key (256-bit). */
        const val REPOSITORY_KEY_SIZE_BYTES = 32
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var currentRepository: DirectRepository? = null

    override suspend fun connect(
        config: ConnectionConfig,
        repositoryPassword: String,
    ): Result<RepositoryConnection> = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.Connecting

        try {
            val storage = createBlobStorage(config)

            val repository = DirectRepositoryImpl.open(
                blobStorage = storage,
                password = repositoryPassword,
                clientOptions = ClientOptions.withDefaults(
                    description = "KopiaKt Android",
                ),
            )

            currentRepository = repository

            val connection = RepositoryConnection(
                id = UUID.randomUUID().toString(),
                displayName = getDisplayName(config),
                storageType = getStorageType(config),
                connectionConfig = config,
                lastConnected = Instant.now(),
                isConnected = true,
            )

            _connectionState.value = ConnectionState.Connected(connection)
            Result.success(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            Result.failure(e)
        }
    }

    override suspend fun create(
        config: ConnectionConfig,
        repositoryPassword: String,
        options: RepositoryCreateOptions,
    ): Result<RepositoryConnection> = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.Connecting

        try {
            val storage = createBlobStorage(config, isCreate = true)

            val repoConfig = buildRepositoryConfig(options)

            val clientOpts = ClientOptions.withDefaults(
                description = options.description.ifEmpty { "KopiaKt Android" },
            )

            val keyDerivationAlgorithm = options.keyDerivationAlgorithm

            val repository = DirectRepositoryImpl.create(
                blobStorage = storage,
                password = repositoryPassword,
                config = repoConfig,
                clientOptions = clientOpts,
                keyDerivationAlgorithm = keyDerivationAlgorithm
                    ?: org.kopiaKt.core.format.KopiaRepositoryJson.DEFAULT_KEY_DERIVATION_ALGORITHM,
            )

            currentRepository = repository

            val connection = RepositoryConnection(
                id = UUID.randomUUID().toString(),
                displayName = getDisplayName(config),
                storageType = getStorageType(config),
                connectionConfig = config,
                lastConnected = Instant.now(),
                isConnected = true,
            )

            _connectionState.value = ConnectionState.Connected(connection)
            Result.success(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Repository creation failed")
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            val repo = currentRepository
            currentRepository = null
            _connectionState.value = ConnectionState.Disconnected
            repo?.close()
        }
    }

    override suspend fun getStoredConnections(): List<RepositoryConnection> {
        // TODO: Implement persistent storage of connections
        return emptyList()
    }

    override suspend fun deleteStoredConnection(id: String) {
        // TODO: Implement
    }

    override fun getRepository(): DirectRepository? = currentRepository

    private suspend fun createBlobStorage(
        config: ConnectionConfig,
        isCreate: Boolean = false,
    ): BlobStorage = when (config) {
        is ConnectionConfig.LocalFilesystem -> {
            FilesystemBlobStorage.create(Path(config.path), create = isCreate)
        }

        is ConnectionConfig.S3 -> createS3Storage(config)

        is ConnectionConfig.WebDAV -> createWebDavStorage(config)

        is ConnectionConfig.SFTP -> {
            // Release builds must never trust an arbitrary host key. The insecure opt-in is a
            // dev/testing-only escape hatch, so reject it here at the connect/factory layer (not
            // just by hiding a UI toggle) — a persisted or imported ConnectionConfig could carry
            // the flag set. The storage layer itself already fails closed when no trust material
            // is supplied (throws HostKeyNotTrustedException).
            requireInsecureHostKeyAllowed(config.insecureSkipHostKeyVerification, BuildConfig.DEBUG)
            RetryingBlobStorage(
                SftpBlobStorage.create(
                    SftpOptions(
                        host = config.host,
                        port = config.port,
                        username = config.username,
                        password = config.password,
                        path = config.path,
                        knownHostsData = config.knownHostsData,
                        hostKeyFingerprint = config.hostKeyFingerprint,
                        insecureSkipHostKeyVerification = config.insecureSkipHostKeyVerification,
                    ),
                ),
            )
        }

        is ConnectionConfig.SAF -> {
            SafBlobStorage.create(
                context = context,
                treeUri = Uri.parse(config.treeUri),
                options = SafOptions(
                    treeUri = Uri.parse(config.treeUri),
                    readOnly = false,
                ),
            )
        }
    }

    /**
     * Remote backends are wrapped in [RetryingBlobStorage] so transient network / 5xx / 429 failures
     * are retried with exponential backoff (local backends are not — their errors are typically
     * permanent, so retrying would only add latency).
     */
    private suspend fun createS3Storage(config: ConnectionConfig.S3): BlobStorage {
        // Cleartext http must be an explicit, per-connection decision — a persisted or imported
        // config could otherwise silently send credentials in the clear.
        requireCleartextAllowed(config.endpoint, config.allowCleartextHttp)
        return RetryingBlobStorage(
            S3BlobStorage.create(
                S3Options(
                    bucketName = config.bucket,
                    endpoint = config.endpoint,
                    region = config.region,
                    accessKeyId = config.accessKeyId,
                    secretAccessKey = config.secretAccessKey,
                    rootCa = config.rootCaPem.takeIf { it.isNotBlank() }?.toByteArray(),
                ),
            ),
        )
    }

    private suspend fun createWebDavStorage(config: ConnectionConfig.WebDAV): BlobStorage {
        requireCleartextAllowed(config.url, config.allowCleartextHttp)
        return RetryingBlobStorage(
            WebDavBlobStorage.create(
                WebDavOptions(
                    url = config.url,
                    username = config.username,
                    password = config.password,
                    trustedServerCertificateFingerprint = config.trustedServerCertificateFingerprint,
                ),
            ),
        )
    }

    private fun getDisplayName(config: ConnectionConfig): String = when (config) {
        is ConnectionConfig.LocalFilesystem -> config.path
        is ConnectionConfig.S3 -> "${config.bucket} (S3)"
        is ConnectionConfig.WebDAV -> config.url
        is ConnectionConfig.SFTP -> "${config.username}@${config.host}:${config.path}"
        is ConnectionConfig.SAF -> config.displayPath
    }

    private fun getStorageType(config: ConnectionConfig): StorageType = when (config) {
        is ConnectionConfig.LocalFilesystem -> StorageType.LOCAL_FILESYSTEM
        is ConnectionConfig.S3 -> StorageType.S3
        is ConnectionConfig.WebDAV -> StorageType.WEBDAV
        is ConnectionConfig.SFTP -> StorageType.SFTP
        is ConnectionConfig.SAF -> StorageType.SAF
    }

    private fun buildRepositoryConfig(options: RepositoryCreateOptions): RepositoryConfig {
        val random = SecureRandom()
        val secret = ByteArray(REPOSITORY_KEY_SIZE_BYTES).also { random.nextBytes(it) }
        val masterKey = ByteArray(REPOSITORY_KEY_SIZE_BYTES).also { random.nextBytes(it) }

        return RepositoryConfig(
            hash = options.hashAlgorithm ?: HashAlgorithm.DEFAULT.id,
            encryption = options.encryptionAlgorithm ?: EncryptionAlgorithm.DEFAULT.id,
            secret = secret,
            masterKey = masterKey,
            splitter = options.splitterAlgorithm ?: "DYNAMIC-4M-BUZHASH",
        )
    }
}

/**
 * Enforces the SFTP host-key security policy at the connect/factory layer: the insecure
 * "trust any host key" opt-in ([ConnectionConfig.SFTP.insecureSkipHostKeyVerification]) is a
 * dev/testing-only escape hatch and must be rejected in release builds, since a persisted or imported
 * config could carry the flag set. Kept as a pure function (the build flag is injected) so the release
 * rejection is unit-testable without building a release variant.
 *
 * @throws IllegalArgumentException if [insecureSkipHostKeyVerification] is set in a non-debug build.
 */
internal fun requireInsecureHostKeyAllowed(
    insecureSkipHostKeyVerification: Boolean,
    isDebugBuild: Boolean,
) {
    require(isDebugBuild || !insecureSkipHostKeyVerification) {
        "insecureSkipHostKeyVerification is not permitted in release builds"
    }
}

/**
 * Enforces the cleartext-HTTP policy at the connect/factory layer: contacting a storage backend over
 * plaintext http sends its credentials (WebDAV Basic auth) and metadata in the clear, so it requires an
 * explicit per-connection acknowledgment.
 *
 * Gated here rather than only in the UI because a persisted or imported [ConnectionConfig] can carry
 * any values — the same reasoning as [requireInsecureHostKeyAllowed]. Unlike that gate this IS allowed
 * in release builds once acknowledged: a self-hosted LAN backend with no TLS is a legitimate use case
 * for a self-hostable backup app; it just must not happen silently. Android cannot enforce this at the
 * OS layer because a network-security-config can only scope cleartext by build-time domain, never by a
 * runtime-entered endpoint.
 *
 * Matches the `http:` scheme prefix (not just `http://`) so it agrees with the UI's `isCleartextUrl()`
 * helper and with OkHttp's lenient parsing of forms like `http:/host`. A scheme-less endpoint is not
 * cleartext: the S3 backend defaults those to https.
 *
 * @throws IllegalArgumentException if [endpoint] is cleartext and [allowCleartextHttp] is not set.
 */
internal fun requireCleartextAllowed(endpoint: String, allowCleartextHttp: Boolean) {
    val isCleartext = endpoint.trim().startsWith("http:", ignoreCase = true)
    require(!isCleartext || allowCleartextHttp) {
        "Refusing to connect to the cleartext endpoint \"$endpoint\": credentials and metadata would " +
            "be sent unencrypted. Use https, or explicitly acknowledge cleartext for this connection."
    }
}
