package org.kopiaKt.snapshot.maintenance

import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.policy.RetentionPolicy
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/**
 * Result of retention computation for a single snapshot.
 */
data class RetentionResult(
    /**
     * The snapshot manifest.
     */
    val snapshot: SnapshotManifest,

    /**
     * Reasons why this snapshot is being retained.
     * Empty if the snapshot should be deleted.
     */
    val retentionReasons: List<String>,

    /**
     * Whether this snapshot should be deleted.
     */
    val shouldDelete: Boolean,
) {
    companion object {
        /**
         * Creates a result indicating the snapshot should be kept.
         */
        fun keep(snapshot: SnapshotManifest, reasons: List<String>): RetentionResult = RetentionResult(snapshot, reasons, shouldDelete = false)

        /**
         * Creates a result indicating the snapshot should be deleted.
         */
        fun delete(snapshot: SnapshotManifest): RetentionResult = RetentionResult(snapshot, emptyList(), shouldDelete = true)
    }
}

/**
 * Computes retention for a list of snapshots based on retention policy.
 *
 * Implements the same algorithm as Go's policy.ComputeRetentionReasons.
 *
 * @param snapshots List of snapshots to evaluate, will be sorted by startTime descending
 * @param policy The retention policy to apply
 * @param now The current time (for time-based retention)
 * @param zone The timezone to use for day/week/month/year calculations
 * @return List of retention results for each snapshot
 */
