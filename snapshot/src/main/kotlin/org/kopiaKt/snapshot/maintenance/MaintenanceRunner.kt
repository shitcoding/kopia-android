package org.kopiaKt.snapshot.maintenance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.RetentionPolicy
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of a maintenance run.
 */
data class MaintenanceResult(
    /**
     * The mode that was run.
     */
    val mode: MaintenanceMode,

    /**
     * Whether maintenance completed successfully.
     */
    val success: Boolean,

    /**
     * Error message if maintenance failed.
     */
    val error: String? = null,

    /**
     * Statistics from snapshot GC (if run).
     */
    val gcStats: SnapshotGCStats? = null,

    /**
     * Number of snapshots deleted due to retention policy.
     */
    val retentionDeletedCount: Int = 0,

    /**
     * Start time of the maintenance run.
     */
    val startTime: Instant,

    /**
     * End time of the maintenance run.
     */
    val endTime: Instant
) {
    /**
     * Duration of the maintenance run.
     */
    val duration: java.time.Duration
        get() = java.time.Duration.between(startTime, endTime)
}

/**
 * Options for running maintenance.
 */
data class MaintenanceOptions(
    /**
     * The maintenance mode to run.
     * Use AUTO to determine mode from schedule.
     */
    val mode: MaintenanceMode = MaintenanceMode.AUTO,

    /**
     * Whether to force maintenance even if not scheduled.
     */
    val force: Boolean = false,

    /**
     * Safety parameters for GC operations.
     */
    val safety: SafetyParameters = SafetyParameters.Default,

    /**
     * Whether to actually delete content during GC.
     * If false, GC will be a dry run.
     *
     * Defaults to false, and an explicit `true` is currently refused (see [runMaintenance]). GC Phase 2
     * deletion IS implemented and tested at the [SnapshotGC] level, but driving it from the full
     * maintenance cycle needs on-device wiring + E2E and a refresh between retention and GC so GC sees
     * retention's manifest deletions (task-14). Until then maintenance stays non-destructive: retention
     * (snapshot-manifest deletion) plus a GC dry run. (backlog task-9 / task-14)
     */
    val gcDelete: Boolean = false,

    /**
     * Progress callback.
     */
    val onProgress: ((String) -> Unit)? = null
)

/**
 * Runs repository maintenance operations.
 *
 * Maintenance includes:
 * - Retention policy enforcement (deleting old snapshots)
 * - Garbage collection (removing unreferenced content)
 * - Index compaction (consolidating index blobs)
 *
 * Go implementation: repo/maintenance/maintenance_run.go
 */
