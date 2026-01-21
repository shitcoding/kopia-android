package org.kopiaKt.snapshot.upload

import org.kopiaKt.snapshot.model.DirEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Callback that creates a checkpoint entry for an upload item.
 */
typealias CheckpointCallback = suspend () -> DirEntry?

/**
 * Registry for checkpoint callbacks during an upload.
 *
 * When a checkpoint is requested, all registered callbacks are invoked
 * to create a snapshot of the current upload state. This allows resuming
 * from a partial snapshot without re-uploading already completed content.
 *
 * Go type: upload.checkpointRegistry
 */
class CheckpointRegistry {
    private val lock = ReentrantLock()
    private val callbacks = ConcurrentHashMap<String, CheckpointCallback>()

    /**
     * Registers a checkpoint callback for an upload item.
     *
     * @param name The name/key for this callback (typically the file/directory name)
     * @param callback The callback to invoke during checkpoints
     */
    fun addCheckpointCallback(name: String, callback: CheckpointCallback) {
        callbacks[name] = callback
    }

    /**
     * Removes a checkpoint callback.
     *
     * @param name The name/key of the callback to remove
     */
    fun removeCheckpointCallback(name: String) {
        callbacks.remove(name)
    }

    /**
     * Runs all registered checkpoints and populates the given builder.
     *
     * @param builder The DirManifestBuilder to populate with checkpoint entries
     * @return List of errors that occurred during checkpoint
     */
    suspend fun runCheckpoints(builder: DirManifestBuilder): List<Throwable> {
        val errors = mutableListOf<Throwable>()

        // Take snapshot of current callbacks
        val currentCallbacks = lock.withLock {
            callbacks.toMap()
        }

        for ((name, callback) in currentCallbacks) {
            try {
                val entry = callback()
                if (entry != null) {
                    builder.addEntry(entry)
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }

        return errors
    }

    /**
     * Returns true if there are no registered callbacks.
     */
    fun isEmpty(): Boolean = callbacks.isEmpty()

    /**
     * Returns the number of registered callbacks.
     */
    fun size(): Int = callbacks.size
}
