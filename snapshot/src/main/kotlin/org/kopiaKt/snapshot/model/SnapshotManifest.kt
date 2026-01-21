package org.kopiaKt.snapshot.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Represents a snapshot manifest containing metadata about a backup.
 *
 * The structure must match the Go implementation for cross-compatibility.
 * Go type: snapshot.Manifest
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SnapshotManifest(
    val id: String,
    val source: SourceInfo,
    val description: String = "",
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    @Serializable(with = InstantSerializer::class)
    val endTime: Instant? = null,
    val stats: SnapshotStats? = null,
    @SerialName("incomplete")
    val incompleteReason: String? = null,
    val rootEntry: DirEntry? = null,
    // RetentionReasons is not persisted in Go (json:"-")
    val tags: Map<String, String> = emptyMap(),
    val storageStats: StorageStats? = null,
    val pins: List<String> = emptyList()
)

/**
 * Information about the source of a backup.
 *
 * Go type: snapshot.SourceInfo
 */
@Serializable
data class SourceInfo(
    val host: String,
    val userName: String,
    val path: String
) {
    override fun toString(): String {
        if (host.isEmpty() && path.isEmpty() && userName.isEmpty()) {
            return "(global)"
        }
        if (path.isEmpty()) {
            return "$userName@$host"
        }
        return "$userName@$host:$path"
    }

    companion object {
        /**
         * Parses a source string in the format "user@host:path" or "user@host".
         * Returns null if the string format is invalid.
         */
        fun parse(source: String): SourceInfo? {
            if (source == "(global)") {
                return SourceInfo(host = "", userName = "", path = "")
            }

            val atIndex = source.indexOf('@')
            val colonIndex = source.indexOf(':')

            if (atIndex <= 0) {
                return null
            }

            // Format: user@host:path
            if (colonIndex > atIndex && colonIndex < source.length - 1) {
                return SourceInfo(
                    userName = source.substring(0, atIndex),
                    host = source.substring(atIndex + 1, colonIndex),
                    path = source.substring(colonIndex + 1)
                )
            }

            // Format: user@host (no path)
            if (colonIndex == -1 && atIndex + 1 < source.length) {
                return SourceInfo(
                    userName = source.substring(0, atIndex),
                    host = source.substring(atIndex + 1),
                    path = ""
                )
            }

            return null
        }
    }
}

/**
 * Type of filesystem entry.
 *
 * Go type: snapshot.EntryType
 */
@Serializable(with = EntryTypeSerializer::class)
enum class EntryType(val code: String) {
    UNKNOWN(""),
    FILE("f"),
    DIRECTORY("d"),
    SYMLINK("s");

    companion object {
        fun fromCode(code: String): EntryType =
            entries.find { it.code == code } ?: UNKNOWN
    }
}

/**
 * Serializer for EntryType to match Go's single-character string representation.
 */
internal object EntryTypeSerializer : KSerializer<EntryType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("EntryType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: EntryType) {
        encoder.encodeString(value.code)
    }

    override fun deserialize(decoder: Decoder): EntryType {
        return EntryType.fromCode(decoder.decodeString())
    }
}

/**
 * Represents a filesystem entry in a snapshot.
 *
 * Go type: snapshot.DirEntry
 */
@Serializable
data class DirEntry(
    val name: String = "",
    val type: EntryType = EntryType.UNKNOWN,
    @Serializable(with = PermissionsSerializer::class)
    @SerialName("mode")
    val permissions: Int = 0,
    @SerialName("size")
    val fileSize: Long = 0,
    @Serializable(with = InstantSerializer::class)
    @SerialName("mtime")
    val modTime: Instant? = null,
    @SerialName("uid")
    val userId: Int? = null,
    @SerialName("gid")
    val groupId: Int? = null,
    @SerialName("obj")
    val objectId: String? = null,
    @SerialName("summ")
    val dirSummary: DirectorySummary? = null
)

/**
 * Serializer for Unix permissions to/from octal string format.
 *
 * Go serializes permissions as octal strings like "0755".
 * Zero permissions are omitted entirely.
 */
internal object PermissionsSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Permissions", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int) {
        if (value == 0) {
            // Go omits zero permissions - but we can't omit from here
            // The caller should use encodeDefaults = false and nullable type
            // For now, encode as empty or "0"
            encoder.encodeString("0${value.toString(8)}")
        } else {
            encoder.encodeString("0${value.toString(8)}")
        }
    }

    override fun deserialize(decoder: Decoder): Int {
        val str = decoder.decodeString()
        return if (str.isEmpty()) {
            0
        } else {
            // Parse octal string, with or without leading "0"
            str.toIntOrNull(8) ?: str.removePrefix("0").toIntOrNull(8) ?: 0
        }
    }
}

/**
 * Summary statistics for a directory.
 *
 * Go type: fs.DirectorySummary
 */
