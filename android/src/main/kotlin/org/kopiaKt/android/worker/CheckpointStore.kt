package org.kopiaKt.android.worker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * DataStore for backup checkpoint persistence.
 */
private val Context.checkpointDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "backup_checkpoints",
)

/**
 * Represents a saved checkpoint state for resuming interrupted backups.
 */
@Serializable
data class BackupCheckpoint(
    /** The backup source ID. */
    val sourceId: String,

    /** The source path being backed up. */
    val sourcePath: String,

    /** Repository connection information (serialized). */
    val repositoryConnectionJson: String,

    /** The manifest ID of the incomplete snapshot, if any. */
    val incompleteManifestId: String? = null,

    /** Object ID of the last completed directory, for resuming tree walk. */
    val lastCompletedDirObjectId: String? = null,

    /** Path of the last completed directory, relative to source root. */
    val lastCompletedDirPath: String? = null,

    /** Number of files processed before checkpoint. */
    val processedFiles: Int = 0,

    /** Bytes processed before checkpoint. */
    val processedBytes: Long = 0,

    /** Timestamp when checkpoint was created (epoch millis). */
    val checkpointTime: Long = System.currentTimeMillis(),

    /** Timestamp when backup was started (epoch millis). */
    val startTime: Long = System.currentTimeMillis(),

    /** Number of times this backup has been resumed. */
    val resumeCount: Int = 0,

    /** Last error message, if backup failed and is being retried. */
    val lastError: String? = null,
) {
    /**
     * Age of this checkpoint in milliseconds.
     */
    fun ageMillis(): Long = System.currentTimeMillis() - checkpointTime

    /**
     * Returns true if this checkpoint is stale (older than the specified duration).
     */
    fun isStale(maxAgeMillis: Long = DEFAULT_MAX_CHECKPOINT_AGE_MILLIS): Boolean = ageMillis() > maxAgeMillis

    companion object {
        /** Default maximum age for a checkpoint before it's considered stale (24 hours). */
        const val DEFAULT_MAX_CHECKPOINT_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * Result of a checkpoint lookup.
 */
sealed class CheckpointResult {
    /** No checkpoint found for the source. */
    data object NotFound : CheckpointResult()

    /** Checkpoint found and is valid for resuming. */
    data class Found(val checkpoint: BackupCheckpoint) : CheckpointResult()

    /** Checkpoint found but is too old to use. */
    data class Stale(val checkpoint: BackupCheckpoint) : CheckpointResult()
}

/**
 * Manages persistence of backup checkpoints for process death recovery.
 *
 * When a backup is interrupted (process death, system kill, etc.), the checkpoint
 * allows resuming from approximately where it left off rather than starting over.
 *
 * Checkpoints are stored per-source, so multiple backup sources can have independent
 * checkpoint states.
 */
class CheckpointStore(
    private val context: Context,
    private val maxCheckpointAgeMillis: Long = BackupCheckpoint.DEFAULT_MAX_CHECKPOINT_AGE_MILLIS,
) {
    private val dataStore: DataStore<Preferences> = context.checkpointDataStore
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Saves a checkpoint for the given source.
     *
     * @param checkpoint The checkpoint to save
     */
    suspend fun saveCheckpoint(checkpoint: BackupCheckpoint) {
        val key = checkpointKey(checkpoint.sourceId)
        dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(checkpoint)
        }
    }

    /**
     * Retrieves the checkpoint for the given source, if any.
     *
     * @param sourceId The source ID to look up
     * @return CheckpointResult indicating whether a valid checkpoint exists
     */
    suspend fun getCheckpoint(sourceId: String): CheckpointResult {
        val key = checkpointKey(sourceId)
        val prefs = dataStore.data.first()
        val checkpointJson = prefs[key] ?: return CheckpointResult.NotFound

        return try {
            val checkpoint = json.decodeFromString<BackupCheckpoint>(checkpointJson)
            if (checkpoint.isStale(maxCheckpointAgeMillis)) {
                CheckpointResult.Stale(checkpoint)
            } else {
                CheckpointResult.Found(checkpoint)
            }
        } catch (e: Exception) {
            CheckpointResult.NotFound
        }
    }

    /**
     * Clears the checkpoint for the given source.
     *
     * Should be called when a backup completes successfully.
     *
     * @param sourceId The source ID to clear
     */
    suspend fun clearCheckpoint(sourceId: String) {
        val key = checkpointKey(sourceId)
        dataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    /**
     * Updates an existing checkpoint with new progress.
     *
     * @param sourceId The source ID
     * @param update Function to update the checkpoint
     */
    suspend fun updateCheckpoint(sourceId: String, update: (BackupCheckpoint) -> BackupCheckpoint) {
        val key = checkpointKey(sourceId)
        dataStore.edit { prefs ->
            val existingJson = prefs[key] ?: return@edit
            try {
                val existing = json.decodeFromString<BackupCheckpoint>(existingJson)
                val updated = update(existing)
                prefs[key] = json.encodeToString(updated)
            } catch (e: Exception) {
                // Ignore update if checkpoint is corrupted
            }
        }
    }

    /**
     * Observes the checkpoint for the given source.
     */
    fun observeCheckpoint(sourceId: String): Flow<BackupCheckpoint?> {
        val key = checkpointKey(sourceId)
        return dataStore.data.map { prefs ->
            val checkpointJson = prefs[key] ?: return@map null
            try {
                json.decodeFromString<BackupCheckpoint>(checkpointJson)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Lists all sources with active checkpoints.
     */
    suspend fun listActiveCheckpoints(): List<BackupCheckpoint> {
        val prefs = dataStore.data.first()
        return prefs.asMap()
            .filter { (key, _) -> key.name.startsWith(CHECKPOINT_PREFIX) }
            .mapNotNull { (_, value) ->
                try {
                    json.decodeFromString<BackupCheckpoint>(value as String)
                } catch (e: Exception) {
                    null
                }
            }
            .filter { !it.isStale(maxCheckpointAgeMillis) }
    }

    /**
     * Clears all stale checkpoints.
     *
     * @return Number of checkpoints cleared
     */
    suspend fun clearStaleCheckpoints(): Int {
        val prefs = dataStore.data.first()
        val staleKeys = mutableListOf<Preferences.Key<String>>()

        prefs.asMap().forEach { (key, value) ->
            if (key.name.startsWith(CHECKPOINT_PREFIX)) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val checkpoint = json.decodeFromString<BackupCheckpoint>(value as String)
                    if (checkpoint.isStale(maxCheckpointAgeMillis)) {
                        @Suppress("UNCHECKED_CAST")
                        staleKeys.add(key as Preferences.Key<String>)
                    }
                } catch (e: Exception) {
                    @Suppress("UNCHECKED_CAST")
                    staleKeys.add(key as Preferences.Key<String>)
                }
            }
        }

        if (staleKeys.isNotEmpty()) {
            dataStore.edit { prefs ->
                staleKeys.forEach { prefs.remove(it) }
            }
        }

        return staleKeys.size
    }

    /**
     * Clears all checkpoints.
     */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            val keysToRemove = prefs.asMap().keys.filter { it.name.startsWith(CHECKPOINT_PREFIX) }
            keysToRemove.forEach { prefs.remove(it) }
        }
    }

    private fun checkpointKey(sourceId: String): Preferences.Key<String> = stringPreferencesKey("$CHECKPOINT_PREFIX$sourceId")

    companion object {
        private const val CHECKPOINT_PREFIX = "checkpoint_"
    }
}

/**
 * Options for checkpoint behavior during backup.
 */
data class CheckpointOptions(
    /** Interval between checkpoints in milliseconds. */
    val intervalMillis: Long = DEFAULT_CHECKPOINT_INTERVAL_MILLIS,

    /** Minimum bytes processed before creating a checkpoint. */
    val minBytesBeforeCheckpoint: Long = DEFAULT_MIN_BYTES_BEFORE_CHECKPOINT,

    /** Maximum number of resume attempts before giving up. */
    val maxResumeAttempts: Int = DEFAULT_MAX_RESUME_ATTEMPTS,
) {
    /**
     * The checkpoint interval clamped to a sane minimum. The checkpoint loop delays by this each cycle;
     * a zero or negative [intervalMillis] (misconfiguration / test) would otherwise make `delay()` return
     * immediately and spin a tight busy-loop, draining CPU/battery. See task-14.
     */
    val effectiveIntervalMillis: Long
        get() = intervalMillis.coerceAtLeast(MIN_CHECKPOINT_INTERVAL_MILLIS)

    companion object {
        /** Default checkpoint interval (5 minutes). */
        const val DEFAULT_CHECKPOINT_INTERVAL_MILLIS = 5L * 60 * 1000

        /** Floor for the checkpoint interval so a zero/negative config can't busy-loop the delay. */
        const val MIN_CHECKPOINT_INTERVAL_MILLIS = 1000L

        /** Default minimum bytes before first checkpoint (10 MB). */
        const val DEFAULT_MIN_BYTES_BEFORE_CHECKPOINT = 10L * 1024 * 1024

        /** Default maximum resume attempts. */
        const val DEFAULT_MAX_RESUME_ATTEMPTS = 3
    }
}
