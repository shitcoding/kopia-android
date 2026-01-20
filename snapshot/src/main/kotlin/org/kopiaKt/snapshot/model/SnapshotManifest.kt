package org.kopiaKt.snapshot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kopiaKt.core.content.ContentId

/**
 * Represents a snapshot manifest containing metadata about a backup.
 *
 * The structure must match the Go implementation for cross-compatibility.
 */
@Serializable
data class SnapshotManifest(
    val id: String,
    val source: SourceInfo,
    val description: String = "",
    val startTime: String, // ISO-8601 timestamp
    val endTime: String? = null, // ISO-8601 timestamp
    val stats: SnapshotStats? = null,
    val rootEntry: DirEntry? = null,
    val retentionReasons: List<String> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    val pins: List<String> = emptyList(),
    val incomplete: String? = null // Reason if snapshot is incomplete
)

/**
 * Information about the source of a backup.
 */
@Serializable
data class SourceInfo(
    val host: String,
    val userName: String,
    val path: String
) {
    override fun toString(): String = "$userName@$host:$path"

    companion object {
        /**
         * Parses a source string in the format "user@host:path".
         */
        fun parse(source: String): SourceInfo? {
            val atIndex = source.indexOf('@')
            val colonIndex = source.lastIndexOf(':')

            if (atIndex == -1 || colonIndex == -1 || colonIndex <= atIndex) {
                return null
            }

            return SourceInfo(
                userName = source.substring(0, atIndex),
                host = source.substring(atIndex + 1, colonIndex),
                path = source.substring(colonIndex + 1)
            )
        }
    }
}

/**
 * Type of filesystem entry.
 */
@Serializable
enum class EntryType {
    @SerialName("f") FILE,
    @SerialName("d") DIRECTORY,
    @SerialName("s") SYMLINK
}

/**
 * Represents a filesystem entry in a snapshot.
 */
@Serializable
data class DirEntry(
    val name: String,
    val type: EntryType,
    val mode: Int? = null, // Unix permissions
    val size: Long = 0,
    val mtime: String? = null, // ISO-8601 timestamp
    @SerialName("uid") val userId: Int? = null,
    @SerialName("gid") val groupId: Int? = null,
    @SerialName("obj") val objectId: String? = null, // ObjectId as string
    @SerialName("summ") val dirSummary: DirSummary? = null, // For directories
    val target: String? = null // Symlink target
)

/**
 * Summary statistics for a directory.
 */
@Serializable
data class DirSummary(
    val files: Long = 0,
    val dirs: Long = 0,
    val symlinks: Long = 0,
    val size: Long = 0,
    val maxTime: String? = null // Latest modification time
)

/**
 * Statistics about a snapshot.
 */
@Serializable
data class SnapshotStats(
    val totalFileCount: Long = 0,
    val totalFileSize: Long = 0,
    val totalDirectoryCount: Long = 0,
    val excludedFileCount: Long = 0,
    val excludedTotalFileSize: Long = 0,
    val errorCount: Long = 0,
    val cachedFiles: Long = 0,
    val nonCachedFiles: Long = 0,
    val readErrors: Long = 0,
    val ignoredErrorCount: Long = 0
)

/**
 * Manifest labels used for querying snapshots.
 */
object ManifestLabels {
    const val TYPE = "type"
    const val TYPE_SNAPSHOT = "snapshot"
    const val HOST = "hostname"
    const val USERNAME = "username"
    const val PATH = "path"
}
