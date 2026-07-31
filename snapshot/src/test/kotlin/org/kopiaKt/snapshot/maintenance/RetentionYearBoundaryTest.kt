package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.RetentionPolicy
import java.time.Instant
import java.time.ZoneId

/**
 * Tests retention tag computation across year boundaries.
 *
 * Go issue #2964 reported weekly retention tags being wrong at year boundaries
 * (Dec 31 - Jan 1). These tests verify that snapshots around year, month, and
 * week boundaries are correctly bucketed and retained.
 *
 * Each case includes a later "anchor" snapshot in its own period so the boundary snapshots are not
 * the newest ones — the anchor claims the first slot, and the boundary snapshots must still get
 * their own distinct buckets after it.
 */
class RetentionYearBoundaryTest {

    private val source = SourceInfo(host = "localhost", userName = "user", path = "/data")
    private val zone = ZoneId.of("UTC")

    private fun createSnapshot(id: String, startTime: Instant): SnapshotManifest = SnapshotManifest(
        id = id,
        source = source,
        startTime = startTime,
        endTime = startTime.plusSeconds(60),
    )

    @Nested
    @DisplayName("Weekly Retention Across Year Boundary")
    inner class WeeklyRetentionAcrossYearBoundary {

        @Test
        fun `should compute correct weekly tags across Dec 31 to Jan 1`() {
            // ISO week boundaries:
            // 2024-12-22 (Sun) = end of ISO week 2024-W51
            // 2024-12-23 (Mon) = start of ISO week 2024-W52
            // 2024-12-29 (Sun) = end of ISO week 2024-W52
            // 2024-12-30 (Mon) = start of ISO week 2025-W01
            // 2025-01-05 (Sun) = end of ISO week 2025-W01
            // 2025-01-06 (Mon) = start of ISO week 2025-W02
            val dec29 = createSnapshot("dec29", Instant.parse("2024-12-29T10:00:00Z")) // W52
            val dec30 = createSnapshot("dec30", Instant.parse("2024-12-30T10:00:00Z")) // W01
            val dec31 = createSnapshot("dec31", Instant.parse("2024-12-31T10:00:00Z")) // W01
            val jan01 = createSnapshot("jan01", Instant.parse("2025-01-01T10:00:00Z")) // W01

            // Anchor snapshot in a later week so the boundary weeks are not "current"
            val anchor = createSnapshot("anchor", Instant.parse("2025-01-13T12:00:00Z")) // W03

            val now = Instant.parse("2025-02-01T12:00:00Z")

            val policy = RetentionPolicy(keepLatest = 0, keepWeekly = 4)
            val allSnapshots = listOf(dec29, dec30, dec31, jan01, anchor)
            val result = computeRetention(allSnapshots, policy, now, zone)

            val weeklyKept = result.filter { r ->
                r.retentionReasons.any { it.startsWith("weekly-") }
            }

            // anchor -> W03 bucket -> weekly-1
            // jan01 -> W01 bucket -> weekly-2 (first snapshot in that week)
            // dec31 -> W01 bucket -> same bucket as jan01, skipped
            // dec30 -> W01 bucket -> same bucket as jan01, skipped
            // dec29 -> W52 bucket -> weekly-3 (different bucket)
            // Three distinct weekly buckets, and the year boundary does not merge W52 into W01.
            assertThat(weeklyKept).hasSize(3)

            val keptIds = weeklyKept.map { it.snapshot.id }
            // jan01 is the first snapshot in the W01 bucket (most recent in that week)
            assertThat(keptIds).contains("jan01")
            // dec29 is in a different week bucket (W52)
            assertThat(keptIds).contains("dec29")
        }
    }

    @Nested
    @DisplayName("Daily Retention Across Year Boundary")
    inner class DailyRetentionAcrossYearBoundary {

        @Test
        fun `should compute correct daily tags across Dec 31 to Jan 1`() {
            val dec31 = createSnapshot("dec31", Instant.parse("2024-12-31T23:00:00Z"))
            val jan01 = createSnapshot("jan01", Instant.parse("2025-01-01T01:00:00Z"))

            // Anchor snapshot on a later day so boundary days are not "current"
            val anchor = createSnapshot("anchor", Instant.parse("2025-01-05T12:00:00Z"))

            val now = Instant.parse("2025-01-10T12:00:00Z")

            val policy = RetentionPolicy(keepLatest = 0, keepDaily = 5)
            val result = computeRetention(listOf(dec31, jan01, anchor), policy, now, zone)

            val dailyKept = result.filter { r ->
                r.retentionReasons.any { it.startsWith("daily-") }
            }

            // anchor -> Jan 5 bucket -> daily-1
            // jan01 -> Jan 1 bucket -> daily-2
            // dec31 -> Dec 31 bucket -> daily-3
            assertThat(dailyKept).hasSize(3)
            assertThat(dailyKept.map { it.snapshot.id }).containsExactly("anchor", "jan01", "dec31")

            // Neither boundary snapshot should be deleted
            val boundaryResults = result.filter { it.snapshot.id in listOf("dec31", "jan01") }
            assertThat(boundaryResults.all { !it.shouldDelete }).isTrue()
        }
    }

