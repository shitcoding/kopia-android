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
