package org.kopiaKt.app.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.FileEntryType
import org.kopiaKt.app.domain.model.RepositoryConnection
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.RestoreState
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SnapshotStats
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.model.StorageType
import org.kopiaKt.app.domain.repository.RestoreOptions

/**
 * Generic result wrapper for JSON responses to JavaScript.
 */
@Serializable
data class WebResult<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
) {
    companion object {
        fun <T> success(data: T): WebResult<T> = WebResult(success = true, data = data)
        fun <T> error(message: String): WebResult<T> = WebResult(success = false, error = message)
    }
}

// ===== Request Models =====

@Serializable
data class WebConnectRequest(
    val config: WebConnectionConfig,
    val password: String
)

@Serializable
data class WebConnectionConfig(
    val storageType: String,
    val local: WebLocalConfig? = null,
    val s3: WebS3Config? = null,
    val webdav: WebWebDavConfig? = null,
    val sftp: WebSftpConfig? = null,
    val saf: WebSafConfig? = null
) {
    fun toDomain(): ConnectionConfig = when (storageType) {
        "LOCAL_FILESYSTEM" -> ConnectionConfig.LocalFilesystem(
            path = local?.path ?: ""
        )
        "S3" -> ConnectionConfig.S3(
            bucket = s3?.bucket ?: "",
            endpoint = s3?.endpoint ?: "",
            region = s3?.region ?: "",
            accessKeyId = s3?.accessKeyId ?: ""
        )
        "WEBDAV" -> ConnectionConfig.WebDAV(
            url = webdav?.url ?: "",
            username = webdav?.username ?: ""
        )
        "SFTP" -> ConnectionConfig.SFTP(
            host = sftp?.host ?: "",
            port = sftp?.port ?: 22,
            username = sftp?.username ?: "",
            path = sftp?.path ?: ""
        )
        "SAF" -> ConnectionConfig.SAF(
            treeUri = saf?.treeUri ?: "",
            displayPath = saf?.displayPath ?: ""
        )
        else -> throw IllegalArgumentException("Unknown storage type: $storageType")
    }
}

@Serializable
data class WebLocalConfig(val path: String)

@Serializable
data class WebS3Config(
    val bucket: String,
    val endpoint: String,
    val region: String,
    val accessKeyId: String
)

@Serializable
data class WebWebDavConfig(
    val url: String,
    val username: String
)

@Serializable
data class WebSftpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val path: String
)

@Serializable
data class WebSafConfig(
    val treeUri: String,
    val displayPath: String
)

@Serializable
data class WebSnapshotListRequest(
    val source: WebSourceInfo? = null
)

@Serializable
data class WebListDirectoryRequest(
    val snapshotId: String,
    val path: String,
    val pageToken: String? = null,
    val pageSize: Int? = null
)

@Serializable
data class WebRestoreRequest(
    val snapshotId: String,
    val sourcePath: String,
    val destinationUri: String,
    val options: WebRestoreOptions? = null
)

@Serializable
data class WebRestoreOptions(
    val parallel: Int = 0,
    val incremental: Boolean = false,
    val overwriteExisting: Boolean = true
) {
    fun toDomain() = RestoreOptions(
        parallel = parallel,
        incremental = incremental,
        overwriteExisting = overwriteExisting
    )
}

@Serializable
data class WebPersistUriRequest(
    val uri: String,
    val read: Boolean = true,
    val write: Boolean = true
)

// ===== Response Models =====

@Serializable
data class WebRepositoryConnection(
    val id: String,
    val displayName: String,
    val storageType: String,
    val connectionConfig: WebConnectionConfig,
    val lastConnectedEpochMs: Long? = null,
    val isConnected: Boolean
)

@Serializable
data class WebSourceInfo(
    val host: String,
    val userName: String,
    val path: String
) {
    fun toDomain() = SourceInfo(
        host = host,
        userName = userName,
        path = path
    )
}