    @Nested
    @DisplayName("Monthly Retention Across Year Boundary")
    inner class MonthlyRetentionAcrossYearBoundary {

        @Test
        fun `should compute correct monthly tags across Dec to Jan`() {
            val dec15 = createSnapshot("dec15", Instant.parse("2024-12-15T12:00:00Z"))
            val jan15 = createSnapshot("jan15", Instant.parse("2025-01-15T12:00:00Z"))

            // Anchor snapshot in a later month so boundary months are not "current"
            val anchor = createSnapshot("anchor", Instant.parse("2025-02-10T12:00:00Z"))

            val now = Instant.parse("2025-03-01T12:00:00Z")

            val policy = RetentionPolicy(keepLatest = 0, keepMonthly = 3)
            val result = computeRetention(listOf(dec15, jan15, anchor), policy, now, zone)

            val monthlyKept = result.filter { r ->
                r.retentionReasons.any { it.startsWith("monthly-") }
            }

            // anchor -> Feb 2025 bucket -> monthly-1
            // jan15 -> Jan 2025 bucket -> monthly-2
            // dec15 -> Dec 2024 bucket -> monthly-3
            assertThat(monthlyKept).hasSize(3)
            assertThat(monthlyKept.map { it.snapshot.id }).containsExactly("anchor", "jan15", "dec15")

            // Neither boundary snapshot should be deleted
            val boundaryResults = result.filter { it.snapshot.id in listOf("dec15", "jan15") }
            assertThat(boundaryResults.all { !it.shouldDelete }).isTrue()
        }
    }

    @Nested
    @DisplayName("Annual Retention Across Year Boundary")
    inner class AnnualRetentionAcrossYearBoundary {

        @Test
        fun `should compute correct annual tags across year boundary`() {
            val dec2024 = createSnapshot("dec2024", Instant.parse("2024-12-20T12:00:00Z"))
            val jan2025 = createSnapshot("jan2025", Instant.parse("2025-01-05T12:00:00Z"))

            // Anchor snapshot in a different year so boundary years are not "current"
            val anchor = createSnapshot("anchor", Instant.parse("2026-01-10T12:00:00Z"))

            val now = Instant.parse("2026-02-01T12:00:00Z")

            val policy = RetentionPolicy(keepLatest = 0, keepAnnual = 3)
            val result = computeRetention(listOf(dec2024, jan2025, anchor), policy, now, zone)

            val annualKept = result.filter { r ->
                r.retentionReasons.any { it.startsWith("annual-") }
            }

            // anchor -> 2026 bucket -> annual-1
            // jan2025 -> 2025 bucket -> annual-2
            // dec2024 -> 2024 bucket -> annual-3
            assertThat(annualKept).hasSize(3)
            assertThat(annualKept.map { it.snapshot.id }).containsExactly("anchor", "jan2025", "dec2024")

            // Neither boundary snapshot should be deleted
            val boundaryResults = result.filter { it.snapshot.id in listOf("dec2024", "jan2025") }
            assertThat(boundaryResults.all { !it.shouldDelete }).isTrue()
        }
    }

    @Nested
    @DisplayName("Leap Year Boundary")
    inner class LeapYearBoundary {

        @Test
        fun `should handle leap year Feb 28-29 boundary correctly`() {
            // 2024 is a leap year
            val feb28 = createSnapshot("feb28", Instant.parse("2024-02-28T12:00:00Z"))
            val feb29 = createSnapshot("feb29", Instant.parse("2024-02-29T12:00:00Z"))

            // Anchor snapshot on a later day so boundary days are not "current"
            val anchor = createSnapshot("anchor", Instant.parse("2024-03-05T12:00:00Z"))

            val now = Instant.parse("2024-03-10T12:00:00Z")

            // keepDaily has to reach back past Feb 28 for this to be a test of day bucketing at all:
            // Go's daily cutoff is the newest complete snapshot minus keepDaily CALENDAR days, so
            // with 5 the cutoff lands exactly on feb29 and feb28 expires for being six days old --
            // which says nothing about leap years. Seven admits both boundary days.
            val policy = RetentionPolicy(keepLatest = 0, keepDaily = 7)
            val result = computeRetention(listOf(feb28, feb29, anchor), policy, now, zone)

            val dailyKept = result.filter { r ->
                r.retentionReasons.any { it.startsWith("daily-") }
            }

            // anchor -> Mar 5 bucket -> daily-1
            // feb29 -> Feb 29 bucket -> daily-2
            // feb28 -> Feb 28 bucket -> daily-3
            assertThat(dailyKept).hasSize(3)
            assertThat(dailyKept.map { it.snapshot.id }).containsExactly("anchor", "feb29", "feb28")

            // Neither should be deleted
            val boundaryResults = result.filter { it.snapshot.id in listOf("feb28", "feb29") }
            assertThat(boundaryResults.all { !it.shouldDelete }).isTrue()
        }
    }
}
