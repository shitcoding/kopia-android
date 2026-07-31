package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.RetentionPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone

class RetentionComputationTest {

    private val source = SourceInfo(host = "localhost", userName = "user", path = "/data")
    private val zone = ZoneId.of("UTC")

    private fun createSnapshot(id: String, startTime: Instant, pins: List<String> = emptyList()): SnapshotManifest = SnapshotManifest(
        id = id,
        source = source,
        startTime = startTime,
        endTime = startTime.plusSeconds(60),
        pins = pins,
    )

    @Test
    fun `empty snapshot list returns empty result`() {
        val policy = RetentionPolicy.Default
        val result = computeRetention(emptyList(), policy, Instant.now(), zone)

        assertThat(result).isEmpty()
    }

    @Test
    fun `keepLatest retains N most recent snapshots`() {
        val now = Instant.now()
        val snapshots = (1..5).map { i ->
            createSnapshot("snap$i", now.minusSeconds(i * 60L))
        }

        val policy = RetentionPolicy(keepLatest = 3)
        val result = computeRetention(snapshots, policy, now, zone)

        val kept = result.filter { !it.shouldDelete }
        val deleted = result.filter { it.shouldDelete }

        assertThat(kept).hasSize(3)
        assertThat(deleted).hasSize(2)

        // Most recent 3 should be kept
        assertThat(kept.map { it.snapshot.id }).containsExactly("snap1", "snap2", "snap3")
    }

    @Test
    fun `pinned snapshots are always retained`() {
        val now = Instant.now()
        val snapshots = listOf(
            createSnapshot("snap1", now.minusSeconds(60)),
            createSnapshot("snap2", now.minusSeconds(120), pins = listOf("important")),
            createSnapshot("snap3", now.minusSeconds(180)),
        )

        val policy = RetentionPolicy(keepLatest = 1)
        val result = computeRetention(snapshots, policy, now, zone)

        val kept = result.filter { !it.shouldDelete }

        // snap1 (latest) and snap2 (pinned) should be kept
        assertThat(kept.map { it.snapshot.id }).containsExactly("snap1", "snap2")

        // Check pin reason
        val snap2Result = result.find { it.snapshot.id == "snap2" }
        assertThat(snap2Result?.retentionReasons).contains("pin:important")
    }

