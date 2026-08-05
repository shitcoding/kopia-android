package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.CancellationException
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.EntryType
import java.util.UUID
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
     * Non-directory entries are renamed to `.checkpointed.<name>.<uuid>`, which is Go's
     * `runCheckpoints` verbatim and is there "to prevent the use of checkpointed objects as
     * authoritative on subsequent runs": a file entry written mid-write holds only the bytes
     * flushed so far, so a resume that matched it by name would treat a truncated file as the whole
     * file. Under a name nothing looks up, the content stays *referenced* — which is the point, it
     * survives GC and the retry dedups against it — without ever being mistaken for the file
     * itself. Directories keep their name, because reading them back by name is the whole mechanism.
     *
     * Unlike Go, a failing callback does not abort the checkpoint: Go's `checkpointRoot` propagates
     * the error and cancels the entire upload. On a handset a checkpoint write can fail for
     * reasons the upload itself will survive (a momentary storage stall), and a lost checkpoint
     * costs one interval of resumability, not the backup. If the repository is genuinely gone the
     * upload's own writes fail on their own.
     *
     * @param builder The DirManifestBuilder to populate with checkpoint entries
     * @return List of errors that occurred during checkpoint
     */
    suspend fun runCheckpoints(builder: DirManifestBuilder): List<Throwable> {
        val errors = mutableListOf<Throwable>()

        // A snapshot of the callbacks, taken under the lock and then RELEASED — Go holds its mutex
        // across the callbacks, but these are suspending and a `ReentrantLock` held across a
        // suspension point is a bug in waiting (the continuation can resume on another thread).
        //
        // What that costs: a callback deregistered while this loop is running can still be invoked
        // once. For a file that means an extra `.checkpointed.` entry naming an object that turned
        // out to be the complete file; for a directory, one redundant copy of a manifest that is
        // already written. Both are extra references to content that exists, in a tree already
        // marked incomplete — never a missing reference, which is the only direction that hurts.
        val currentCallbacks = lock.withLock {
            callbacks.toMap()
        }

        for ((_, callback) in currentCallbacks) {
            try {
                val entry = callback() ?: continue
                builder.addEntry(
                    if (entry.type == EntryType.DIRECTORY) {
                        entry
                    } else {
                        entry.copy(name = ".checkpointed.${entry.name}.${UUID.randomUUID()}")
                    },
                )
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
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