@Serializable
data class DirectorySummary(
    @SerialName("size")
    val totalFileSize: Long = 0,
    @SerialName("files")
    val totalFileCount: Long = 0,
    @SerialName("symlinks")
    val totalSymlinkCount: Long = 0,
    @SerialName("dirs")
    val totalDirCount: Long = 0,
    @Serializable(with = InstantSerializer::class)
    @SerialName("maxTime")
    val maxModTime: Instant? = null,
    @SerialName("incomplete")
    val incompleteReason: String? = null,
    @SerialName("numFailed")
    val fatalErrorCount: Int = 0,
    @SerialName("numIgnoredErrors")
    val ignoredErrorCount: Int = 0,
    @SerialName("errors")
    val failedEntries: List<EntryWithError>? = null
)

/**
 * Represents an entry that failed to process.
 *
 * Go type: fs.EntryWithError
 */
@Serializable
data class EntryWithError(
    @SerialName("path")
    val entryPath: String,
    val error: String
)

/**
 * Statistics about a snapshot.
 *
 * Go type: snapshot.Stats
 */
@Serializable
data class SnapshotStats(
    @SerialName("totalSize")
    val totalFileSize: Long = 0,
    @SerialName("excludedTotalSize")
    val excludedTotalFileSize: Long = 0,
    @SerialName("fileCount")
    val totalFileCount: Int = 0,
    val cachedFiles: Int = 0,
    val nonCachedFiles: Int = 0,
    @SerialName("dirCount")
    val totalDirectoryCount: Int = 0,
    val excludedFileCount: Int = 0,
    val excludedDirCount: Int = 0,
    val ignoredErrorCount: Int = 0,
    val errorCount: Int = 0
)

/**
 * Storage usage statistics for a snapshot.
 *
 * Go type: snapshot.StorageStats
 */
@Serializable
data class StorageStats(
    val newData: StorageUsageDetails = StorageUsageDetails(),
    val runningTotal: StorageUsageDetails = StorageUsageDetails()
)

/**
 * Details about storage usage.
 *
 * Go type: snapshot.StorageUsageDetails
 */
@Serializable
data class StorageUsageDetails(
    val objectBytes: Long = 0,
    val originalContentBytes: Long = 0,
    val packedContentBytes: Long = 0,
    @SerialName("fileObjects")
    val fileObjectCount: Int = 0,
    @SerialName("dirObjects")
    val dirObjectCount: Int = 0,
    @SerialName("contents")
    val contentCount: Int = 0
)

/**
 * Represents serialized contents of a directory.
 *
 * Go type: snapshot.DirManifest
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DirManifest(
    @EncodeDefault
    @SerialName("stream")
    val streamType: String = DIRECTORY_STREAM_TYPE,
    val entries: List<DirEntry> = emptyList(),
    val summary: DirectorySummary? = null
) {
    companion object {
        const val DIRECTORY_STREAM_TYPE = "kopia:directory"
    }

    /**
     * Returns true if this manifest has the correct stream type for a directory.
     */
    fun isValidDirectoryStream(): Boolean = streamType == DIRECTORY_STREAM_TYPE
}

/**
 * Manifest labels used for querying snapshots.
 */
object ManifestLabels {
    const val TYPE = "type"
    const val TYPE_SNAPSHOT = "snapshot"
    const val HOST = "hostname"
    const val USERNAME = "username"
    const val PATH = "path"

    /**
     * Creates manifest labels for a snapshot with the given source info.
     */
    fun forSnapshot(source: SourceInfo): Map<String, String> = mapOf(
        TYPE to TYPE_SNAPSHOT,
        HOST to source.host,
        USERNAME to source.userName,
        PATH to source.path
    )
}

/**
 * Serializer for java.time.Instant to/from Go-compatible ISO-8601 format.
 *
 * Go uses RFC3339Nano format: "2006-01-02T15:04:05.999999999Z07:00"
 * This serializer handles both with and without nanoseconds, and with timezone offsets.
 */
internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    // Formatter that outputs nanoseconds with trailing zeros trimmed
    private val outputFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    // Parser that handles various input formats including timezone offsets
    private val inputFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .optionalStart()
        .appendOffset("+HH:MM", "Z")
        .optionalEnd()
        .optionalStart()
        .appendOffset("+HH:mm", "Z")
        .optionalEnd()
        .toFormatter()

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(outputFormatter.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val str = decoder.decodeString()
        return try {
            // Try parsing as Instant directly (handles Z timezone)
            Instant.parse(str)
        } catch (_: Exception) {
            // Try parsing with offset (handles +/-HH:MM timezone)
            try {
                OffsetDateTime.parse(str, inputFormatter).toInstant()
            } catch (_: Exception) {
                // Last resort: try the flexible formatter
                OffsetDateTime.parse(str).toInstant()
            }
        }
    }
}
