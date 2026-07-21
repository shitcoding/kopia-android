package org.kopiaKt.android.worker

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Status of a backup source.
 */
enum class SourceStatus {
    IDLE,
    UPLOADING,
    PAUSED,
}

/**
 * Information about a configured backup source.
 *
 * @property id Unique identifier (UUID-based)
 * @property path Local filesystem path or SAF content:// URI
 * @property displayName Human-readable name for the source
 * @property status Current operational status
 * @property lastSnapshotTime Time of the most recent completed snapshot, or null if never backed up
 * @property createdAt Time when this source was created
 */
data class SourceInfo(
    val id: String,
    val path: String,
    val displayName: String,
    val status: SourceStatus = SourceStatus.IDLE,
    val lastSnapshotTime: Instant? = null,
    val createdAt: Instant = Instant.now(),
)

/**
 * Manages backup sources (directories/URIs to back up).
 *
 * Sources are stored in-memory using a ConcurrentHashMap for thread safety.
 * Persistence via manifests is handled separately.
 */
class BackupSourceManager {

    private val sources = ConcurrentHashMap<String, SourceInfo>()

    /**
     * Creates a new backup source.
     *
     * @param path Local filesystem path or SAF content:// URI
     * @param displayName Human-readable name. If empty, the path is used as the display name.
     * @return The newly created SourceInfo with a generated UUID
     */
    fun createSource(path: String, displayName: String): SourceInfo {
        val effectiveDisplayName = displayName.ifEmpty { path }
        val source = SourceInfo(
            id = UUID.randomUUID().toString(),
            path = path,
            displayName = effectiveDisplayName,
        )
        sources[source.id] = source
        return source
    }

    /**
     * Returns the source with the given ID, or null if not found.
     */
    fun getSource(id: String): SourceInfo? = sources[id]

    /**
     * Returns all configured backup sources.
     */
    fun listSources(): List<SourceInfo> = sources.values.toList()

    /**
     * Removes the source with the given ID. No-op if the ID does not exist.
     */
    fun deleteSource(id: String) {
        sources.remove(id)
    }

    /**
     * Sets the status of a source. No-op if the source ID does not exist.
     */
    fun setSourceStatus(id: String, status: SourceStatus) {
        sources.computeIfPresent(id) { _, existing ->
            existing.copy(status = status)
        }
    }

    /**
     * Records the last snapshot completion time for a source.
     * No-op if the source ID does not exist.
     */
    fun updateLastSnapshotTime(id: String, time: Instant) {
        sources.computeIfPresent(id) { _, existing ->
            existing.copy(lastSnapshotTime = time)
        }
    }

    /**
     * Pauses a source (sets status to PAUSED). No-op if the source ID does not exist.
     */
    fun pauseSource(id: String) {
        setSourceStatus(id, SourceStatus.PAUSED)
    }

    /**
     * Resumes a paused source (sets status back to IDLE). No-op if the source ID does not exist.
     */
    fun resumeSource(id: String) {
        setSourceStatus(id, SourceStatus.IDLE)
    }
}
