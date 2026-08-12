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
)

data class SnapshotWithRetention(
    val snapshot: SnapshotInfo,
    val retentionReasons: List<String>,
)
