package org.kopiaKt.app.domain.repository

import kotlinx.coroutines.flow.Flow
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SnapshotWithRetention
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.model.SourceWithStats

interface SnapshotRepository {
    suspend fun listSources(): List<SourceInfo>

    suspend fun listSnapshots(source: SourceInfo? = null): List<SnapshotInfo>

    suspend fun getSnapshot(snapshotId: String): SnapshotInfo?

    suspend fun browseDirectory(snapshotId: String, path: String): List<FileEntry>

    fun restore(
        snapshotId: String,
        sourcePath: String,
        destinationUri: String,
        options: RestoreOptions = RestoreOptions(),
    ): Flow<RestoreProgress>

    fun cancelRestore()

    suspend fun listSourcesWithStats(): List<SourceWithStats>

    suspend fun listSnapshotsWithRetention(source: SourceInfo): List<SnapshotWithRetention>

    suspend fun deleteSnapshots(snapshotIds: List<String>)
}

data class RestoreOptions(
    val parallel: Int = 0,
    val incremental: Boolean = false,
    val overwriteExisting: Boolean = true,
)
