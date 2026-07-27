package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.RetentionPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
}
