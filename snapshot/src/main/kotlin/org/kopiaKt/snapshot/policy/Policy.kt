package org.kopiaKt.snapshot.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Complete policy for a backup source.
 *
 * Policies control retention, file selection, compression, and scheduling.
 * The structure must match the Go implementation for cross-compatibility.
 */
@Serializable
data class Policy(
    val retention: RetentionPolicy = RetentionPolicy(),
    val files: FilesPolicy = FilesPolicy(),
    val errorHandling: ErrorHandlingPolicy = ErrorHandlingPolicy(),
    val scheduling: SchedulingPolicy = SchedulingPolicy(),
    val compression: CompressionPolicy = CompressionPolicy(),
    val splitter: SplitterPolicy = SplitterPolicy(),
    val upload: UploadPolicy = UploadPolicy(),
    val actions: ActionsPolicy = ActionsPolicy(),
    val logging: LoggingPolicy = LoggingPolicy(),
    @SerialName("noParent") val noParent: Boolean = false
)

/**
 * Retention policy controlling how many snapshots to keep.
 */
@Serializable
data class RetentionPolicy(
    val keepLatest: Int? = null,
    val keepHourly: Int? = null,
    val keepDaily: Int? = null,
    val keepWeekly: Int? = null,
    val keepMonthly: Int? = null,
    val keepAnnual: Int? = null,
    val ignoreIdenticalSnapshots: Boolean? = null
)

/**
 * File selection policy controlling what files to include/exclude.
 */
@Serializable
data class FilesPolicy(
    @SerialName("ignore") val ignoreRules: List<String> = emptyList(),
    @SerialName("ignoreDotFiles") val ignoreDotFiles: List<String> = emptyList(),
    val maxFileSize: Long? = null,
    @SerialName("noParentIgnore") val noParentIgnore: Boolean = false,
    @SerialName("noParentDotFiles") val noParentDotFiles: Boolean = false,
    @SerialName("oneFileSystem") val oneFileSystem: Boolean? = null
)

/**
 * Error handling policy controlling how to handle errors during backup.
 */
@Serializable
data class ErrorHandlingPolicy(
    val ignoreFileErrors: Boolean? = null,
    val ignoreDirectoryErrors: Boolean? = null,
    val ignoreUnknownTypes: Boolean? = null
)

/**
 * Scheduling policy for automated backups.
 */
@Serializable
data class SchedulingPolicy(
    val intervalSeconds: Long? = null,
    val timesOfDay: List<TimeOfDay>? = null,
    val manual: Boolean = false,
    val runMissed: Boolean? = null,
    val cron: String? = null
)

/**
 * Time of day for scheduled backups.
 */
@Serializable
data class TimeOfDay(
    val hour: Int,
    val minute: Int = 0
)

/**
 * Compression policy controlling compression algorithm and when to compress.
 */
@Serializable
data class CompressionPolicy(
    val compressorName: String = "zstd",
    val onlyCompress: List<String>? = null,
    val neverCompress: List<String>? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null
)

/**
 * Splitter policy controlling content chunking.
 */
@Serializable
data class SplitterPolicy(
    val algorithm: String = "DYNAMIC-4M-BUZHASH"
)

/**
 * Upload policy controlling upload behavior.
 */
@Serializable
data class UploadPolicy(
    val maxParallelSnapshots: Int? = null,
    val maxParallelFileReads: Int? = null
)

/**
 * Actions policy for pre/post backup hooks.
 */
@Serializable
data class ActionsPolicy(
    val beforeSnapshotRoot: ActionCommand? = null,
    val afterSnapshotRoot: ActionCommand? = null,
    val beforeFolder: ActionCommand? = null,
    val afterFolder: ActionCommand? = null
)

/**
 * Command to execute as a hook action.
 */
@Serializable
data class ActionCommand(
    val path: String,
    val args: List<String> = emptyList(),
    val timeout: Long? = null, // seconds
    val mode: ActionMode = ActionMode.ESSENTIAL
)

/**
 * Mode for action command execution.
 */
@Serializable
enum class ActionMode {
    @SerialName("essential") ESSENTIAL, // Fail backup if action fails
    @SerialName("optional") OPTIONAL,   // Continue backup if action fails
    @SerialName("async") ASYNC          // Run action asynchronously
}

/**
 * Logging policy controlling backup log verbosity.
 */
@Serializable
data class LoggingPolicy(
    val directories: LogDetail? = null,
    val entries: LogDetail? = null
)

/**
 * Log detail level.
 */
@Serializable
data class LogDetail(
    val snapshotted: Int? = null,
    val cached: Int? = null
)