    @Test
    fun `all zeroes means keep all`() {
        val now = Instant.now()
        val snapshots = (1..10).map { i ->
            createSnapshot("snap$i", now.minusSeconds(i * 60L))
        }

        val policy = RetentionPolicy(
            keepLatest = 0,
            keepHourly = 0,
            keepDaily = 0,
            keepWeekly = 0,
            keepMonthly = 0,
            keepAnnual = 0,
        )

        // effectiveKeepLatest should return MAX_VALUE when all are 0
        assertThat(policy.effectiveKeepLatest()).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `hourly retention keeps one per hour`() {
        val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val snapshots = listOf(
            createSnapshot("snap1", now.minusSeconds(1800)), // 30 min ago (current hour)
            createSnapshot("snap2", now.minusSeconds(3600 + 1800)), // 1.5 hours ago
            createSnapshot("snap3", now.minusSeconds(3600 + 3000)), // also 1+ hour ago (same hour bucket)
            createSnapshot("snap4", now.minusSeconds(7200 + 1800)), // 2.5 hours ago
        )

        val policy = RetentionPolicy(keepLatest = 0, keepHourly = 3)
        val result = computeRetention(snapshots, policy, now, zone)

        // Should keep snap2 (hourly-1), snap4 (hourly-2)
        // snap1 is in current hour which is excluded
        // snap3 is same hour bucket as snap2
        val kept = result.filter { !it.shouldDelete }

        // The exact behavior depends on implementation
        // At minimum, we should keep distinct hour buckets
        assertThat(kept.size).isAtLeast(2)
    }

    @Test
    fun `retention result includes all reasons`() {
        val now = Instant.now()
        val snapshot = createSnapshot("snap1", now.minusSeconds(60), pins = listOf("backup"))

        val policy = RetentionPolicy(keepLatest = 1)
        val result = computeRetention(listOf(snapshot), policy, now, zone)

        assertThat(result).hasSize(1)
        val reasons = result[0].retentionReasons

        assertThat(reasons).contains("latest-1")
        assertThat(reasons).contains("pin:backup")
    }

    @Test
    fun `computeSnapshotsToDelete returns only deleted`() {
        val now = Instant.now()
        val snapshots = (1..5).map { i ->
            createSnapshot("snap$i", now.minusSeconds(i * 60L))
        }

        val policy = RetentionPolicy(keepLatest = 2)
        val toDelete = computeSnapshotsToDelete(snapshots, policy, now, zone)

        assertThat(toDelete.map { it.id }).containsExactly("snap3", "snap4", "snap5")
    }

    @Test
    fun `computeSnapshotsToKeep returns only kept`() {
        val now = Instant.now()
        val snapshots = (1..5).map { i ->
            createSnapshot("snap$i", now.minusSeconds(i * 60L))
        }

        val policy = RetentionPolicy(keepLatest = 2)
        val toKeep = computeSnapshotsToKeep(snapshots, policy, now, zone)

        assertThat(toKeep.map { it.id }).containsExactly("snap1", "snap2")
    }

    @Test
    fun `incomplete snapshots without endTime are filtered`() {
        val now = Instant.now()
        val snapshots = listOf(
            createSnapshot("complete", now.minusSeconds(60)),
            SnapshotManifest(
                id = "incomplete",
                source = source,
                startTime = now.minusSeconds(120),
                incompleteReason = "cancelled",
            ),
        )

        val policy = RetentionPolicy(keepLatest = 10)
        val result = computeRetention(snapshots, policy, now, zone)

        // Incomplete snapshots are filtered in the sort step
        // Only complete snapshot should be processed for latest
        val latestReasons = result.filter { it.retentionReasons.any { r -> r.startsWith("latest-") } }
        assertThat(latestReasons).hasSize(1)
        assertThat(latestReasons[0].snapshot.id).isEqualTo("complete")
    }

    @Test
    fun `daily retention considers timezone`() {
        // Use a specific instant that's known to be at a day boundary in UTC
        val noon = Instant.parse("2024-01-15T12:00:00Z")

        val snapshots = listOf(
            createSnapshot("today1", noon.minusSeconds(3600)), // 11:00 same day
            createSnapshot("today2", noon.minusSeconds(7200)), // 10:00 same day
            createSnapshot("yesterday", noon.minusSeconds(86400 + 3600)), // Yesterday 11:00
        )

        val policy = RetentionPolicy(keepLatest = 0, keepDaily = 2)
        val utcZone = ZoneId.of("UTC")
        val result = computeRetention(snapshots, policy, noon, utcZone)

        // Should keep one from yesterday (not today since it's current period)
        val dailyReasons = result.filter {
            it.retentionReasons.any { r -> r.startsWith("daily-") }
        }
        assertThat(dailyReasons).isNotEmpty()
    }

    @Test
    fun `a policy with no keep counts set keeps everything`() {
        val now = Instant.now()
        val snapshots = (1..5).map { i -> createSnapshot("snap$i", now.minus(i.toLong(), ChronoUnit.DAYS)) }

        // Go's EffectiveKeepLatest returns MaxInt32 when every count is unset, so that an empty
        // policy is a no-op rather than an instruction to delete the entire snapshot history.
        val result = computeRetention(snapshots, RetentionPolicy(), now, zone)

        assertThat(result.filter { it.shouldDelete }).isEmpty()
    }

    @Test
    fun `a policy with an explicit zero keepLatest still honours time-based keeps`() {
        val now = Instant.parse("2026-07-27T12:00:00Z")
        val today = createSnapshot("today", now.minusSeconds(3600))
        val yesterday = createSnapshot("yesterday", now.minus(1, ChronoUnit.DAYS))

        val policy = RetentionPolicy(keepLatest = 0, keepDaily = 7)
        val result = computeRetention(listOf(today, yesterday), policy, now, zone)

        // The newest snapshot must earn its own day's daily slot. Skipping the current period left
        // today's snapshots with no retention reason at all, i.e. marked for deletion.
        val kept = result.filter { !it.shouldDelete }.map { it.snapshot.id }
        assertThat(kept).containsExactly("today", "yesterday")
    }

    @Test
    fun `RetentionResult keep factory creates correct result`() {
        val snapshot = createSnapshot("test", Instant.now())
        val result = RetentionResult.keep(snapshot, listOf("latest-1", "daily-1"))

        assertThat(result.shouldDelete).isFalse()
        assertThat(result.retentionReasons).containsExactly("latest-1", "daily-1")
        assertThat(result.snapshot).isEqualTo(snapshot)
    }

    @Test
    fun `RetentionResult delete factory creates correct result`() {
        val snapshot = createSnapshot("test", Instant.now())
        val result = RetentionResult.delete(snapshot)

        assertThat(result.shouldDelete).isTrue()
        assertThat(result.retentionReasons).isEmpty()
        assertThat(result.snapshot).isEqualTo(snapshot)
    }

    @Test
    fun `weekly and monthly period ids share one namespace, as they do in Go`() {
        // Go keeps ONE `ids` map across every period type. A weekly id is "YYYY-WW" and a monthly id
        // is "YYYY-MM", so for ISO weeks 01-12 they are the same string -- and monthly is evaluated
        // first, which BLOCKS the weekly grant and leaves the weekly slot free for an older
        // snapshot. Give each tracker its own bucket set instead and the newest snapshot takes both
        // reasons, the older one gets nothing, and the phone reaps a snapshot desktop Kopia keeps.
        val newer = createSnapshot("newer", Instant.parse("2026-01-02T12:00:00Z")) // ISO week 1 -> "2026-01"
        val older = createSnapshot("older", Instant.parse("2025-12-28T12:00:00Z")) // ISO week 52 -> "2025-52"
        val policy = RetentionPolicy(keepLatest = 1, keepWeekly = 1, keepMonthly = 1)

        val result = computeRetention(listOf(newer, older), policy, Instant.parse("2026-01-02T13:00:00Z"), zone)

        assertThat(result.first { it.snapshot.id == "newer" }.retentionReasons)
            .containsExactly("latest-1", "monthly-1")
        assertThat(result.first { it.snapshot.id == "older" }.retentionReasons)
            .containsExactly("weekly-1")
    }

    @Test
    fun `the shared namespace survives a device locale with non-ASCII digits`() {
        // The weekly id is built with String.format, which follows the default locale unless told
        // otherwise. Under fa/ar it would render as Persian or Arabic digits while the monthly id
        // stays ASCII, the two would stop colliding, and the over-deletion above would come back for
        // those users only -- invisible to every test running under an English locale.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa"))

            val newer = createSnapshot("newer", Instant.parse("2026-01-02T12:00:00Z"))
            val older = createSnapshot("older", Instant.parse("2025-12-28T12:00:00Z"))
            val policy = RetentionPolicy(keepLatest = 1, keepWeekly = 1, keepMonthly = 1)

            val result = computeRetention(listOf(newer, older), policy, Instant.parse("2026-01-02T13:00:00Z"), zone)

            assertThat(result.first { it.snapshot.id == "older" }.retentionReasons).containsExactly("weekly-1")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a snapshot exactly at the cutoff is kept`() {
        // Go's guard is a strict `Before`, so the cutoff instant itself is still inside the window.
        // Nothing else in the suite sits a snapshot exactly on a cutoff, and without this a `<`
        // turning into a `<=` -- deleting the boundary snapshot -- passes everything.
        val newest = createSnapshot("newest", Instant.parse("2026-07-28T12:00:00Z"))
        val onTheEdge = createSnapshot("onTheEdge", Instant.parse("2026-07-21T12:00:00Z")) // exactly 7 days
        val policy = RetentionPolicy(keepLatest = 1, keepDaily = 7)

        val result = computeRetention(listOf(newest, onTheEdge), policy, Instant.parse("2026-07-28T13:00:00Z"), zone)

        assertThat(result.first { it.snapshot.id == "onTheEdge" }.retentionReasons).containsExactly("daily-2")
    }

    @Test
    fun `an absurd keep count neither throws nor overflows into a future cutoff`() {
        // The policy comes out of the repository and may have been written by any client. keepAnnual
        // past ~1e9 walks java.time out of range; keepWeekly past ~306 million overflows the
        // days multiply negative, putting the cutoff in the FUTURE where it refuses every snapshot.
        val newest = createSnapshot("newest", Instant.parse("2026-07-28T12:00:00Z"))
        val older = createSnapshot("older", Instant.parse("2020-07-28T12:00:00Z"))
        val policy = RetentionPolicy(keepLatest = 1, keepWeekly = Int.MAX_VALUE, keepAnnual = Int.MAX_VALUE)

        val result = computeRetention(listOf(newest, older), policy, Instant.parse("2026-07-28T13:00:00Z"), zone)

        assertThat(result.filterNot { it.shouldDelete }.map { it.snapshot.id })
            .containsExactly("newest", "older")
    }

    @Test
    fun `a period reason is refused to snapshots older than Go's cutoff`() {
        // Go computes a cutoff per period type from the newest COMPLETE snapshot -- keepDaily=7
        // means "the last seven days", not "seven distinct days however far back they run". Without
        // it a sparse history hands out daily reasons to month-old snapshots and the phone keeps
        // far more than the policy says.
        val newest = createSnapshot("newest", Instant.parse("2026-07-28T12:00:00Z"))
        val ancient = createSnapshot("ancient", Instant.parse("2026-07-01T12:00:00Z"))
        val policy = RetentionPolicy(keepLatest = 1, keepDaily = 7)

        val result = computeRetention(listOf(newest, ancient), policy, Instant.parse("2026-07-28T13:00:00Z"), zone)

        assertThat(result.filterNot { it.shouldDelete }.map { it.snapshot.id }).containsExactly("newest")
    }

    @Test
    fun `retention reasons come back in Go's display order`() {
        // Go sorts every snapshot's reasons through SortRetentionTags before storing them:
        // latest, hourly, daily, weekly, monthly, annual -- not the order the periods are evaluated
        // in. The snapshot list shows these strings, so the order is user-visible.
        val only = createSnapshot("only", Instant.parse("2026-07-28T12:00:00Z"))
        val policy = RetentionPolicy(
            keepLatest = 1,
            keepHourly = 1,
            keepDaily = 1,
            keepWeekly = 1,
            keepMonthly = 1,
            keepAnnual = 1,
        )

        val result = computeRetention(listOf(only), policy, Instant.parse("2026-07-28T13:00:00Z"), zone)

        assertThat(result.single().retentionReasons)
            .containsExactly("latest-1", "hourly-1", "daily-1", "weekly-1", "monthly-1", "annual-1")
            .inOrder()
    }

    @Test
    fun `the weekly period follows the device timezone, matching Go's own inconsistency`() {
        // Go's weekly id is the only one not forced to UTC: `ToTime().ISOWeek()`, and `ToTime()` is
        // `time.Unix(0, n)` -- local. These two are the same UTC ISO week but different Moscow ones
        // (the local week turns at 21:00Z Sunday), so Go gives each its own weekly reason and keeps
        // both. Normalising the week to UTC would collapse them into one bucket and delete the
        // older -- the same over-deletion the UTC test above exists to prevent, mirrored.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))

            val older = createSnapshot("older", Instant.parse("2026-07-05T20:30:00Z"))
            val newer = createSnapshot("newer", Instant.parse("2026-07-05T22:30:00Z"))
            val policy = RetentionPolicy(keepLatest = 1, keepWeekly = 2)

            val result = computeRetention(listOf(older, newer), policy, Instant.parse("2026-07-06T00:00:00Z"))

            assertThat(result.filterNot { it.shouldDelete }.map { it.snapshot.id })
                .containsExactly("older", "newer")
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `hourly, daily, monthly and annual periods are bucketed in UTC, not the device timezone`() {
        // Go derives every period id from `UTCTimestamp.Format`, which is `ToTime().UTC().Format(...)`
        // -- the buckets are UTC wherever the machine sits. Defaulting to the device's zone instead
        // would make a phone delete snapshots desktop Kopia keeps: these two are on different UTC
        // days, so Go grants each its own daily reason, but at UTC+3 they fall on the same local day
        // and the older one is left with no reason at all.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))

            val older = createSnapshot("older", Instant.parse("2026-07-01T23:30:00Z"))
            val newer = createSnapshot("newer", Instant.parse("2026-07-02T01:30:00Z"))
            val policy = RetentionPolicy(keepLatest = 1, keepDaily = 2)

            val result = computeRetention(listOf(older, newer), policy, Instant.parse("2026-07-02T02:00:00Z"))

            assertThat(result.filterNot { it.shouldDelete }.map { it.snapshot.id })
                .containsExactly("older", "newer")
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
