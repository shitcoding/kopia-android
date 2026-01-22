package org.kopiaKt.app.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.FileEntryType
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.RestoreState
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SnapshotStats
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.repository.RestoreOptions
import org.kopiaKt.app.domain.repository.SnapshotRepository
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapshotRepositoryImpl @Inject constructor(
    private val repositoryManager: KopiaRepositoryManagerImpl
) : SnapshotRepository {

    @Volatile
    private var restoreCancelled = false

    override suspend fun listSources(): List<SourceInfo> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: throw IllegalStateException("Not connected to repository")

        val manifests = repo.findManifests(mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT))

        manifests
            .mapNotNull { metadata ->
                val host = metadata.labels[ManifestLabels.HOST] ?: return@mapNotNull null
                val userName = metadata.labels[ManifestLabels.USERNAME] ?: return@mapNotNull null
                val path = metadata.labels[ManifestLabels.PATH] ?: return@mapNotNull null
                SourceInfo(host, userName, path)
            }
            .distinct()
    }

    override suspend fun listSnapshots(source: SourceInfo?): List<SnapshotInfo> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: throw IllegalStateException("Not connected to repository")

        val labels = mutableMapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
        source?.let {
            labels[ManifestLabels.HOST] = it.host
            labels[ManifestLabels.USERNAME] = it.userName
            labels[ManifestLabels.PATH] = it.path
        }

        val manifests = repo.findManifests(labels)

        manifests.mapNotNull { metadata ->
            try {
                val (manifest, _) = repo.getManifest(
                    metadata.id,
                    SnapshotManifest.serializer()
                )
                manifest.toSnapshotInfo()
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun getSnapshot(snapshotId: String): SnapshotInfo? = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: throw IllegalStateException("Not connected to repository")

        try {
            val manifestId = ManifestId.invoke(snapshotId)
            val (manifest, _) = repo.getManifest(
                manifestId,
                SnapshotManifest.serializer()
            )
            manifest.toSnapshotInfo()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun browseDirectory(snapshotId: String, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: throw IllegalStateException("Not connected to repository")

        val manifestId = ManifestId.invoke(snapshotId)
        val (manifest, _) = repo.getManifest(
            manifestId,
            SnapshotManifest.serializer()
        )

        var currentEntry = snapshotRoot(repo, manifest)

        if (path.isNotEmpty()) {
            val pathParts = path.trim('/').split('/')
            for (part in pathParts) {
                if (part.isEmpty()) continue
                val dir = currentEntry as? Directory
                    ?: throw IllegalArgumentException("$part is not a directory")
                currentEntry = dir.child(part)
                    ?: throw IllegalArgumentException("$part not found")
            }
        }

        val dir = currentEntry as? Directory
            ?: throw IllegalArgumentException("Path is not a directory")

        val entries = mutableListOf<FileEntry>()
        val iterator = dir.iterate()
        try {
            var entry = iterator.next()
            while (entry != null) {
                entries.add(entry.toFileEntry())
                entry = iterator.next()
            }
        } finally {
            iterator.close()
        }

        entries
    }

    override fun restore(
        snapshotId: String,
        sourcePath: String,
        destinationUri: String,
        options: RestoreOptions
    ): Flow<RestoreProgress> = callbackFlow {
        restoreCancelled = false

        send(RestoreProgress(
            state = RestoreState.PREPARING,
            totalFiles = 0,
            restoredFiles = 0,
            totalBytes = 0,
            restoredBytes = 0,
            currentFile = null,
            errorMessage = null
        ))

        try {
            // TODO: Implement actual restore using SnapshotRestorer
            // This is a placeholder that needs to be connected to the existing restore infrastructure

            send(RestoreProgress(
                state = RestoreState.COMPLETED,
                totalFiles = 0,
                restoredFiles = 0,
                totalBytes = 0,
                restoredBytes = 0,
                currentFile = null,
                errorMessage = null
            ))
        } catch (e: Exception) {
            send(RestoreProgress(
                state = RestoreState.FAILED,
                totalFiles = 0,
                restoredFiles = 0,
                totalBytes = 0,
                restoredBytes = 0,
                currentFile = null,
                errorMessage = e.message
            ))
        }

        awaitClose { restoreCancelled = true }
    }

    override fun cancelRestore() {
        restoreCancelled = true
    }

    private fun SnapshotManifest.toSnapshotInfo(): SnapshotInfo {
        return SnapshotInfo(
            id = id,
            source = SourceInfo(source.host, source.userName, source.path),
            startTime = startTime,
            endTime = endTime,
            description = description,
            stats = stats?.let {
                SnapshotStats(
                    totalFileSize = it.totalFileSize,
                    totalFileCount = it.totalFileCount,
                    totalDirectoryCount = it.totalDirectoryCount
                )
            },
            isIncomplete = incompleteReason != null,
            tags = tags
        )
    }

    private fun org.kopiaKt.snapshot.fs.Entry.toFileEntry(): FileEntry {
        return FileEntry(
            name = name,
            type = when (type) {
                org.kopiaKt.snapshot.fs.EntryType.FILE -> FileEntryType.FILE
                org.kopiaKt.snapshot.fs.EntryType.DIRECTORY -> FileEntryType.DIRECTORY
                org.kopiaKt.snapshot.fs.EntryType.SYMLINK -> FileEntryType.SYMLINK
                else -> FileEntryType.UNKNOWN
            },
            size = size,
            modTime = modTime,
            permissions = mode,
            objectId = null
        )
    }
}
