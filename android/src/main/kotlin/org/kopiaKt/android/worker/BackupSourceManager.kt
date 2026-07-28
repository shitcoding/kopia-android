package org.kopiaKt.android.worker

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

/** On-disk shape of a source. Separate from [SourceInfo] so `Instant` stays out of the wire format. */
@Serializable
private data class StoredSource(
    val id: String,
    val path: String,
    val displayName: String,
    val lastSnapshotTimeEpochMs: Long? = null,
    val createdAtEpochMs: Long,
)

/**
 * Manages backup sources (directories/URIs to back up), keyed by the durable `user@host:path`
 * identity.
 *
 * Held in a [ConcurrentHashMap] and persisted to SharedPreferences as one small JSON document.
 * SharedPreferences rather than DataStore because every caller here is synchronous — the
 * `@JavascriptInterface` bridge and the worker — and DataStore's suspend-only API would mean
 * `runBlocking` around a file read on a UI-adjacent thread for a list that is only ever a handful of
 * entries.
 *
 * **Status is deliberately not persisted.** It describes what is happening right now; a PAUSED flag
 * would be worth keeping, but reloading a source as UPLOADING after the process died would show a
 * backup that is not running. Restored sources come back IDLE.
 *
 * @param context null in tests that only need the in-memory behaviour.
 */
class BackupSourceManager(private val context: Context? = null) {

    private val sources = ConcurrentHashMap<String, SourceInfo>()

    init {
        load()
    }

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
        val source = sources.compute(id) { _, existing ->
            existing?.copy(path = path, displayName = effectiveDisplayName)
                ?: SourceInfo(id = id, path = path, displayName = effectiveDisplayName)
        }!!
        persist()
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
     * Forgets the source with the given ID. No-op if the ID does not exist.
     *
     * This is only the record; the surrounding cleanup a durable source needs (cancelling its
     * pending work, clearing its checkpoint, releasing its SAF grant) lives in
     * [BackupSourceDeleter], which deliberately keeps the repository's snapshots and the source's
     * policy — deleting a source stops backing a folder up, it does not throw away what was backed
     * up, and re-adding the same folder should find its settings intact.
     */
    fun deleteSource(id: String) {
        if (sources.remove(id) != null) {
            persist()
        }
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
        persist()
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

    private fun prefs() = context?.applicationContext
        ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load() {
        val stored = prefs()?.getString(KEY_SOURCES, null) ?: return
        try {
            for (it in json.decodeFromString<List<StoredSource>>(stored)) {
                sources[it.id] = SourceInfo(
                    id = it.id,
                    path = it.path,
                    displayName = it.displayName,
                    lastSnapshotTime = it.lastSnapshotTimeEpochMs?.let(Instant::ofEpochMilli),
                    createdAt = Instant.ofEpochMilli(it.createdAtEpochMs),
                )
            }
        } catch (e: Exception) {
            // A corrupt record must not make the app unusable; the user re-adds their folders.
            android.util.Log.w(TAG, "could not read the stored backup sources", e)
        }
    }

    private fun persist() {
        val prefs = prefs() ?: return
        val stored = sources.values.map {
            StoredSource(
                id = it.id,
                path = it.path,
                displayName = it.displayName,
                lastSnapshotTimeEpochMs = it.lastSnapshotTime?.toEpochMilli(),
                createdAtEpochMs = it.createdAt.toEpochMilli(),
            )
        }
        // commit(), not apply(): adding and deleting a source are user-visible acts that are
        // acknowledged immediately, and a delete lost to process death would resurrect the source.
        prefs.edit().putString(KEY_SOURCES, json.encodeToString(stored)).commit()
    }

    private companion object {
        const val TAG = "BackupSourceManager"
        const val PREFS_NAME = "kopia_backup_sources"
        const val KEY_SOURCES = "sources"
        val json = Json { ignoreUnknownKeys = true }
    }
}
