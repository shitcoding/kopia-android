package org.kopiaKt.app.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.kopiaKt.app.domain.model.ConnectionConfig as DomainConnectionConfig
import org.kopiaKt.app.domain.model.FileEntry as DomainFileEntry
import org.kopiaKt.app.domain.model.FileEntryType as DomainFileEntryType
import org.kopiaKt.app.domain.model.RepositoryConnection as DomainRepositoryConnection
import org.kopiaKt.app.domain.model.RestoreProgress as DomainRestoreProgress
import org.kopiaKt.app.domain.model.RestoreState as DomainRestoreState
import org.kopiaKt.app.domain.model.SnapshotInfo as DomainSnapshotInfo
import org.kopiaKt.app.domain.model.SnapshotStats as DomainSnapshotStats
import org.kopiaKt.app.domain.model.SourceInfo as DomainSourceInfo
import org.kopiaKt.app.domain.model.StorageType as DomainStorageType
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.app.domain.repository.RestoreOptions as DomainRestoreOptions
import org.kopiaKt.app.domain.repository.SnapshotRepository
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Hilt EntryPoint for accessing DI-managed services from the Flutter bridge.
 * This allows non-Hilt classes (like KopiaBridgeHandler) to access Hilt-injected dependencies.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KopiaBridgeEntryPoint {
    fun repositoryManager(): KopiaRepositoryManager
    fun snapshotRepository(): SnapshotRepository
}

/**
 * Bridge handler implementing the Pigeon-generated KopiaHostApi.
 * Handles communication between Flutter UI and Kotlin domain layer.
 */
