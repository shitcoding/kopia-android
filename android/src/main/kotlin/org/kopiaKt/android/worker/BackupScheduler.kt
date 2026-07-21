package org.kopiaKt.android.worker

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Port interface for scheduling work.
 *
 * Abstracts WorkManager calls to allow pure-JVM unit testing of [BackupScheduler]
 * without Robolectric or Android framework dependencies.
 */
interface WorkSchedulingPort {

    /**
     * Schedules a one-time backup for the given source.
     */
    fun scheduleOneTime(
        sourceId: String,
        sourcePath: String,
        constraints: BackupConstraints = BackupConstraints(),
    )

    /**
     * Schedules periodic backups for the given source.
     */
    fun schedulePeriodic(
        sourceId: String,
        sourcePath: String,
        intervalHours: Long,
        constraints: BackupConstraints = BackupConstraints(),
    )

    /**
     * Cancels all scheduled backups (one-time and periodic) for the given source.
     */
    fun cancel(sourceId: String)
}

/**
 * Higher-level scheduler that coordinates backup scheduling with source state management.
 *
 * Wraps [WorkSchedulingPort] for actual WorkManager interaction and [BackupSourceManager]
 * for source state (IDLE, PAUSED, UPLOADING). Enforces business rules such as minimum
 * intervals and source existence checks.
 *
 * @param schedulingPort Abstraction over WorkManager scheduling calls
 * @param sourceManager Manages backup source state and metadata
 */
class BackupScheduler(
    private val schedulingPort: WorkSchedulingPort,
    private val sourceManager: BackupSourceManager,
) {

    private val activeSchedules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Schedules periodic backups for a source.
     *
     * @param sourceId ID of the source (must exist in [sourceManager])
     * @param intervalHours Interval between backups in hours (minimum 1)
     * @param constraints Execution constraints (WiFi, charging, battery, etc.)
     * @throws IllegalArgumentException if the source does not exist
     */
    fun schedulePeriodicBackup(
        sourceId: String,
        intervalHours: Long,
        constraints: BackupConstraints = BackupConstraints(),
    ) {
        val source = sourceManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source not found: $sourceId")

        val effectiveInterval = intervalHours.coerceAtLeast(1)

        schedulingPort.schedulePeriodic(
            sourceId = sourceId,
            sourcePath = source.path,
            intervalHours = effectiveInterval,
            constraints = constraints,
        )
        activeSchedules.add(sourceId)
    }

    /**
     * Schedules a one-time backup for a source.
     *
     * @param sourceId ID of the source (must exist in [sourceManager])
     * @param constraints Execution constraints
     * @throws IllegalArgumentException if the source does not exist
     */
    fun scheduleOneTimeBackup(
        sourceId: String,
        constraints: BackupConstraints = BackupConstraints(),
    ) {
        val source = sourceManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source not found: $sourceId")

        schedulingPort.scheduleOneTime(
            sourceId = sourceId,
            sourcePath = source.path,
            constraints = constraints,
        )
        activeSchedules.add(sourceId)
    }

    /**
     * Cancels all scheduled backups for a source.
     */
    fun cancelScheduledBackup(sourceId: String) {
        schedulingPort.cancel(sourceId)
        activeSchedules.remove(sourceId)
    }

    /**
     * Pauses a source: cancels its scheduled work and sets its status to PAUSED.
     *
     * @param sourceId ID of the source
     */
    fun pauseSource(sourceId: String) {
        schedulingPort.cancel(sourceId)
        sourceManager.pauseSource(sourceId)
        activeSchedules.remove(sourceId)
    }

    /**
     * Resumes a paused source: re-creates scheduled work and sets its status to IDLE.
     *
     * @param sourceId ID of the source (must exist in [sourceManager])
     * @param intervalHours Interval between backups in hours (minimum 1)
     * @param constraints Execution constraints
     * @throws IllegalArgumentException if the source does not exist
     */
    fun resumeSource(
        sourceId: String,
        intervalHours: Long,
        constraints: BackupConstraints = BackupConstraints(),
    ) {
        val source = sourceManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source not found: $sourceId")

        val effectiveInterval = intervalHours.coerceAtLeast(1)

        sourceManager.resumeSource(sourceId)
        schedulingPort.schedulePeriodic(
            sourceId = sourceId,
            sourcePath = source.path,
            intervalHours = effectiveInterval,
            constraints = constraints,
        )
        activeSchedules.add(sourceId)
    }

    /**
     * Updates the schedule for a source by cancelling the old one and creating a new one.
     *
     * @param sourceId ID of the source (must exist in [sourceManager])
     * @param intervalHours New interval between backups in hours (minimum 1)
     * @param constraints New execution constraints
     * @throws IllegalArgumentException if the source does not exist
     */
    fun updateSchedule(
        sourceId: String,
        intervalHours: Long,
        constraints: BackupConstraints = BackupConstraints(),
    ) {
        val source = sourceManager.getSource(sourceId)
            ?: throw IllegalArgumentException("Source not found: $sourceId")

        val effectiveInterval = intervalHours.coerceAtLeast(1)

        schedulingPort.cancel(sourceId)
        schedulingPort.schedulePeriodic(
            sourceId = sourceId,
            sourcePath = source.path,
            intervalHours = effectiveInterval,
            constraints = constraints,
        )
        activeSchedules.add(sourceId)
    }

    /**
     * Checks whether the given source has an active schedule tracked by this scheduler.
     */
    fun hasActiveSchedule(sourceId: String): Boolean = sourceId in activeSchedules
}

/**
 * Production implementation of [WorkSchedulingPort] that delegates to [BackupWorker]
 * static scheduling methods via the real WorkManager.
 *
 * @param context Application context for WorkManager access
 */
class WorkManagerSchedulingAdapter(
    private val context: Context,
) : WorkSchedulingPort {

    override fun scheduleOneTime(
        sourceId: String,
        sourcePath: String,
        constraints: BackupConstraints,
    ) {
        BackupWorker.scheduleOneTime(
            context = context,
            sourceId = sourceId,
            sourcePath = sourcePath,
            constraints = constraints,
        )
    }

    override fun schedulePeriodic(
        sourceId: String,
        sourcePath: String,
        intervalHours: Long,
        constraints: BackupConstraints,
    ) {
        BackupWorker.schedulePeriodic(
            context = context,
            sourceId = sourceId,
            sourcePath = sourcePath,
            intervalHours = intervalHours,
            constraints = constraints,
        )
    }

    override fun cancel(sourceId: String) {
        BackupWorker.cancel(context, sourceId)
    }
}
