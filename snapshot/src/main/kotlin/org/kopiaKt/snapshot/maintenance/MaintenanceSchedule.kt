package org.kopiaKt.snapshot.maintenance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kopiaKt.snapshot.model.InstantSerializer
import java.time.Duration
import java.time.Instant

/**
 * Maintenance task types.
 *
 * Go type: maintenance.TaskType
 */
enum class TaskType(val id: String) {
    SNAPSHOT_GC("snapshot-gc"),
    DELETE_ORPHANED_BLOBS_QUICK("quick-delete-blobs"),
    DELETE_ORPHANED_BLOBS_FULL("full-delete-blobs"),
    REWRITE_CONTENTS_QUICK("quick-rewrite-contents"),
    REWRITE_CONTENTS_FULL("full-rewrite-contents"),
    DROP_DELETED_CONTENTS_FULL("full-drop-deleted-content"),
    INDEX_COMPACTION("index-compaction"),
    EXTEND_BLOB_RETENTION_TIME("extend-blob-retention-time"),
    CLEANUP_LOGS("cleanup-logs"),
    EPOCH_ADVANCE("advance-epoch"),
    EPOCH_DELETE_SUPERSEDED_INDEXES("delete-superseded-epoch-indexes"),
    EPOCH_CLEANUP_MARKERS("cleanup-epoch-markers"),
    EPOCH_GENERATE_RANGE("generate-epoch-range-index"),
    EPOCH_COMPACT_SINGLE("compact-single-epoch"),
    ;

    companion object {
        fun fromId(id: String): TaskType? = entries.find { it.id == id }
    }
}

/**
 * Maintenance mode.
 *
 * Go type: maintenance.Mode
 */
enum class MaintenanceMode {
    /** No maintenance. */
    NONE,

    /** Quick maintenance (frequent, lightweight). */
    QUICK,

    /** Full maintenance (less frequent, comprehensive). */
    FULL,

    /** Automatically determine mode based on schedule. */
    AUTO,
}

/**
 * Information about a single maintenance run.
 *
 * Go type: maintenance.RunInfo
 */
@Serializable
data class RunInfo(
    @Serializable(with = InstantSerializer::class)
    val start: Instant,
    @Serializable(with = InstantSerializer::class)
    val end: Instant,
    val success: Boolean,
    val error: String? = null,
    val extra: List<TaskStats> = emptyList(),
)

/**
 * Task-specific statistics.
 */
@Serializable
data class TaskStats(
    val name: String,
    val value: Long,
)

/**
 * Cycle-specific parameters.
 *
 * Go type: maintenance.CycleParams
 */
@Serializable
data class CycleParams(
    val enabled: Boolean = true,
    @Serializable(with = JavaDurationSerializer::class)
    val interval: Duration = Duration.ZERO,
) {
    companion object {
        /**
         * Default quick maintenance cycle (every hour).
         */
        val QuickDefault = CycleParams(
            enabled = true,
            interval = Duration.ofHours(1),
        )

        /**
         * Default full maintenance cycle (every 24 hours).
         */
        val FullDefault = CycleParams(
            enabled = true,
            interval = Duration.ofHours(24),
        )
    }
}

/**
 * Log retention options.
 *
 * Go type: maintenance.LogRetentionOptions
 */
@Serializable
data class LogRetentionOptions(
    @Serializable(with = JavaDurationSerializer::class)
    val maxAge: Duration = Duration.ofDays(30),
    val maxTotalSize: Long = 100 * 1024 * 1024, // 100MB
)

/**
 * Maintenance parameters.
 *
 * Go type: maintenance.Params
 */
@Serializable
data class MaintenanceParams(
    /**
     * Username@hostname of the maintenance owner.
     * Only this user can run maintenance.
     */
    val owner: String = "",

    /**
     * Quick maintenance cycle parameters.
     */
    val quickCycle: CycleParams = CycleParams.QuickDefault,

    /**
     * Full maintenance cycle parameters.
     */
    val fullCycle: CycleParams = CycleParams.FullDefault,

    /**
     * Log retention options.
     */
    val logRetention: LogRetentionOptions = LogRetentionOptions(),

    /**
     * Whether to extend object lock retention times.
     */
    val extendObjectLocks: Boolean = false,

    /**
     * Parallelism for blob listing operations.
     */
    val listParallelism: Int = 1,
)

