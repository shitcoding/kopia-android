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
import java.security.SecureRandom
import org.kopiaKt.core.repository.ClientOptions
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import org.kopiaKt.storage.s3.S3BlobStorage
import org.kopiaKt.storage.s3.S3Options
import org.kopiaKt.storage.sftp.SftpBlobStorage
import org.kopiaKt.storage.sftp.SftpOptions
import org.kopiaKt.storage.webdav.WebDavBlobStorage
import org.kopiaKt.storage.webdav.WebDavOptions
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.Path

@Singleton
class KopiaRepositoryManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : KopiaRepositoryManager {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var currentRepository: DirectRepository? = null

    override suspend fun connect(
        config: ConnectionConfig,
        repositoryPassword: String
    ): Result<RepositoryConnection> = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.Connecting

        try {
            val storage = createBlobStorage(config)

            val repository = DirectRepositoryImpl.open(
                blobStorage = storage,
                password = repositoryPassword,
                clientOptions = ClientOptions.withDefaults(
                    description = "KopiaKt Android"
                )
            )

            currentRepository = repository

            val connection = RepositoryConnection(
                id = UUID.randomUUID().toString(),
                displayName = getDisplayName(config),
                storageType = getStorageType(config),
                connectionConfig = config,
                lastConnected = Instant.now(),
                isConnected = true
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
        options: RepositoryCreateOptions
    ): Result<RepositoryConnection> = withContext(Dispatchers.IO) {
        _connectionState.value = ConnectionState.Connecting

        try {
            val storage = createBlobStorage(config, isCreate = true)

            val repoConfig = buildRepositoryConfig(options)

            val clientOpts = ClientOptions.withDefaults(
                description = options.description.ifEmpty { "KopiaKt Android" }
            )

            val keyDerivationAlgorithm = options.keyDerivationAlgorithm

            val repository = DirectRepositoryImpl.create(
                blobStorage = storage,
                password = repositoryPassword,
                config = repoConfig,
                clientOptions = clientOpts,
                keyDerivationAlgorithm = keyDerivationAlgorithm
                    ?: org.kopiaKt.core.format.KopiaRepositoryJson.DEFAULT_KEY_DERIVATION_ALGORITHM
            )

            currentRepository = repository

            val connection = RepositoryConnection(
                id = UUID.randomUUID().toString(),
                displayName = getDisplayName(config),
                storageType = getStorageType(config),
                connectionConfig = config,
                lastConnected = Instant.now(),
                isConnected = true
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
        isCreate: Boolean = false
    ): BlobStorage = when (config) {
        is ConnectionConfig.LocalFilesystem -> {
            FilesystemBlobStorage.create(Path(config.path), create = isCreate)
        }

        is ConnectionConfig.S3 -> {
            S3BlobStorage.create(
                S3Options(
                    bucketName = config.bucket,
                    endpoint = config.endpoint,
                    region = config.region,
                    accessKeyId = config.accessKeyId,
                    secretAccessKey = config.secretAccessKey
                )
            )
        }

        is ConnectionConfig.WebDAV -> {
            WebDavBlobStorage.create(
                WebDavOptions(
                    url = config.url,
                    username = config.username,
                    password = config.password
                )
            )
        }

        is ConnectionConfig.SFTP -> {
            SftpBlobStorage.create(
                SftpOptions(
                    host = config.host,
                    port = config.port,
                    username = config.username,
                    password = config.password,
                    path = config.path
                )
            )
        }

        is ConnectionConfig.SAF -> {
            SafBlobStorage.create(
                context = context,
                treeUri = Uri.parse(config.treeUri),
                options = SafOptions(
                    treeUri = Uri.parse(config.treeUri),
                    readOnly = false
                )
            )
        }
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
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val masterKey = ByteArray(32).also { random.nextBytes(it) }

        return RepositoryConfig(
            hash = options.hashAlgorithm ?: HashAlgorithm.DEFAULT.id,
            encryption = options.encryptionAlgorithm ?: EncryptionAlgorithm.DEFAULT.id,
            secret = secret,
            masterKey = masterKey,
            splitter = options.splitterAlgorithm ?: "DYNAMIC-4M-BUZHASH"
        )
    }
}