class KopiaBridgeHandler(
    private val context: Context,
    private val activity: ComponentActivity,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : KopiaHostApi {

    private val entryPoint = EntryPointAccessors.fromApplication(
        context,
        KopiaBridgeEntryPoint::class.java
    )

    private val repositoryManager get() = entryPoint.repositoryManager()
    private val snapshotRepository get() = entryPoint.snapshotRepository()

    private val restoreStreamHandler = RestoreProgressStreamHandler()
    private var restoreJob: Job? = null

    /**
     * Sets up the Pigeon channels with the Flutter binary messenger.
     */
    fun setUp(binaryMessenger: BinaryMessenger) {
        KopiaHostApi.setUp(binaryMessenger, this)

        // Set up EventChannel for restore progress streaming
        EventChannel(binaryMessenger, "org.kopiaKt.app/restore_progress")
            .setStreamHandler(restoreStreamHandler)
    }

    /**
     * Simple ping method to verify bridge communication.
     */
    override fun ping(callback: (Result<String>) -> Unit) {
        callback(Result.success("pong"))
    }

    override fun connect(
        request: ConnectRequest,
        callback: (Result<RepositoryConnection>) -> Unit
    ) {
        scope.launch {
            try {
                val domainConfig = request.config.toDomain()
                val result = repositoryManager.connect(domainConfig, request.password)
                result.fold(
                    onSuccess = { callback(Result.success(it.toPigeon())) },
                    onFailure = { callback(Result.failure(it)) }
                )
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun disconnect(callback: (Result<Unit>) -> Unit) {
        scope.launch {
            try {
                repositoryManager.disconnect()
                callback(Result.success(Unit))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun listSources(callback: (Result<List<SourceInfo?>>) -> Unit) {
        scope.launch {
            try {
                val sources = snapshotRepository.listSources()
                callback(Result.success(sources.map { it.toPigeon() }))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun listSnapshots(
        request: SnapshotListRequest,
        callback: (Result<List<SnapshotInfo?>>) -> Unit
    ) {
        scope.launch {
            try {
                val source = request.source?.toDomain()
                val snapshots = snapshotRepository.listSnapshots(source)
                callback(Result.success(snapshots.map { it.toPigeon() }))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun getSnapshot(snapshotId: String, callback: (Result<SnapshotInfo?>) -> Unit) {
        scope.launch {
            try {
                val snapshot = snapshotRepository.getSnapshot(snapshotId)
                callback(Result.success(snapshot?.toPigeon()))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun listDirectory(
        request: ListDirectoryRequest,
        callback: (Result<DirectoryPage>) -> Unit
    ) {
        scope.launch {
            try {
                val all = snapshotRepository.browseDirectory(request.snapshotId, request.path)

                // Simple pagination
                val start = request.pageToken?.toLongOrNull()?.toInt() ?: 0
                val size = request.pageSize?.toInt() ?: all.size
                val slice = all.drop(start).take(size)
                val next = if (start + size < all.size) (start + size).toString() else null

                val page = DirectoryPage(
                    entries = slice.map { it.toPigeon() },
                    nextPageToken = next
                )
                callback(Result.success(page))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun startRestore(request: RestoreRequest, callback: (Result<Unit>) -> Unit) {
        // Cancel any existing restore
        restoreJob?.cancel()
        snapshotRepository.cancelRestore()

        val options = request.options?.toDomain() ?: DomainRestoreOptions()
        restoreJob = scope.launch {
            try {
                var reachedTerminalState = false
                snapshotRepository.restore(
                    snapshotId = request.snapshotId,
                    sourcePath = request.sourcePath,
                    destinationUri = request.destinationUri,
                    options = options
                ).collect { progress ->
                    restoreStreamHandler.emit(progress.toPigeon())

                    // Track terminal state but don't cancel - let flow complete naturally
                    if (progress.state.isTerminal()) {
                        reachedTerminalState = true
                        restoreStreamHandler.endOfStream()
                    }
                }
                // Flow completed normally
                callback(Result.success(Unit))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled externally (e.g., user cancelled)
                // Don't treat as error - this is expected behavior
                throw e
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun cancelRestore(callback: (Result<Unit>) -> Unit) {
        snapshotRepository.cancelRestore()
        restoreJob?.cancel()

        // Emit cancelled state and close stream
        restoreStreamHandler.emit(
            RestoreProgress(
                state = RestoreState.CANCELLED,
                totalFiles = 0,
                restoredFiles = 0,
                totalBytes = 0,
                restoredBytes = 0,
                currentFile = null,
                errorMessage = "Cancelled"
            )
        )
        restoreStreamHandler.endOfStream()
        callback(Result.success(Unit))
    }

    override fun pickRestoreDestination(callback: (Result<SafPickResult>) -> Unit) {
        val key = "kopia_pick_${UUID.randomUUID()}"
        var launcher: androidx.activity.result.ActivityResultLauncher<Uri?>? = null
        launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            launcher?.unregister()
            if (uri == null) {
                callback(Result.success(SafPickResult(uri = null, displayName = null)))
            } else {
                callback(Result.success(
                    SafPickResult(
                        uri = uri.toString(),
                        displayName = uri.lastPathSegment
                    )
                ))
            }
        }
        launcher.launch(null)
    }

    override fun persistUriPermission(
        request: PersistUriPermissionRequest,
        callback: (Result<Unit>) -> Unit
    ) {
        try {
            val flags = (if (request.read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (request.write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            context.contentResolver.takePersistableUriPermission(Uri.parse(request.uri), flags)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    /**
     * EventChannel.StreamHandler for streaming restore progress to Flutter.
     */
    private class RestoreProgressStreamHandler : EventChannel.StreamHandler {
        @Volatile
        private var sink: EventChannel.EventSink? = null

        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            sink = events
        }

        override fun onCancel(arguments: Any?) {
            sink = null
        }

        fun emit(progress: RestoreProgress) {
            sink?.success(progress.toList())
        }

        fun endOfStream() {
            sink?.endOfStream()
            sink = null
        }
    }
}

// ===== Domain -> Pigeon Mappings =====

private fun DomainSourceInfo.toPigeon() = SourceInfo(
    host = host,
    userName = userName,
    path = path
)

private fun SourceInfo.toDomain() = DomainSourceInfo(
    host = host,
    userName = userName,
    path = path
)

private fun DomainStorageType.toPigeon() = when (this) {
    DomainStorageType.LOCAL_FILESYSTEM -> StorageType.LOCAL_FILESYSTEM
    DomainStorageType.S3 -> StorageType.S3
    DomainStorageType.WEBDAV -> StorageType.WEBDAV
    DomainStorageType.SFTP -> StorageType.SFTP
    DomainStorageType.SAF -> StorageType.SAF
}

private fun StorageType.toDomain() = when (this) {
    StorageType.LOCAL_FILESYSTEM -> DomainStorageType.LOCAL_FILESYSTEM
    StorageType.S3 -> DomainStorageType.S3
    StorageType.WEBDAV -> DomainStorageType.WEBDAV
    StorageType.SFTP -> DomainStorageType.SFTP
    StorageType.SAF -> DomainStorageType.SAF
}

private fun DomainRestoreState.toPigeon() = when (this) {
    DomainRestoreState.IDLE -> RestoreState.IDLE
    DomainRestoreState.PREPARING -> RestoreState.PREPARING
    DomainRestoreState.IN_PROGRESS -> RestoreState.IN_PROGRESS
    DomainRestoreState.COMPLETED -> RestoreState.COMPLETED
    DomainRestoreState.FAILED -> RestoreState.FAILED
    DomainRestoreState.CANCELLED -> RestoreState.CANCELLED
}

private fun DomainRestoreState.isTerminal() =
    this == DomainRestoreState.COMPLETED ||
        this == DomainRestoreState.FAILED ||
        this == DomainRestoreState.CANCELLED

private fun DomainRestoreProgress.toPigeon() = RestoreProgress(
    state = state.toPigeon(),
    totalFiles = totalFiles,
    restoredFiles = restoredFiles,
    totalBytes = totalBytes,
    restoredBytes = restoredBytes,
    currentFile = currentFile,
    errorMessage = errorMessage
)

private fun RestoreOptions.toDomain() = DomainRestoreOptions(
    parallel = parallel.toInt(),
    incremental = incremental,
    overwriteExisting = overwriteExisting
)

private fun DomainRepositoryConnection.toPigeon() = RepositoryConnection(
    id = id,
    displayName = displayName,
    storageType = storageType.toPigeon(),
    connectionConfig = connectionConfig.toPigeon(),
    lastConnectedEpochMs = lastConnected?.toEpochMilli(),
    isConnected = isConnected
)

private fun DomainConnectionConfig.toPigeon(): ConnectionConfig = when (this) {
    is DomainConnectionConfig.LocalFilesystem -> ConnectionConfig(
        storageType = StorageType.LOCAL_FILESYSTEM,
        local = LocalFilesystemConfig(path = path)
    )
    is DomainConnectionConfig.S3 -> ConnectionConfig(
        storageType = StorageType.S3,
        s3 = S3Config(
            bucket = bucket,
            endpoint = endpoint,
            region = region,
            accessKeyId = accessKeyId
        )
    )
    is DomainConnectionConfig.WebDAV -> ConnectionConfig(
        storageType = StorageType.WEBDAV,
        webdav = WebDavConfig(
            url = url,
            username = username
        )
    )
    is DomainConnectionConfig.SFTP -> ConnectionConfig(
        storageType = StorageType.SFTP,
        sftp = SftpConfig(
            host = host,
            port = port.toLong(),
            username = username,
            path = path
        )
    )
    is DomainConnectionConfig.SAF -> ConnectionConfig(
        storageType = StorageType.SAF,
        saf = SafConfig(
            treeUri = treeUri,
            displayPath = displayPath
        )
    )
}

private fun ConnectionConfig.toDomain(): DomainConnectionConfig = when (storageType) {
    StorageType.LOCAL_FILESYSTEM -> DomainConnectionConfig.LocalFilesystem(
        path = local?.path ?: ""
    )
    StorageType.S3 -> DomainConnectionConfig.S3(
        bucket = s3?.bucket ?: "",
        endpoint = s3?.endpoint ?: "",
        region = s3?.region ?: "",
        accessKeyId = s3?.accessKeyId ?: ""
    )
    StorageType.WEBDAV -> DomainConnectionConfig.WebDAV(
        url = webdav?.url ?: "",
        username = webdav?.username ?: ""
    )
    StorageType.SFTP -> DomainConnectionConfig.SFTP(
        host = sftp?.host ?: "",
        port = sftp?.port?.toInt() ?: 22,
        username = sftp?.username ?: "",
        path = sftp?.path ?: ""
    )
    StorageType.SAF -> DomainConnectionConfig.SAF(
        treeUri = saf?.treeUri ?: "",
        displayPath = saf?.displayPath ?: ""
    )
}

private fun DomainSnapshotInfo.toPigeon() = SnapshotInfo(
    id = id,
    source = source.toPigeon(),
    startTimeEpochMs = startTime.toEpochMilli(),
    endTimeEpochMs = endTime?.toEpochMilli(),
    description = description,
    stats = stats?.toPigeon(),
    isIncomplete = isIncomplete,
    tags = tags.mapKeys { it.key as String? }.mapValues { it.value as String? }
)

private fun DomainSnapshotStats.toPigeon() = SnapshotStats(
    totalFileSize = totalFileSize,
    totalFileCount = totalFileCount.toLong(),
    totalDirectoryCount = totalDirectoryCount.toLong()
)

private fun DomainFileEntry.toPigeon() = FileEntry(
    name = name,
    type = type.toPigeon(),
    size = size,
    modTimeEpochMs = modTime?.toEpochMilli(),
    permissions = permissions.toLong(),
    objectId = objectId
)

private fun DomainFileEntryType.toPigeon() = when (this) {
    DomainFileEntryType.FILE -> FileEntryType.FILE
    DomainFileEntryType.DIRECTORY -> FileEntryType.DIRECTORY
    DomainFileEntryType.SYMLINK -> FileEntryType.SYMLINK
    DomainFileEntryType.UNKNOWN -> FileEntryType.UNKNOWN
}