class MaintenanceRunner(
    private val repository: DirectRepository
) {
    private val cancelled = AtomicBoolean(false)

    /**
     * Runs maintenance on the repository.
     *
     * @param options Maintenance options
     * @return Result of the maintenance run
     */
    suspend fun run(options: MaintenanceOptions = MaintenanceOptions()): MaintenanceResult {
        return withContext(Dispatchers.Default) {
            runMaintenance(options)
        }
    }

    /**
     * Cancels a running maintenance operation.
     */
    fun cancel() {
        cancelled.set(true)
    }

    private suspend fun runMaintenance(options: MaintenanceOptions): MaintenanceResult {
        cancelled.set(false)
        val startTime = Instant.now()

        try {
            // Refuse a delete-GC request up front. SnapshotGC Phase 2 deletion works, but driving it
            // through the full maintenance cycle is gated pending on-device wiring + E2E, a refresh
            // between retention and GC, AND a guarantee that no backup runs concurrently with the delete
            // run (SnapshotGC.run's concurrency contract — a concurrent dedup-reuse of old content would
            // otherwise be tombstoned; task-14 must serialize backup and delete-GC). Failing here —
            // before retention deletes any snapshot manifests — avoids partial destructive work and then
            // soft-failing. Surfaces as success=false via the catch below. Use gcDelete=false (the
            // default) for retention + a GC dry run. (task-9 / task-14)
            if (options.gcDelete) {
                throw UnsupportedOperationException(
                    "Maintenance-driven GC content deletion is gated pending on-device wiring; " +
                        "run with gcDelete=false."
                )
            }

            // Determine mode
            val mode = if (options.mode == MaintenanceMode.AUTO) {
                determineMode(options)
            } else {
                options.mode
            }

            if (mode == MaintenanceMode.NONE) {
                return MaintenanceResult(
                    mode = MaintenanceMode.NONE,
                    success = true,
                    startTime = startTime,
                    endTime = Instant.now()
                )
            }

            options.onProgress?.invoke("Starting $mode maintenance")

            // Run maintenance tasks
            val result = when (mode) {
                MaintenanceMode.QUICK -> runQuickMaintenance(options)
                MaintenanceMode.FULL -> runFullMaintenance(options)
                else -> RunResult()
            }

            return MaintenanceResult(
                mode = mode,
                success = !result.hasErrors,
                error = result.errorMessage,
                gcStats = result.gcStats,
                retentionDeletedCount = result.retentionDeletedCount,
                startTime = startTime,
                endTime = Instant.now()
            )
        } catch (e: Exception) {
            return MaintenanceResult(
                mode = options.mode,
                success = false,
                error = e.message ?: "Unknown error",
                startTime = startTime,
                endTime = Instant.now()
            )
        }
    }

    private suspend fun determineMode(options: MaintenanceOptions): MaintenanceMode {
        // In a full implementation, we would:
        // 1. Load the maintenance schedule from repository
        // 2. Check ownership
        // 3. Determine which mode should run based on schedule
        // For now, return QUICK if force is set, otherwise NONE
        return if (options.force) {
            MaintenanceMode.QUICK
        } else {
            MaintenanceMode.NONE
        }
    }

    private suspend fun runQuickMaintenance(options: MaintenanceOptions): RunResult {
        val result = RunResult()

        if (cancelled.get()) return result

        // Quick maintenance tasks:
        // 1. Index compaction (lightweight)
        options.onProgress?.invoke("Compacting indexes")
        // Index compaction would be done here

        return result
    }

    private suspend fun runFullMaintenance(options: MaintenanceOptions): RunResult {
        val result = RunResult()

        if (cancelled.get()) return result

        // Full maintenance tasks:

        // 1. Apply retention policies
        options.onProgress?.invoke("Applying retention policies")
        result.retentionDeletedCount = applyRetentionPolicies(options)

        if (cancelled.get()) return result

        // 2. Run garbage collection
        options.onProgress?.invoke("Running garbage collection")
        result.gcStats = runGarbageCollection(options)

        if (cancelled.get()) return result

        // 3. Index compaction
        options.onProgress?.invoke("Compacting indexes")
        // Index compaction would be done here

        return result
    }

    /**
     * Applies retention policies to all sources.
     *
     * @return Number of snapshots deleted
     */
    private suspend fun applyRetentionPolicies(options: MaintenanceOptions): Int {
        var totalDeleted = 0

        // Get all unique sources
        val sources = getUniqueSources()

        for (source in sources) {
            if (cancelled.get()) break

            val deleted = applyRetentionForSource(source, options)
            totalDeleted += deleted
        }

        return totalDeleted
    }

    private suspend fun getUniqueSources(): Set<SourceInfo> {
        val manifests = repository.findManifests(
            mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
        )

        val sources = mutableSetOf<SourceInfo>()
        for (metadata in manifests) {
            val host = metadata.labels[ManifestLabels.HOST] ?: continue
            val user = metadata.labels[ManifestLabels.USERNAME] ?: continue
            val path = metadata.labels[ManifestLabels.PATH] ?: continue
            sources.add(SourceInfo(host, user, path))
        }

        return sources
    }

    private suspend fun applyRetentionForSource(source: SourceInfo, options: MaintenanceOptions): Int {
        // Get all snapshots for this source
        val snapshots = getSnapshotsForSource(source)

        if (snapshots.isEmpty()) return 0

        // Load retention policy for this source
        // For now, use default policy
        val policy = RetentionPolicy.Default

        // Compute which snapshots to delete
        val toDelete = computeSnapshotsToDelete(snapshots, policy)

        if (toDelete.isEmpty()) return 0

        // Delete the snapshots
        val writer = repository.newWriter(WriteSessionOptions(purpose = "maintenance-retention"))
        try {
            for (snapshot in toDelete) {
                if (cancelled.get()) break
                deleteSnapshot(writer, snapshot)
            }
            writer.flush()
        } finally {
            writer.close()
        }

        return toDelete.size
    }

    private suspend fun getSnapshotsForSource(source: SourceInfo): List<SnapshotManifest> {
        val manifests = repository.findManifests(ManifestLabels.forSnapshot(source))

        return manifests.mapNotNull { metadata ->
            try {
                repository.getManifest(metadata.id, SnapshotManifest.serializer()).first
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun deleteSnapshot(writer: org.kopiaKt.core.repository.RepositoryWriter, snapshot: SnapshotManifest) {
        val manifestId = ManifestId(snapshot.id)
        writer.deleteManifest(manifestId)
    }

    private suspend fun runGarbageCollection(options: MaintenanceOptions): SnapshotGCStats {
        val gc = SnapshotGC(repository)
        return gc.run(
            GCOptions(
                delete = options.gcDelete,
                safety = options.safety,
                onProgress = { progress ->
                    options.onProgress?.invoke("GC: ${progress.phase}")
                }
            )
        )
    }

    private class RunResult {
        var hasErrors: Boolean = false
        var errorMessage: String? = null
        var gcStats: SnapshotGCStats? = null
        var retentionDeletedCount: Int = 0
    }
}
