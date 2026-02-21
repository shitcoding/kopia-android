package org.kopiaKt.app.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
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
import org.kopiaKt.core.blob.BlobStorage
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

        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        currentRepository?.close()
        currentRepository = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun getStoredConnections(): List<RepositoryConnection> {
        // TODO: Implement persistent storage of connections
        return emptyList()
    }

    override suspend fun deleteStoredConnection(id: String) {
        // TODO: Implement
    }

    fun getRepository(): DirectRepository? = currentRepository

    private suspend fun createBlobStorage(
        config: ConnectionConfig
    ): BlobStorage = when (config) {
        is ConnectionConfig.LocalFilesystem -> {
            FilesystemBlobStorage.create(Path(config.path))
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
}