@Serializable
data class WebSnapshotInfo(
    val id: String,
    val source: WebSourceInfo,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long? = null,
    val description: String,
    val stats: WebSnapshotStats? = null,
    val isIncomplete: Boolean,
    val tags: Map<String, String>
)

@Serializable
data class WebSnapshotStats(
    val totalFileSize: Long,
    val totalFileCount: Long,
    val totalDirectoryCount: Long
)

@Serializable
data class WebDirectoryPage(
    val entries: List<WebFileEntry>,
    val nextPageToken: String? = null
)

@Serializable
data class WebFileEntry(
    val name: String,
    val type: String,
    val size: Long,
    val modTimeEpochMs: Long? = null,
    val permissions: Int,
    val objectId: String? = null
)

@Serializable
data class WebRestoreProgress(
    val state: String,
    val totalFiles: Long,
    val restoredFiles: Long,
    val totalBytes: Long,
    val restoredBytes: Long,
    val currentFile: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class WebSafPickResult(
    val uri: String? = null,
    val displayName: String? = null
)

// ===== Domain -> Web Mappings =====

fun RepositoryConnection.toWeb() = WebRepositoryConnection(
    id = id,
    displayName = displayName,
    storageType = storageType.name,
    connectionConfig = connectionConfig.toWeb(),
    lastConnectedEpochMs = lastConnected?.toEpochMilli(),
    isConnected = isConnected
)

fun ConnectionConfig.toWeb(): WebConnectionConfig = when (this) {
    is ConnectionConfig.LocalFilesystem -> WebConnectionConfig(
        storageType = "LOCAL_FILESYSTEM",
        local = WebLocalConfig(path = path)
    )
    is ConnectionConfig.S3 -> WebConnectionConfig(
        storageType = "S3",
        s3 = WebS3Config(
            bucket = bucket,
            endpoint = endpoint,
            region = region,
            accessKeyId = accessKeyId
        )
    )
    is ConnectionConfig.WebDAV -> WebConnectionConfig(
        storageType = "WEBDAV",
        webdav = WebWebDavConfig(
            url = url,
            username = username
        )
    )
    is ConnectionConfig.SFTP -> WebConnectionConfig(
        storageType = "SFTP",
        sftp = WebSftpConfig(
            host = host,
            port = port,
            username = username,
            path = path
        )
    )
    is ConnectionConfig.SAF -> WebConnectionConfig(
        storageType = "SAF",
        saf = WebSafConfig(
            treeUri = treeUri,
            displayPath = displayPath
        )
    )
}

fun SourceInfo.toWeb() = WebSourceInfo(
    host = host,
    userName = userName,
    path = path
)

fun SnapshotInfo.toWeb() = WebSnapshotInfo(
    id = id,
    source = source.toWeb(),
    startTimeEpochMs = startTime.toEpochMilli(),
    endTimeEpochMs = endTime?.toEpochMilli(),
    description = description,
    stats = stats?.toWeb(),
    isIncomplete = isIncomplete,
    tags = tags
)

fun SnapshotStats.toWeb() = WebSnapshotStats(
    totalFileSize = totalFileSize,
    totalFileCount = totalFileCount.toLong(),
    totalDirectoryCount = totalDirectoryCount.toLong()
)

fun FileEntry.toWeb() = WebFileEntry(
    name = name,
    type = type.name,
    size = size,
    modTimeEpochMs = modTime?.toEpochMilli(),
    permissions = permissions,
    objectId = objectId
)

fun RestoreProgress.toWeb() = WebRestoreProgress(
    state = state.name,
    totalFiles = totalFiles,
    restoredFiles = restoredFiles,
    totalBytes = totalBytes,
    restoredBytes = restoredBytes,
    currentFile = currentFile,
    errorMessage = errorMessage
)

fun RestoreState.isTerminal() =
    this == RestoreState.COMPLETED ||
        this == RestoreState.FAILED ||
        this == RestoreState.CANCELLED
