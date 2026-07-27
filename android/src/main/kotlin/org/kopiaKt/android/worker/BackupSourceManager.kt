package org.kopiaKt.android.worker

import java.time.Instant
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
 * @property id The source's durable identity, `user@host:path`. Not a random UUID: it is the same
 *   string the snapshot manifests and the source's policy are keyed by, and the only handle the UI
 *   has for addressing this source.
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
 * Sources are stored in-memory using a ConcurrentHashMap for thread safety, keyed by the durable
 * `user@host:path` identity.
 */
class BackupSourceManager {

    private val sources = ConcurrentHashMap<String, SourceInfo>()

    /**
     * Registers a backup source under its durable identity.
     *
     * Re-registering an existing identity updates the display name and keeps the accumulated state
     * (status, last snapshot time, creation time) rather than replacing the source — the same path
     * is the same source, so adding it twice must not produce two rows with divergent history.
     *
     * @param id The source's durable identity, `user@host:path`
     * @param path Local filesystem path or SAF content:// URI
     * @param displayName Human-readable name. If empty, the path is used as the display name.
     */
    fun createSource(id: String, path: String, displayName: String): SourceInfo {
        val effectiveDisplayName = displayName.ifEmpty { path }
        return sources.compute(id) { _, existing ->
            existing?.copy(path = path, displayName = effectiveDisplayName)
                ?: SourceInfo(id = id, path = path, displayName = effectiveDisplayName)
        }!!
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
