package org.kopiaKt.snapshot.maintenance

import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.policy.RetentionPolicy
import org.kopiaKt.snapshot.policy.sortRetentionTags
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

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
 * @param zone The machine's own timezone, which decides **the weekly bucket and nothing else**.
 *   Go is inconsistent here and the divergence is worth matching rather than tidying: the hourly,
 *   daily, monthly and annual period ids all come from `UTCTimestamp.Format`, i.e.
 *   `ToTime().UTC().Format(...)`, but the weekly id comes from `ToTime().ISOWeek()` — and `ToTime()`
 *   is `time.Unix(0, n)`, which is local. Cutting the other four in the device's zone would let a
 *   phone at UTC+3 delete a snapshot desktop Kopia keeps, because two snapshots either side of
 *   midnight UTC share one local day and only the newer is given a reason; cutting the weekly one in
 *   UTC does the same thing around a local week boundary, in the other direction.
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

    // Every cutoff hangs off the newest COMPLETE snapshot. With none, the complete branch below
    // never runs and the cutoffs are never consulted, so there is nothing to compute.
    val cutoffs = sortedSnapshots.firstOrNull { it.incompleteReason == null }
        ?.let { cutoffTimes(policy, it.startTime, zone) }

    // ONE id set across every period type, and a counter per type -- Go's `ids` and `idCounters`.
    // Sharing the set is not an accident to tidy up: a weekly id ("YYYY-WW") and a monthly id
    // ("YYYY-MM") are the same string for ISO weeks 01-12, and the monthly grant blocking the
    // weekly one is what leaves the weekly slot for an older snapshot.
    val ids = mutableSetOf<String>()
    val counters = mutableMapOf<String, Int>()

    return sortedSnapshots.mapIndexed { index, snapshot ->
        val reasons = if (snapshot.incompleteReason != null) {
            if (snapshot.id in incompleteKept) listOf("incomplete") else emptyList()
        } else {
            retentionReasons(index, snapshot, policy, cutoffs, ids, counters, zone)
        } + snapshot.pins.map { "pin:$it" }

        if (reasons.isNotEmpty()) RetentionResult.keep(snapshot, reasons) else RetentionResult.delete(snapshot)
    }
}

/**
 * The reasons to keep a complete snapshot, in Go's display order, or an empty list if nothing wants
 * it. Mutates [ids] and [counters], which are shared across the whole run.
 */
private fun retentionReasons(
    index: Int,
    snapshot: SnapshotManifest,
    policy: RetentionPolicy,
    cutoffs: CutoffTimes?,
    ids: MutableSet<String>,
    counters: MutableMap<String, Int>,
    zone: ZoneId,
): List<String> {
    val reasons = mutableListOf<String>()

    for (period in periodCases(index, snapshot.startTime, policy, cutoffs, zone)) {
        if (!period.canSpeakFor(snapshot.startTime, ids, counters)) continue

        val used = counters[period.type] ?: 0
        ids.add(period.id)
        counters[period.type] = used + 1
        reasons.add("${period.type}-${used + 1}")
    }

    return sortRetentionTags(reasons)
}

/** One of Go's `cases` rows: the period that might speak for a snapshot, and what would stop it. */
private data class PeriodCase(val cutoff: Instant?, val id: String, val type: String, val max: Int?)

/** Go's four guards, in Go's order: unset period, too old for it, id already claimed, quota spent. */
private fun PeriodCase.canSpeakFor(
    startTime: Instant,
    ids: Set<String>,
    counters: Map<String, Int>,
): Boolean {
    val limit = max ?: return false
    if (cutoff != null && startTime < cutoff) return false
    if (id in ids) return false
    val used = counters[type] ?: 0
    return used < limit
}

private fun periodCases(
    index: Int,
    startTime: Instant,
    policy: RetentionPolicy,
    cutoffs: CutoffTimes?,
    zone: ZoneId,
): List<PeriodCase> {
    val utc = ZonedDateTime.ofInstant(startTime, ZoneOffset.UTC)
    // The week is the one period Go resolves in the machine's own zone -- see the note on `zone`.
    val local = ZonedDateTime.ofInstant(startTime, zone)
    // Locale.ROOT is load-bearing, not decoration. Kotlin's String.format defaults to the device
    // locale, and on one with non-ASCII digits (fa, ar, bn, ne) this id would come out as "۲۰۲۶-۰۱"
    // while the monthly id below stays ASCII "2026-01" -- the two would stop colliding, the shared
    // namespace would quietly stop working, and those users alone would get the over-deletion this
    // whole port exists to remove. Go emits ASCII unconditionally.
    val weekId = String.format(
        Locale.ROOT,
        "%04d-%02d",
        local.get(WeekFields.ISO.weekBasedYear()),
        local.get(WeekFields.ISO.weekOfWeekBasedYear()),
    )

    // Go's order, which decides who wins a collision in the shared id set. `latest`'s id is the
    // snapshot's index, and it shares that set too -- faithfully, quirk and all.
    // effectiveKeepLatest() is Go's fail-safe: a policy with NO keep counts set keeps everything
    // rather than reading as "delete the entire snapshot history".
    return listOf(
        PeriodCase(null, index.toString(), "latest", policy.effectiveKeepLatest()),
        PeriodCase(cutoffs?.annual, utc.format(ANNUAL_ID), "annual", policy.keepAnnual),
        PeriodCase(cutoffs?.monthly, utc.format(MONTHLY_ID), "monthly", policy.keepMonthly),
        PeriodCase(cutoffs?.weekly, weekId, "weekly", policy.keepWeekly),
        PeriodCase(cutoffs?.daily, utc.format(DAILY_ID), "daily", policy.keepDaily),
        PeriodCase(cutoffs?.hourly, utc.format(HOURLY_ID), "hourly", policy.keepHourly),
    )
}

