package org.kopiaKt.app.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.kopiaKt.android.restore.SafRestoreOutput
import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.FileEntryType
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.model.RestoreState
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SnapshotStats
import org.kopiaKt.app.domain.model.SnapshotWithRetention
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.model.SourceWithStats
import org.kopiaKt.app.domain.repository.RestoreOptions
import org.kopiaKt.app.domain.repository.SnapshotRepository
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.manifest.ManifestNotFoundException
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.maintenance.computeRetention
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.policy.RetentionPolicy
import org.kopiaKt.snapshot.restore.RestoreStats
import org.kopiaKt.snapshot.restore.SnapshotRestorer
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import org.kopiaKt.snapshot.restore.RestoreOptions as CoreRestoreOptions

@Singleton
class SnapshotRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repositoryManager: KopiaRepositoryManagerImpl,
) : SnapshotRepository {

    private var currentRestorer: SnapshotRestorer? = null

    @Volatile
    private var restoreCancelled = false

    override suspend fun listSources(): List<SourceInfo> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: error("Not connected to repository")

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
            ?: error("Not connected to repository")

        repo.requireCompleteManifestView()

        val labels = mutableMapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
        source?.let {
            labels[ManifestLabels.HOST] = it.host
            labels[ManifestLabels.USERNAME] = it.userName
            labels[ManifestLabels.PATH] = it.path
        }

        val manifests = repo.findManifests(labels)

        manifests.mapNotNull { metadata ->
            repo.loadSnapshot(metadata.id)?.toSnapshotInfo(metadata.id.value)
        }
    }

    override suspend fun getSnapshot(snapshotId: String): SnapshotInfo? = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: error("Not connected to repository")

        try {
            val manifestId = ManifestId.invoke(snapshotId)
            val (manifest, _) = repo.getManifest(
                manifestId,
                SnapshotManifest.serializer(),
            )
            manifest.toSnapshotInfo(snapshotId)
        } catch (@Suppress("SwallowedException") e: ManifestNotFoundException) {
            // Genuinely absent -- retention deleted it between the list and the tap, say. Null is
            // the honest answer, and the caller renders "not found".
            //
            // Everything else PROPAGATES. Answering null for a failed read tells the caller "this
            // snapshot is fine", the bridge wraps that as a success, and react-query caches it as
            // fresh data -- no retry, no error state. The incomplete-snapshot warnings key on this
            // object, so a transient repository failure would quietly leave them unrendered while
            // browsing and restoring carried on regardless.
            null
        }
    }

    override suspend fun browseDirectory(snapshotId: String, path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: error("Not connected to repository")

        val manifestId = ManifestId.invoke(snapshotId)
        val (manifest, _) = repo.getManifest(
            manifestId,
            SnapshotManifest.serializer(),
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
        options: RestoreOptions,
    ): Flow<RestoreProgress> = callbackFlow {
        restoreCancelled = false

        send(
            RestoreProgress(
                state = RestoreState.PREPARING,
                totalFiles = 0,
                restoredFiles = 0,
                totalBytes = 0,
                restoredBytes = 0,
                currentFile = null,
                errorMessage = null,
            ),
        )

        try {
            val repo = repositoryManager.getRepository()
                ?: error("Not connected to repository")

            // Load snapshot manifest
            val manifestId = ManifestId.invoke(snapshotId)
            val (manifest, _) = repo.getManifest(
                manifestId,
                SnapshotManifest.serializer(),
            )

            // Navigate to source path within snapshot
            var currentEntry: Entry = snapshotRoot(repo, manifest)
            if (sourcePath.isNotEmpty() && sourcePath != "/") {
                val pathParts = sourcePath.trim('/').split('/')
                for (part in pathParts) {
                    if (part.isEmpty()) continue
                    val dir = currentEntry as? Directory
                        ?: throw IllegalArgumentException("$part is not a directory")
                    currentEntry = dir.child(part)
                        ?: throw IllegalArgumentException("$part not found")
                }
            }

            // Create SAF output for destination
            val destUri = Uri.parse(destinationUri)
            val safOutput = SafRestoreOutput(context, destUri)

            // Create progress tracker
            val progressTracker = object : org.kopiaKt.snapshot.restore.RestoreProgress {
                private var totalFiles = 0L
                private var restoredFiles = 0L
                private var totalBytes = 0L
                private var restoredBytes = 0L
                private var currentFile: String? = null

                override fun directoryEnqueued() {}
                override fun directoryRestored() {}
                override fun directoryDeleted() {}

                override fun fileEnqueued(size: Long) {
                    totalFiles++
                    totalBytes += size
                    trySend(makeProgress(RestoreState.IN_PROGRESS))
                }

                override fun fileProgress(bytesWritten: Long) {
                    restoredBytes += bytesWritten
                    trySend(makeProgress(RestoreState.IN_PROGRESS))
                }

                override fun fileRestored() {
                    restoredFiles++
                    trySend(makeProgress(RestoreState.IN_PROGRESS))
                }

                override fun fileSkipped(size: Long) {
                    restoredFiles++
                    restoredBytes += size
                }

                override fun fileDeleted() {}

                override fun symlinkEnqueued() {
                    totalFiles++
                }

                override fun symlinkRestored() {
                    restoredFiles++
                }

                override fun symlinkDeleted() {}

                override fun errorIgnored() {}

                override fun snapshot(): RestoreStats = RestoreStats(
                    restoredTotalFileSize = restoredBytes,
                    restoredFileCount = restoredFiles.toInt(),
                    skippedTotalFileSize = 0,
                    skippedCount = 0,
                    enqueuedTotalFileSize = totalBytes,
                    enqueuedFileCount = totalFiles.toInt(),
                )

                private fun makeProgress(state: RestoreState) = RestoreProgress(
                    state = state,
                    totalFiles = totalFiles,
                    restoredFiles = restoredFiles,
                    totalBytes = totalBytes,
                    restoredBytes = restoredBytes,
                    currentFile = currentFile,
                    errorMessage = null,
                )
            }

            // Create restorer and run restore
            val coreOptions = CoreRestoreOptions(
                parallel = 1, // SAF is not thread-safe
                incremental = options.incremental,
                deleteExtra = false,
                ignoreErrors = false,
            )

            val restorer = SnapshotRestorer(safOutput, coreOptions, progressTracker)
            currentRestorer = restorer

            send(
                RestoreProgress(
                    state = RestoreState.IN_PROGRESS,
                    totalFiles = 0,
                    restoredFiles = 0,
                    totalBytes = 0,
                    restoredBytes = 0,
                    currentFile = null,
                    errorMessage = null,
                ),
            )

            // Run restore
            withContext(Dispatchers.IO) {
                val stats = restorer.restore(currentEntry)

                send(
                    RestoreProgress(
                        // A cooperatively cancelled restore returns normally with partial stats;
                        // reporting COMPLETED would claim a full restore that never happened.
                        state = if (restorer.isCancelled) RestoreState.CANCELLED else RestoreState.COMPLETED,
                        totalFiles = stats.enqueuedFileCount.toLong(),
                        restoredFiles = stats.restoredFileCount.toLong(),
                        totalBytes = stats.enqueuedTotalFileSize,
                        restoredBytes = stats.restoredTotalFileSize,
                        currentFile = null,
                        errorMessage = null,
                    ),
                )
            }
            // Close the channel to signal completion
            close()
        } catch (e: CancellationException) {
            // A cancelled restore is not a failed restore, and the channel is already gone —
            // emitting FAILED here would both mislead the UI and throw from send().
            throw e
        } catch (e: Exception) {
            android.util.Log.e("SnapshotRepo", "Restore failed", e)
            send(
                RestoreProgress(
                    state = RestoreState.FAILED,
                    totalFiles = 0,
                    restoredFiles = 0,
                    totalBytes = 0,
                    restoredBytes = 0,
                    currentFile = null,
                    errorMessage = e.message ?: "Unknown error",
                ),
            )
            // Close the channel to signal failure completion
            close()
        } finally {
            currentRestorer = null
        }

        awaitClose { restoreCancelled = true }
    }.flowOn(Dispatchers.IO)

    override fun cancelRestore() {
        restoreCancelled = true
        currentRestorer?.cancel()
    }

    override suspend fun listSourcesWithStats(): List<SourceWithStats> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: error("Not connected to repository")

        repo.requireCompleteManifestView()

        // Need to load full manifests to get storageStats
        val labels = mutableMapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
        val metadataList = repo.findManifests(labels)

        val manifests = metadataList.mapNotNull { repo.loadSnapshot(it.id) }

        manifests
            .groupBy { SourceInfo(it.source.host, it.source.userName, it.source.path) }
            .map { (source, snapshots) ->
                val complete = snapshots.filter { it.incompleteReason == null }
                val latestComplete = complete.maxByOrNull { it.startTime }
                // A source with nothing complete in it is still a source, and the screen it feeds
                // is the only way to reach what the unfinished run did store.
                val latest = latestComplete ?: snapshots.maxByOrNull { it.startTime }!!

                SourceWithStats(
                    source = source,
                    snapshotCount = complete.size,
                    latestSnapshotTime = latest.startTime,
                    totalFileCount = latestComplete?.displayFileCount() ?: 0,
                    // Use deduplicated storage size (matches Kopia GUI)
                    totalFileSize = latestComplete?.let {
                        it.storageStats?.runningTotal?.objectBytes ?: it.stats?.totalFileSize
                    } ?: 0,
                )
            }
            .sortedByDescending { it.latestSnapshotTime }
    }

    override suspend fun listSnapshotsWithRetention(source: SourceInfo): List<SnapshotWithRetention> = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: error("Not connected to repository")

        repo.requireCompleteManifestView()

        val labels = mutableMapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
        labels[ManifestLabels.HOST] = source.host
        labels[ManifestLabels.USERNAME] = source.userName
        labels[ManifestLabels.PATH] = source.path

        val metadataList = repo.findManifests(labels)

        // Load full manifests paired with their manifest IDs
        val manifestsWithIds = metadataList.mapNotNull { metadata ->
            repo.loadSnapshot(metadata.id)?.let { metadata.id.value to it }
        }

        // Compute retention using default policy
        val policy = RetentionPolicy.Default
        val retentionResults = computeRetention(
            snapshots = manifestsWithIds.map { it.second },
            policy = policy,
            now = Instant.now(),
        )

        // Build retention map: match by startTime since RetentionResult.snapshot
        // is the original SnapshotManifest (computeRetention sorts internally)
        val retentionByStartTime = mutableMapOf<Instant, List<String>>()
        for (result in retentionResults) {
            retentionByStartTime[result.snapshot.startTime] = result.retentionReasons
        }

        // Map to domain models
        manifestsWithIds.map { (manifestId, manifest) ->
            val snapshotInfo = manifest.toSnapshotInfo(manifestId)
            SnapshotWithRetention(
                snapshot = snapshotInfo,
                retentionReasons = retentionByStartTime[manifest.startTime] ?: emptyList(),
            )
        }.sortedByDescending { it.snapshot.startTime }
    }

    override suspend fun deleteSnapshots(snapshotIds: List<String>) = withContext(Dispatchers.IO) {
        val repo = repositoryManager.getRepository()
            ?: error("Not connected to repository")

        val writer = repo.newDirectWriter()
        try {
            for (id in snapshotIds) {
                writer.deleteManifest(ManifestId(id))
            }
            writer.flush()
        } finally {
            writer.close()
        }
    }

    /**
     * Refuses to answer from a manifest view the repository itself calls partial.
     *
     * [loadSnapshot] only covers manifests that reach `findManifests`. The likelier loss happens
     * one level up: `ManifestManager` skips a whole manifest content BLOCK it cannot parse — and a
     * block holds many manifests — so those snapshots never appear in the list at all and there is
     * no read left to fail. `lastLoadWasComplete()` is how the repository says that happened (the
     * same signal snapshot GC refuses to delete on, task-9); a count taken over that view is a
     * number the user would count differently.
     */
    private fun DirectRepository.requireCompleteManifestView() = check(lastLoadWasComplete()) {
        "Repository could not be fully read: some index or manifest data was unreadable, so snapshots may be missing"
    }

    /**
     * The snapshot manifest [id] names, or null if it is genuinely gone.
     *
     * Only absence answers null. A manifest that cannot be DECODED propagates, because every list
     * built on this prints its own length as "N snapshots": swallowing one read reports 99
     * snapshots for a source that has 100, with nothing anywhere saying a manifest could not be
     * read. In a backup tool that is the wrong direction to fail, and both callers can say so — the
     * snapshots screen shows its error state, and the dashboard drops the counts while keeping the
     * source list (`snapshotStatsBySource`).
     *
     * A decode failure is the only failure this call has: the payload is already in memory by the
     * time it is asked for (`ManifestManager.getEntryContent` reads the committed/pending maps, no
     * storage). Losing the bytes at STORAGE level is the case [requireCompleteManifestView] covers.
     *
     * Retention deletes manifests after every backup, so losing one between `findManifests` and
     * this read is an ordinary race and the one case where leaving it out is the truth. Same rule
     * as [getSnapshot].
     */
    private suspend fun DirectRepository.loadSnapshot(id: ManifestId): SnapshotManifest? = try {
        getManifest(id, SnapshotManifest.serializer()).first
    } catch (@Suppress("SwallowedException") e: ManifestNotFoundException) {
        null
    }

    private fun SnapshotManifest.toSnapshotInfo(manifestId: String): SnapshotInfo = SnapshotInfo(
        id = manifestId,
        source = SourceInfo(source.host, source.userName, source.path),
        startTime = startTime,
        endTime = endTime,
        description = description,
        stats = stats?.let {
            SnapshotStats(
                totalFileSize = it.totalFileSize,
                totalFileCount = displayFileCount(),
                totalDirectoryCount = displayDirectoryCount(),
            )
        },
        isIncomplete = incompleteReason != null,
        tags = tags,
    )

    private fun org.kopiaKt.snapshot.fs.Entry.toFileEntry(): FileEntry = FileEntry(
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
        objectId = null,
    )
}

/**
 * Number of files in the snapshot, as a user means it.
 *
 * The root directory summary holds the RECURSIVE count, which is what the Kopia GUI shows. Go's
 * `stats.fileCount` counts files HASHED during that run, so on an incremental snapshot — where most
 * files are served from cache — it reports a handful of changed files: a tree of 11 files displayed
 * as "1 files". The summary is absent only on snapshots written without one, hence the fallback.
 */
internal fun SnapshotManifest.displayFileCount(): Int {
    val summary = rootEntry?.dirSummary
    return summary?.totalFileCount?.toInt() ?: stats?.totalFileCount ?: 0
}

/** Directory count in the snapshot; see [displayFileCount] for why the summary is preferred. */
internal fun SnapshotManifest.displayDirectoryCount(): Int {
    val summary = rootEntry?.dirSummary
    return summary?.totalDirCount?.toInt() ?: stats?.totalDirectoryCount ?: 0
}
