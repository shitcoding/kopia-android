package org.kopiaKt.app.domain.model

import java.time.Instant

data class SnapshotInfo(
    val id: String,
    val source: SourceInfo,
    val startTime: Instant,
    val endTime: Instant?,
    val description: String,
    val stats: SnapshotStats?,
    val isIncomplete: Boolean,
    /**
     * Entries the run could not read, recorded in the manifest as `numFailed` (task-63).
     *
     * Distinct from [isIncomplete]: a snapshot with failures is *complete* -- the run finished and
     * saved what it could read -- so every warning that keys on [isIncomplete] stays silent for it.
     * That is how a snapshot holding 945 of 2004 files came to sit at the top of the list, tagged
     * `latest`, looking exactly like a healthy one.
     */
    val failedEntryCount: Int,
    val tags: Map<String, String>,
)

data class SourceInfo(
    val host: String,
    val userName: String,
    val path: String,
) {
    override fun toString(): String = "$userName@$host:$path"
}

data class SnapshotStats(
    val totalFileSize: Long,
    val totalFileCount: Int,
    val totalDirectoryCount: Int,
)

/**
 * @param snapshotCount COMPLETE snapshots only — what Go's `kopia snapshot list` shows without
 *   `--incomplete`. A cancelled run leaves a manifest behind and retention deliberately keeps up to
 *   three of them, so counting those would make cancelling a backup look like taking one. The
 *   per-source list marks incomplete manifests separately, and counts the same way.
 * @param totalFileCount from the newest COMPLETE snapshot, as are [totalFileSize] and
 *   [latestSnapshotTime] — a checkpoint's numbers describe however much of the run had been
 *   uploaded when it stopped, which is not the size of the source. Both are zero when nothing is
 *   complete; [latestSnapshotTime] then falls back to the newest manifest of any kind, so a source
 *   whose only run was cancelled still sorts and dates sensibly.
 */
data class SourceWithStats(
    val source: SourceInfo,
    val snapshotCount: Int,
    val latestSnapshotTime: Instant,
    val totalFileCount: Int,
    val totalFileSize: Long,
    /**
     * Entries the newest COMPLETE snapshot could not read (task-63), so the dashboard can say that
     * the numbers beside it describe a backup that lost something.
     *
     * From the same snapshot as [totalFileCount] and [totalFileSize], deliberately — NOT from the
     * newest *clean* one. Re-picking would make the headline describe a snapshot that is not what
     * "restore latest" returns, which hides the problem in the other direction and breaks the
     * one-rule invariant these three numbers share.
     */
    val latestFailedEntryCount: Int,
)

data class SnapshotWithRetention(
    val snapshot: SnapshotInfo,
    val retentionReasons: List<String>,
)