// Locale.ROOT for the same reason as the weekly id above: these are repository-level keys compared
// as strings, never anything a user reads.
private val ANNUAL_ID = DateTimeFormatter.ofPattern("uuuu").withLocale(Locale.ROOT)
private val MONTHLY_ID = DateTimeFormatter.ofPattern("uuuu-MM").withLocale(Locale.ROOT)
private val DAILY_ID = DateTimeFormatter.ofPattern("uuuu-MM-dd").withLocale(Locale.ROOT)
private val HOURLY_ID = DateTimeFormatter.ofPattern("uuuu-MM-dd HH").withLocale(Locale.ROOT)

/**
 * How far back each period is allowed to reach, measured from the newest complete snapshot.
 *
 * This is what makes `keepDaily = 7` mean "the last seven days" rather than "seven distinct days,
 * however far back they run". Without it a sparse history — weekly backups against a daily policy —
 * hands out daily reasons to month-old snapshots and keeps far more than the policy says.
 *
 * A null means the policy does not set that period at all, so nothing is ever granted for it and the
 * cutoff would never be read; Go computes a far-future placeholder in that case, to the same effect.
 *
 * Known divergence, deliberately left: when the computed wall time lands inside a DST gap or overlap,
 * Go's `AddDate` and `java.time`'s stepping resolve it differently and the cutoff can sit an hour
 * apart. It matters only for a snapshot whose start time falls in exactly that hour and which has no
 * other reason to be kept, and the boundary moves with every new backup. It is also dwarfed by the
 * fact that the cutoff is computed in the machine's own zone at all, so a phone and a desktop in
 * different timezones already disagree by far more than an hour — mirroring Go there is the point.
 */
private data class CutoffTimes(
    val annual: Instant?,
    val monthly: Instant?,
    val weekly: Instant?,
    val daily: Instant?,
    val hourly: Instant?,
)

private fun cutoffTimes(policy: RetentionPolicy, newestComplete: Instant, zone: ZoneId) = CutoffTimes(
    annual = policy.keepAnnual?.let { newestComplete.minusCalendarYears(it.sane(), zone) },
    monthly = policy.keepMonthly?.let { newestComplete.minusCalendarMonths(it.sane(), zone) },
    // Go counts weeks as 7 calendar days, not as a duration.
    weekly = policy.keepWeekly?.let { newestComplete.minusCalendarDays(it.sane() * WEEK_DAYS, zone) },
    daily = policy.keepDaily?.let { newestComplete.minusCalendarDays(it.sane(), zone) },
    // ...but hours as an exact duration, exactly as Go does.
    hourly = policy.keepHourly?.let { newestComplete.minus(it.sane().toLong(), ChronoUnit.HOURS) },
)

/**
 * The policy is read from the repository and may have been written by any client, so a keep count is
 * untrusted input. Beyond this it stops meaning anything — 100,000 days is 273 years — while
 * `keepAnnual` past a billion walks out of `java.time`'s range and throws, and `keepWeekly` past
 * ~306 million overflows the multiply into a NEGATIVE cutoff, which sits in the future and refuses
 * every weekly reason. Go just computes a harmless far-past time; clamping gets to the same place.
 */
private fun Int.sane(): Int = coerceIn(0, MAX_KEEP_COUNT)

private const val MAX_KEEP_COUNT = 100_000

private const val WEEK_DAYS = 7

private fun Instant.minusCalendarDays(days: Int, zone: ZoneId): Instant {
    val local = ZonedDateTime.ofInstant(this, zone)
    return local.minusDays(days.toLong()).toInstant()
}

/**
 * Go's `AddDate` normalises an overflowing day-of-month *forward* — one month before 2026-03-31 is
 * 2026-03-03, not the 2026-02-28 that `java.time` would clamp to. The cutoff is a deletion boundary,
 * so the arithmetic is mirrored rather than left to clamp.
 */
private fun Instant.minusCalendarMonths(months: Int, zone: ZoneId): Instant {
    val zdt = ZonedDateTime.ofInstant(this, zone)
    return zdt.withDayOfMonth(1).minusMonths(months.toLong()).plusDays(zdt.dayOfMonth - 1L).toInstant()
}

/** As [minusCalendarMonths]: one year before 2024-02-29 is 2023-03-01 in Go, not 2023-02-28. */
private fun Instant.minusCalendarYears(years: Int, zone: ZoneId): Instant {
    val zdt = ZonedDateTime.ofInstant(this, zone)
    return zdt.withDayOfMonth(1).minusYears(years.toLong()).plusDays(zdt.dayOfMonth - 1L).toInstant()
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