/**
 * Maintenance schedule tracking.
 *
 * Stores the schedule for next maintenance runs and history of past runs.
 * This is persisted as an encrypted blob in the repository.
 *
 * Go type: maintenance.Schedule
 */
@Serializable
data class MaintenanceSchedule(
    /**
     * Next scheduled full maintenance time.
     */
    @Serializable(with = InstantSerializer::class)
    @SerialName("nextFullMaintenance")
    val nextFullMaintenanceTime: Instant? = null,

    /**
     * Next scheduled quick maintenance time.
     */
    @Serializable(with = InstantSerializer::class)
    @SerialName("nextQuickMaintenance")
    val nextQuickMaintenanceTime: Instant? = null,

    /**
     * History of maintenance runs by task type.
     */
    val runs: Map<String, List<RunInfo>> = emptyMap(),
) {
    /**
     * Gets the runs for a specific task type.
     */
    fun runsFor(taskType: TaskType): List<RunInfo> = runs[taskType.id] ?: emptyList()

    /**
     * Gets the most recent successful run for a task type.
     */
    fun lastSuccessfulRun(taskType: TaskType): RunInfo? = runsFor(taskType).filter { it.success }.maxByOrNull { it.end }

    /**
     * Gets the most recent run (successful or not) for a task type.
     */
    fun lastRun(taskType: TaskType): RunInfo? = runsFor(taskType).maxByOrNull { it.end }

    /**
     * Returns a new schedule with the run info added.
     */
    fun withRun(taskType: TaskType, runInfo: RunInfo, maxHistory: Int = 10): MaintenanceSchedule {
        val existingRuns = runs[taskType.id] ?: emptyList()
        val newRuns = (listOf(runInfo) + existingRuns).take(maxHistory)
        return copy(runs = runs + (taskType.id to newRuns))
    }

    /**
     * Returns a new schedule with updated next maintenance times.
     */
    fun withNextTimes(
        nextFull: Instant? = nextFullMaintenanceTime,
        nextQuick: Instant? = nextQuickMaintenanceTime,
    ): MaintenanceSchedule = copy(
        nextFullMaintenanceTime = nextFull,
        nextQuickMaintenanceTime = nextQuick,
    )

    /**
     * Determines the maintenance mode to run based on schedule.
     *
     * @param now Current time
     * @param params Maintenance parameters
     * @return The mode to run, or NONE if no maintenance is due
     */
    fun determineMode(now: Instant, params: MaintenanceParams): MaintenanceMode {
        // Check if full maintenance is due
        if (params.fullCycle.enabled) {
            val nextFull = nextFullMaintenanceTime
            if (nextFull == null || now >= nextFull) {
                return MaintenanceMode.FULL
            }
        }

        // Check if quick maintenance is due
        if (params.quickCycle.enabled) {
            val nextQuick = nextQuickMaintenanceTime
            if (nextQuick == null || now >= nextQuick) {
                return MaintenanceMode.QUICK
            }
        }

        return MaintenanceMode.NONE
    }

    /**
     * Calculates the safe time before which deleted content can be dropped.
     *
     * This requires two successful GC cycles with sufficient margin between them.
     *
     * @param safety Safety parameters
     * @return The safe drop time, or null if not yet safe to drop
     */
    fun findSafeDropTime(safety: SafetyParameters): Instant? {
        if (!safety.requireTwoGCCycles) {
            // If not requiring two cycles, return the last GC time minus margin
            val lastGC = lastSuccessfulRun(TaskType.SNAPSHOT_GC)
            return lastGC?.end?.minus(safety.marginBetweenSnapshotGC)
        }

        val gcRuns = runsFor(TaskType.SNAPSHOT_GC)
            .filter { it.success }
            .sortedByDescending { it.end }

        if (gcRuns.size < 2) {
            return null // Need at least 2 successful GC runs
        }

        // Find two GC runs with sufficient margin between them
        for (i in 0 until gcRuns.size - 1) {
            val recent = gcRuns[i]
            val earlier = gcRuns[i + 1]

            val gap = Duration.between(earlier.end, recent.start)
            if (gap >= safety.marginBetweenSnapshotGC) {
                // Found valid pair, return the earlier GC's end time
                return earlier.end
            }
        }

        return null // No valid pair found
    }

    companion object {
        /**
         * Empty schedule.
         */
        val Empty = MaintenanceSchedule()

        /**
         * Manifest label for maintenance schedule.
         */
        const val MANIFEST_LABEL_TYPE = "maintenance"
    }
}
