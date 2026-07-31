package org.kopiaKt.android.worker

import androidx.work.Data
import androidx.work.workDataOf
import org.kopiaKt.snapshot.upload.UploadCounters

/**
 * The upload counters, in the named-map shape the task UI reads.
 *
 * A port of Go's `CountingUploadProgress.UITaskCounters`
 * (`snapshot/upload/upload_progress.go:310-342`), names included. The names ARE the contract — the
 * Tasks screen looks them up by string (`task.counters["Uploaded Bytes"]`), exactly as Kopia's own
 * web UI does — so keeping Go's spelling is what lets one UI describe either implementation.
 *
 * @param final drops the estimates, as Go does once a run has finished: an estimate sitting beside a
 *   completed total is noise at best, and reads as a shortfall at worst.
 */
fun UploadCounters.toUiTaskCounters(final: Boolean): Map<String, TaskCounterValue> {
    val cachedFiles = totalCachedFiles.toLong()
    val hashedFiles = totalHashedFiles.toLong()

    val counters = mutableMapOf(
        "Cached Files" to simpleCounter(cachedFiles),
        "Hashed Files" to simpleCounter(hashedFiles),
        "Processed Files" to simpleCounter(hashedFiles + cachedFiles),

        "Cached Bytes" to bytesCounter(totalCachedBytes),
        "Hashed Bytes" to bytesCounter(totalHashedBytes),
        "Processed Bytes" to bytesCounter(totalHashedBytes + totalCachedBytes),

        // Bytes that actually went to the server, not the deduplicated total: on a metered
        // connection this is the number that costs the user something.
        "Uploaded Bytes" to bytesCounter(totalUploadedBytes),

        "Excluded Files" to simpleCounter(totalExcludedFiles.toLong()),
        "Excluded Directories" to simpleCounter(totalExcludedDirs.toLong()),

        "Errors" to errorCounter(fatalErrorCount.toLong()),
    )

    if (!final) {
        counters["Estimated Files"] = simpleCounter(estimatedFiles)
        counters["Estimated Bytes"] = bytesCounter(estimatedBytes)
    }

    return counters
}

/** Go's `uitask.BytesCounter`: the unit is what tells the UI to render 1.2 MB rather than 1258291. */
private fun bytesCounter(value: Long) = TaskCounterValue(value = value, units = "bytes")

/** Go's `uitask.SimpleCounter`. */
private fun simpleCounter(value: Long) = TaskCounterValue(value = value, units = "")

/** Go's `uitask.ErrorCounter`: the level is what lets the UI show it as a problem, not a statistic. */
private fun errorCounter(value: Long) = TaskCounterValue(value = value, units = "", level = "error")

/**
 * The counters as WorkManager [Data], for republishing while a run is alive.
 *
 * WorkManager `Data` is a flat bundle with a hard size cap, so the counters travel as scalars rather
 * than as serialized JSON: fewer bytes, and no parse step that could fail on the receiving side and
 * take the progress display down with it. [toUploadCounters] is the inverse.
 */
fun UploadCounters.toProgressData(): Data = workDataOf(
    BackupWorker.KEY_PROGRESS_CACHED_BYTES to totalCachedBytes,
    BackupWorker.KEY_PROGRESS_HASHED_BYTES to totalHashedBytes,
    BackupWorker.KEY_PROGRESS_UPLOADED_BYTES to totalUploadedBytes,
    BackupWorker.KEY_PROGRESS_ESTIMATED_BYTES to estimatedBytes,
    BackupWorker.KEY_PROGRESS_CACHED_FILES to totalCachedFiles,
    BackupWorker.KEY_PROGRESS_HASHED_FILES to totalHashedFiles,
    BackupWorker.KEY_PROGRESS_EXCLUDED_FILES to totalExcludedFiles,
    BackupWorker.KEY_PROGRESS_EXCLUDED_DIRS to totalExcludedDirs,
    BackupWorker.KEY_PROGRESS_FATAL_ERRORS to fatalErrorCount,
    BackupWorker.KEY_PROGRESS_ESTIMATED_FILES to estimatedFiles,
    BackupWorker.KEY_PROGRESS_CURRENT_DIR to currentDirectory,
)

/**
 * Reads back what [toProgressData] wrote, or null if this [Data] carries no counters at all.
 *
 * Null rather than a zeroed [UploadCounters]: WorkManager hands out empty progress before the first
 * publish and again once the work finishes, and reporting all-zeros then would make a finished
 * backup look like one that did nothing.
 */
fun Data.toUploadCounters(): UploadCounters? {
    if (!keyValueMap.containsKey(BackupWorker.KEY_PROGRESS_HASHED_BYTES)) return null
    return UploadCounters(
        totalCachedBytes = getLong(BackupWorker.KEY_PROGRESS_CACHED_BYTES, 0),
        totalHashedBytes = getLong(BackupWorker.KEY_PROGRESS_HASHED_BYTES, 0),
        totalUploadedBytes = getLong(BackupWorker.KEY_PROGRESS_UPLOADED_BYTES, 0),
        estimatedBytes = getLong(BackupWorker.KEY_PROGRESS_ESTIMATED_BYTES, 0),
        totalCachedFiles = getInt(BackupWorker.KEY_PROGRESS_CACHED_FILES, 0),
        totalHashedFiles = getInt(BackupWorker.KEY_PROGRESS_HASHED_FILES, 0),
        totalExcludedFiles = getInt(BackupWorker.KEY_PROGRESS_EXCLUDED_FILES, 0),
        totalExcludedDirs = getInt(BackupWorker.KEY_PROGRESS_EXCLUDED_DIRS, 0),
        fatalErrorCount = getInt(BackupWorker.KEY_PROGRESS_FATAL_ERRORS, 0),
        estimatedFiles = getLong(BackupWorker.KEY_PROGRESS_ESTIMATED_FILES, 0),
        currentDirectory = getString(BackupWorker.KEY_PROGRESS_CURRENT_DIR) ?: "",
    )
}
