package org.kopiaKt.app.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kopiaKt.android.worker.TaskKind
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.RepositoryConnection
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.RestoreState
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SnapshotStats
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.repository.RestoreOptions

/**
 * Shared Json config for the JS bridge: [KopiaWebBridge] encodes/decodes with this, and the bridge
 * contract tests assert wire shapes against it, so the pins can't drift from the real encoder.
 * `ignoreUnknownKeys` tolerates older/newer JS clients; `encodeDefaults` keeps optional fields present.
 */
internal val bridgeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Generic result wrapper for JSON responses to JavaScript.
 */
@Serializable
data class WebResult<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val errorCode: String? = null,
) {
    companion object {
        fun <T> success(data: T): WebResult<T> = WebResult(success = true, data = data)
        fun <T> error(message: String, code: String? = null): WebResult<T> = WebResult(success = false, error = message, errorCode = code)
    }
}

/**
 * Error codes for specific error types that the UI can handle specially.
 */
object WebErrorCodes {
    const val STORAGE_PERMISSION_REQUIRED = "STORAGE_PERMISSION_REQUIRED"
}

// ===== Request Models =====

@Serializable
data class WebConnectRequest(
    val config: WebConnectionConfig,
    val repositoryPassword: String = "",
    val password: String = "",
)

@Serializable
data class WebConnectionConfig(
    val storageType: String,
    val local: WebLocalConfig? = null,
    val s3: WebS3Config? = null,
    val webdav: WebWebDavConfig? = null,
    val sftp: WebSftpConfig? = null,
    val saf: WebSafConfig? = null,
) {
    fun toDomain(): ConnectionConfig = when (storageType) {
        "LOCAL_FILESYSTEM" -> ConnectionConfig.LocalFilesystem(
            path = local?.path ?: "",
        )
        "S3" -> ConnectionConfig.S3(
            bucket = s3?.bucket ?: "",
            endpoint = s3?.endpoint ?: "",
            region = s3?.region ?: "",
            accessKeyId = s3?.accessKeyId ?: "",
            secretAccessKey = s3?.secretAccessKey ?: "",
        )
        "WEBDAV" -> ConnectionConfig.WebDAV(
            url = webdav?.url ?: "",
            username = webdav?.username ?: "",
            password = webdav?.password ?: "",
        )
        "SFTP" -> ConnectionConfig.SFTP(
            host = sftp?.host ?: "",
            port = sftp?.port ?: 22,
            username = sftp?.username ?: "",
            path = sftp?.path ?: "",
            password = sftp?.password ?: "",
            knownHostsData = sftp?.knownHostsData ?: "",
            hostKeyFingerprint = sftp?.hostKeyFingerprint ?: "",
            insecureSkipHostKeyVerification = sftp?.insecureSkipHostKeyVerification ?: false,
        )
        "SAF" -> ConnectionConfig.SAF(
            treeUri = saf?.treeUri ?: "",
            displayPath = saf?.displayPath ?: "",
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
    val accessKeyId: String,
    val secretAccessKey: String = "",
)

@Serializable
data class WebWebDavConfig(
    val url: String,
    val username: String,
    val password: String = "",
)

@Serializable
data class WebSftpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val path: String,
    val password: String = "",
    val knownHostsData: String = "",
    val hostKeyFingerprint: String = "",
    val insecureSkipHostKeyVerification: Boolean = false,
)

@Serializable
data class WebSafConfig(
    val treeUri: String,
    val displayPath: String,
)

@Serializable
data class WebSnapshotListRequest(
    val source: WebSourceInfo? = null,
)

@Serializable
data class WebListDirectoryRequest(
    val snapshotId: String,
    val path: String,
    val pageToken: String? = null,
    val pageSize: Int? = null,
)

@Serializable
data class WebRestoreRequest(
    val snapshotId: String,
    val sourcePath: String,
    val destinationUri: String,
    val options: WebRestoreOptions? = null,
)

@Serializable
data class WebRestoreOptions(
    val parallel: Int = 0,
    val incremental: Boolean = false,
    val overwriteExisting: Boolean = true,
) {
    fun toDomain() = RestoreOptions(
        parallel = parallel,
        incremental = incremental,
        overwriteExisting = overwriteExisting,
    )
}

@Serializable
data class WebDeleteSnapshotsRequest(
    val snapshotIds: List<String>,
)

@Serializable
data class WebPersistUriRequest(
    val uri: String,
    val read: Boolean = true,
    val write: Boolean = true,
)

// ===== Response Models =====

@Serializable
data class WebRepositoryConnection(
    val id: String,
    val displayName: String,
    val storageType: String,
    val connectionConfig: WebConnectionConfig,
    val lastConnectedEpochMs: Long? = null,
    val isConnected: Boolean,
)

@Serializable
data class WebSourceInfo(
    val host: String,
    val userName: String,
    val path: String,
) {
    fun toDomain() = SourceInfo(
        host = host,
        userName = userName,
        path = path,
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
    val tags: Map<String, String>,
)

@Serializable
data class WebSnapshotStats(
    val totalFileSize: Long,
    val totalFileCount: Long,
    val totalDirectoryCount: Long,
)

@Serializable
data class WebDirectoryPage(
    val entries: List<WebFileEntry>,
    val nextPageToken: String? = null,
)

@Serializable
data class WebFileEntry(
    val name: String,
    val type: String,
    val size: Long,
    val modTimeEpochMs: Long? = null,
    val permissions: Int,
    val objectId: String? = null,
)

@Serializable
data class WebRestoreProgress(
    val state: String,
    val totalFiles: Long,
    val restoredFiles: Long,
    val totalBytes: Long,
    val restoredBytes: Long,
    val currentFile: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class WebSafPickResult(
    val uri: String? = null,
    val displayName: String? = null,
)

@Serializable
data class WebSourceWithStats(
    val source: WebSourceInfo,
    val snapshotCount: Int,
    val latestSnapshotTime: Long,
    val totalFileCount: Long,
    val totalFileSize: Long,
)

@Serializable
data class WebSnapshotWithRetention(
    val id: String,
    val source: WebSourceInfo,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long? = null,
    val description: String,
    val stats: WebSnapshotStats? = null,
    val isIncomplete: Boolean,
    val tags: Map<String, String>,
    val retentionReasons: List<String>,
)

// ===== Domain -> Web Mappings =====

fun RepositoryConnection.toWeb() = WebRepositoryConnection(
    id = id,
    displayName = displayName,
    storageType = storageType.name,
    connectionConfig = connectionConfig.toWeb(),
    lastConnectedEpochMs = lastConnected?.toEpochMilli(),
    isConnected = isConnected,
)

fun ConnectionConfig.toWeb(): WebConnectionConfig = when (this) {
    is ConnectionConfig.LocalFilesystem -> WebConnectionConfig(
        storageType = "LOCAL_FILESYSTEM",
        local = WebLocalConfig(path = path),
    )
    is ConnectionConfig.S3 -> WebConnectionConfig(
        storageType = "S3",
        s3 = WebS3Config(
            bucket = bucket,
            endpoint = endpoint,
            region = region,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
        ),
    )
    is ConnectionConfig.WebDAV -> WebConnectionConfig(
        storageType = "WEBDAV",
        webdav = WebWebDavConfig(
            url = url,
            username = username,
            password = password,
        ),
    )
    is ConnectionConfig.SFTP -> WebConnectionConfig(
        storageType = "SFTP",
        sftp = WebSftpConfig(
            host = host,
            port = port,
            username = username,
            path = path,
            password = password,
            knownHostsData = knownHostsData,
            hostKeyFingerprint = hostKeyFingerprint,
            insecureSkipHostKeyVerification = insecureSkipHostKeyVerification,
        ),
    )
    is ConnectionConfig.SAF -> WebConnectionConfig(
        storageType = "SAF",
        saf = WebSafConfig(
            treeUri = treeUri,
            displayPath = displayPath,
        ),
    )
}

fun SourceInfo.toWeb() = WebSourceInfo(
    host = host,
    userName = userName,
    path = path,
)

fun SnapshotInfo.toWeb() = WebSnapshotInfo(
    id = id,
    source = source.toWeb(),
    startTimeEpochMs = startTime.toEpochMilli(),
    endTimeEpochMs = endTime?.toEpochMilli(),
    description = description,
    stats = stats?.toWeb(),
    isIncomplete = isIncomplete,
    tags = tags,
)

fun SnapshotStats.toWeb() = WebSnapshotStats(
    totalFileSize = totalFileSize,
    totalFileCount = totalFileCount.toLong(),
    totalDirectoryCount = totalDirectoryCount.toLong(),
)

fun FileEntry.toWeb() = WebFileEntry(
    name = name,
    type = type.name,
    size = size,
    modTimeEpochMs = modTime?.toEpochMilli(),
    permissions = permissions,
    objectId = objectId,
)

fun RestoreProgress.toWeb() = WebRestoreProgress(
    state = state.name,
    totalFiles = totalFiles,
    restoredFiles = restoredFiles,
    totalBytes = totalBytes,
    restoredBytes = restoredBytes,
    currentFile = currentFile,
    errorMessage = errorMessage,
)

fun RestoreState.isTerminal() = this == RestoreState.COMPLETED ||
    this == RestoreState.FAILED ||
    this == RestoreState.CANCELLED

// ===== Backup Source Models =====

@Serializable
data class WebCreateSourceRequest(
    val uri: String,
    val displayName: String = "",
    val startBackup: Boolean = false,
    /**
     * Policy chosen in the add-source wizard (schedule/compression/exclusions). Applied to the new
     * source at creation so the choices actually persist. Null/omitted = leave the source on the
     * inherited/global policy.
     */
    val policy: org.kopiaKt.snapshot.policy.Policy? = null,
) {
    /** Alias for bridge method that uses `path` parameter */
    val path: String get() = uri
}

@Serializable
data class WebBackupSourceInfo(
    val id: String,
    val path: String,
    val displayName: String,
    val status: String,
    val lastSnapshotTimeEpochMs: Long? = null,
    val createdAtEpochMs: Long,
)

/**
 * Source status matching the React UI's WebSourceStatus interface.
 * Uses a nested source field with { host, userName, path }.
 */
@Serializable
data class WebSourceStatus(
    val source: WebSourceInfo,
    val status: String,
    val nextBackupTimeEpochMs: Long? = null,
    val lastBackupTimeEpochMs: Long? = null,
    val currentTaskId: String? = null,
    val snapshotCount: Int = 0,
    val totalFileSize: Long = 0,
)

// ===== Task Models =====

@Serializable
data class WebTaskInfo(
    val id: String,
    val kind: String,
    val description: String,
    val status: String,
    val progressInfo: String = "",
    val counters: Map<String, WebTaskCounterValue> = emptyMap(),
    // The TS bridge contract names this field `error` (types/kopia.ts WebTaskInfo.error), unlike
    // WebRestoreProgress which uses `errorMessage` on both sides. Emit `error` so the task UI's
    // `task.error` actually populates on FAILED tasks.
    @SerialName("error")
    val errorMessage: String? = null,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long? = null,
)

@Serializable
data class WebTaskCounterValue(
    val value: Long,
    val units: String,
    val level: String = "",
)

// ===== Policy Request Models =====

@Serializable
data class WebPolicySourceRequest(
    val host: String,
    val userName: String,
    val path: String,
)

@Serializable
data class WebSetPolicyRequest(
    val source: WebPolicySourceRequest,
    val policy: org.kopiaKt.snapshot.policy.Policy,
)

// ===== New Request/Response Models =====

@Serializable
data class WebSupportedAlgorithms(
    val hashing: List<String>,
    val encryption: List<String>,
    val compression: List<String>,
)

@Serializable
data class WebCreateRepositoryRequest(
    val config: WebConnectionConfig,
    val password: String,
    val options: WebCreateRepoOptions,
)

@Serializable
data class WebCreateRepoOptions(
    val hash: String,
    val encryption: String,
    val compression: String,
    val description: String = "",
)

@Serializable
data class WebEstimateBackupRequest(
    val sourceId: String,
    val policyOverride: String? = null,
)

@Serializable
data class WebMaintenanceStatus(
    val lastRunTimeEpochMs: Long? = null,
    val lastMode: String? = null,
    val lastSuccess: Boolean? = null,
    val lastError: String? = null,
    val lastGcStats: WebMaintenanceGcStats? = null,
)

@Serializable
data class WebMaintenanceGcStats(
    val deletedContentCount: Int = 0,
    val reclaimedBytes: Long = 0,
)

@Serializable
data class WebTaskLogEntry(
    val timestamp: Long,
    val level: String,
    val module: String,
    val message: String,
)

@Serializable
data class WebResolvedPolicy(
    val effective: org.kopiaKt.snapshot.policy.Policy,
    val defined: org.kopiaKt.snapshot.policy.Policy?,
    val upcomingSnapshotTimes: List<Long>,
)

@Serializable
data class WebPolicyListEntry(
    val source: WebSourceInfo,
    val policy: org.kopiaKt.snapshot.policy.Policy,
)

@Serializable
data class WebRepositoryCreationResult(
    val storageType: String,
    val encryption: String,
    val hashing: String,
    val description: String? = null,
)

// ===== Backup Source -> Web Mappings =====

fun org.kopiaKt.android.worker.SourceInfo.toWeb() = WebBackupSourceInfo(
    id = id,
    path = path,
    displayName = displayName,
    status = status.name,
    lastSnapshotTimeEpochMs = lastSnapshotTime?.toEpochMilli(),
    createdAtEpochMs = createdAt.toEpochMilli(),
)

/**
 * The snapshot-policy identity of a local backup source. createSource stores the wizard policy under
 * this SourceInfo, and [toWebStatus] surfaces the same one to the UI, so the policy editor resolves the
 * exact policy the wizard set. Keep these two in sync via this single helper — a drift would silently
 * store the policy under a key the editor never reads.
 */
internal fun localSnapshotSourceInfo(path: String): org.kopiaKt.snapshot.model.SourceInfo = org.kopiaKt.snapshot.model.SourceInfo(
    host = android.os.Build.MODEL ?: "unknown",
    userName = "local",
    path = path,
)

fun org.kopiaKt.android.worker.SourceInfo.toWebStatus() = WebSourceStatus(
    source = localSnapshotSourceInfo(path).toWeb(),
    status = status.name,
    lastBackupTimeEpochMs = lastSnapshotTime?.toEpochMilli(),
    snapshotCount = 0,
    totalFileSize = 0,
)

fun org.kopiaKt.android.worker.TaskInfo.toWeb() = WebTaskInfo(
    id = id,
    // The TS contract (types/kopia.ts WebTaskInfo.kind, TaskListScreen TASK_KIND_ICON) keys on
    // Go-style names; emitting the raw enum name (BACKUP/…) makes TASK_KIND_ICON[kind] undefined and
    // crashes the task-list render. A backup task produces a snapshot, hence BACKUP -> "Snapshot".
    kind = when (kind) {
        TaskKind.BACKUP -> "Snapshot"
        TaskKind.RESTORE -> "Restore"
        TaskKind.MAINTENANCE -> "Maintenance"
        TaskKind.ESTIMATE -> "Estimate"
    },
    description = description,
    status = status.name,
    progressInfo = progressInfo,
    counters = counters.mapValues { (_, v) ->
        WebTaskCounterValue(value = v.value, units = v.units, level = v.level)
    },
    errorMessage = errorMessage,
    startTimeEpochMs = startTime.toEpochMilli(),
    endTimeEpochMs = endTime?.toEpochMilli(),
)

fun WebPolicySourceRequest.toSnapshotSourceInfo() = org.kopiaKt.snapshot.model.SourceInfo(
    host = host,
    userName = userName,
    path = path,
)

fun org.kopiaKt.snapshot.model.SourceInfo.toWeb() = WebSourceInfo(
    host = host,
    userName = userName,
    path = path,
)