fun computeRetention(
    snapshots: List<SnapshotManifest>,
    policy: RetentionPolicy,
    @Suppress("UNUSED_PARAMETER") now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RetentionResult> {
    if (snapshots.isEmpty()) {
        return emptyList()
    }

    // Sort snapshots by start time, most recent first
    val sortedSnapshots = snapshots.sortedByDescending { it.startTime }

    // Go measures an incomplete snapshot's age against the NEWEST snapshot's start time rather than
    // the wall clock, so the same set of manifests always produces the same answer -- a maintenance
    // run started an hour later must not reap a different set.
    val newestStartTime = sortedSnapshots.first().startTime
    val incompleteKept = incompleteToKeep(sortedSnapshots, newestStartTime)

    // Track retention for each category
    // effectiveKeepLatest() is Go's fail-safe: a policy with NO keep counts set keeps
    // everything rather than reading as "delete the entire snapshot history".
    val latestTracker = RetentionTracker(policy.effectiveKeepLatest() ?: 0)
    val hourlyTracker = TimeBasedTracker(policy.keepHourly ?: 0, "hourly") { it.truncateToHour(zone) }
    val dailyTracker = TimeBasedTracker(policy.keepDaily ?: 0, "daily") { it.truncateToDay(zone) }
    val weeklyTracker = TimeBasedTracker(policy.keepWeekly ?: 0, "weekly") { it.truncateToWeek(zone) }
    val monthlyTracker = TimeBasedTracker(policy.keepMonthly ?: 0, "monthly") { it.truncateToMonth(zone) }
    val annualTracker = TimeBasedTracker(policy.keepAnnual ?: 0, "annual") { it.truncateToYear(zone) }

    val timeTrackers = listOf(annualTracker, monthlyTracker, weeklyTracker, dailyTracker, hourlyTracker)
    val results = mutableListOf<RetentionResult>()

    for (snapshot in sortedSnapshots) {
        val reasons = retentionReasons(snapshot, incompleteKept, latestTracker, timeTrackers)

        // Check pins
        if (snapshot.pins.isNotEmpty()) {
            reasons.addAll(snapshot.pins.map { "pin:$it" })
        }

        // Ignore identical snapshots if configured
        // (Would compare root object ID with previous snapshot)

        results.add(
            if (reasons.isNotEmpty()) {
                RetentionResult.keep(snapshot, reasons)
            } else {
                RetentionResult.delete(snapshot)
            },
        )
    }

    return results
}

/**
 * The incomplete snapshots to keep, following Go's rule in `snapshot/policy/retention_policy.go`.
 *
 * Walking newest-first, an incomplete snapshot is kept while it is younger than
 * [RETAIN_INCOMPLETE_YOUNGER_THAN] **or** it is within the first [RETAIN_INCOMPLETE_MINIMUM_COUNT]
 * snapshots — and the walk **stops at the first complete one**. That last part is what stops
 * checkpoints accumulating forever: once a run has finished, the partial manifests it left behind
 * are litter no matter how recent they are. The minimum count is what stops an overnight
 * interruption reaping every checkpoint of a backup that could still be resumed.
 */
/** The reasons to keep [snapshot], or an empty list if nothing wants it. */
private fun retentionReasons(
    snapshot: SnapshotManifest,
    incompleteKept: Set<String>,
    latestTracker: RetentionTracker,
    timeTrackers: List<TimeBasedTracker>,
): MutableList<String> {
    val reasons = mutableListOf<String>()
    if (snapshot.incompleteReason != null) {
        if (snapshot.id in incompleteKept) {
            reasons.add("incomplete")
        }
        return reasons
    }
    latestTracker.tryAdd(snapshot.id)?.let { reasons.add(it) }
    timeTrackers.forEach { tracker ->
        tracker.tryAdd(snapshot.startTime, snapshot.id)?.let { reasons.add(it) }
    }
    return reasons
}

private fun incompleteToKeep(
    sortedSnapshots: List<SnapshotManifest>,
    newestStartTime: Instant,
): Set<String> = sortedSnapshots
    .asSequence()
    .withIndex()
    .takeWhile { (index, snapshot) ->
        snapshot.incompleteReason != null &&
            (
                Duration.between(snapshot.startTime, newestStartTime) < RETAIN_INCOMPLETE_YOUNGER_THAN ||
                    index < RETAIN_INCOMPLETE_MINIMUM_COUNT
                )
    }
    .map { (_, snapshot) -> snapshot.id }
    .toSet()

private val RETAIN_INCOMPLETE_YOUNGER_THAN: Duration = Duration.ofHours(4)
private const val RETAIN_INCOMPLETE_MINIMUM_COUNT = 3

/**
 * Returns the list of snapshots that should be deleted based on retention policy.
 */
fun computeSnapshotsToDelete(
    snapshots: List<SnapshotManifest>,
    policy: RetentionPolicy,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<SnapshotManifest> = computeRetention(snapshots, policy, now, zone)
    .filter { it.shouldDelete }
    .map { it.snapshot }

/**
 * Returns the list of snapshots that should be kept based on retention policy.
 */
fun computeSnapshotsToKeep(
    snapshots: List<SnapshotManifest>,
    policy: RetentionPolicy,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<SnapshotManifest> = computeRetention(snapshots, policy, now, zone)
    .filter { !it.shouldDelete }
    .map { it.snapshot }

// Helper class for tracking "keep N latest"
private class RetentionTracker(private val maxCount: Int) {
    private var count = 0

    fun tryAdd(id: String): String? {
        if (maxCount <= 0) return null
        if (count >= maxCount) return null

        count++
        return "latest-$count"
    }
}

// Helper class for time-based retention tracking
private class TimeBasedTracker(
    private val maxCount: Int,
    private val reasonPrefix: String,
    private val truncate: (Instant) -> Instant,
) {
    private val buckets = mutableSetOf<Instant>()
    private var count = 0

    fun tryAdd(time: Instant, id: String): String? {
        if (maxCount <= 0) return null
        if (count >= maxCount) return null

        // The newest period counts. Go grants the most recent snapshot its own period's slot; an
        // "in progress" exclusion left today's snapshots with no retention reason — and therefore
        // marked for deletion — whenever keepLatest was 0 and only time-based keeps were set.
        val bucket = truncate(time)

        if (bucket in buckets) return null

        buckets.add(bucket)
        count++

        return "$reasonPrefix-$count"
    }
}

// Extension functions for time truncation
private fun Instant.truncateToHour(zone: ZoneId): Instant = ZonedDateTime.ofInstant(this, zone)
    .truncatedTo(ChronoUnit.HOURS)
    .toInstant()

private fun Instant.truncateToDay(zone: ZoneId): Instant = ZonedDateTime.ofInstant(this, zone)
    .truncatedTo(ChronoUnit.DAYS)
    .toInstant()

private fun Instant.truncateToWeek(zone: ZoneId): Instant {
    val zdt = ZonedDateTime.ofInstant(this, zone)
    val dayOfWeek = zdt.get(ChronoField.DAY_OF_WEEK)
    // Go to start of week (Monday = 1)
    return zdt.minusDays(dayOfWeek.toLong() - 1)
        .truncatedTo(ChronoUnit.DAYS)
        .toInstant()
}

private fun Instant.truncateToMonth(zone: ZoneId): Instant = ZonedDateTime.ofInstant(this, zone)
    .withDayOfMonth(1)
    .truncatedTo(ChronoUnit.DAYS)
    .toInstant()

private fun Instant.truncateToYear(zone: ZoneId): Instant = ZonedDateTime.ofInstant(this, zone)
    .withDayOfYear(1)
    .truncatedTo(ChronoUnit.DAYS)
    .toInstant()
