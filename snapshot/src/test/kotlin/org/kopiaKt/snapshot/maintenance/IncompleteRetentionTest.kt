package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.RetentionPolicy
import java.time.Instant
import java.time.ZoneId

/**
 * Incomplete snapshots accumulate from every cancelled run, and phase 3's checkpointing will write
 * one every 45 minutes — a six-hour backup leaves about eight. Getting their retention wrong means
 * either permanent litter in the user's snapshot list or reaping manifests a resume still needs, so
 * this pins Go's rule exactly (`snapshot/policy/retention_policy.go`):
 *
 * walking newest-first, an incomplete snapshot is kept while it is younger than four hours **or**
 * it is within the first three snapshots — and the walk **stops at the first complete one**, so
 * anything older than the newest complete snapshot is reaped regardless of age.
 *
 * Age is measured from the newest snapshot's start time, not from the wall clock, so the result does
 * not change while a maintenance run is in flight.
 */
class IncompleteRetentionTest {

    private val source = SourceInfo(host = "phone", userName = "local", path = "/sdcard/DCIM")
    private val base = Instant.parse("2026-07-28T12:00:00Z")
    private val policy = RetentionPolicy(keepLatest = 5)

    private fun snapshot(minutesAgo: Long, incomplete: Boolean): SnapshotManifest = SnapshotManifest(
        id = "s-$minutesAgo-${if (incomplete) "i" else "c"}",
        source = source,
        startTime = base.minusSeconds(minutesAgo * 60),
        endTime = base.minusSeconds(minutesAgo * 60 - 1),
        incompleteReason = if (incomplete) "checkpoint" else null,
    )

    private fun kept(vararg snapshots: SnapshotManifest): List<String> {
        val utc = ZoneId.of("UTC")
        val results = computeRetention(snapshots.toList(), policy, now = base, zone = utc)
        return results.filterNot { it.shouldDelete }.map { it.snapshot.id }
    }

    @Test
    fun `recent incompletes are kept`() {
        val fresh = snapshot(minutesAgo = 30, incomplete = true)
        val complete = snapshot(minutesAgo = 600, incomplete = false)

        assertThat(kept(fresh, complete)).containsExactly(fresh.id, complete.id)
    }

    @Test
    fun `the newest three incompletes are kept even when old`() {
        // Go's minimum count: without it, a long checkpointed backup interrupted overnight would
        // have every one of its checkpoints reaped before it could be resumed.
        // Spaced more than four hours apart, so only the minimum-count rule can keep them.
        val old = (0..3).map { snapshot(minutesAgo = it * 300L, incomplete = true) }

        assertThat(kept(*old.toTypedArray())).containsExactly(old[0].id, old[1].id, old[2].id)
    }

    @Test
    fun `incompletes older than the newest complete snapshot are reaped`() {
        val complete = snapshot(minutesAgo = 10, incomplete = false)
        val stale = snapshot(minutesAgo = 20, incomplete = true)

        // Once a complete snapshot exists, older checkpoints are litter -- even though this one is
        // only twenty minutes old.
        assertThat(kept(complete, stale)).containsExactly(complete.id)
    }

    @Test
    fun `age is measured from the newest snapshot, not the wall clock`() {
        // FOUR of them, an hour apart, so the last one sits past the minimum count and only the age
        // rule can save it: they are all within four hours OF EACH OTHER, but the run itself
        // finished a day ago. Measuring age against the wall clock would reap that fourth one --
        // with only three the minimum count keeps everything and the test proves nothing.
        val run = (0..3).map { snapshot(minutesAgo = it * 60L, incomplete = true) }

        val results = computeRetention(
            run,
            policy,
            now = base.plusSeconds(86_400),
            zone = ZoneId.of("UTC"),
        )

        assertThat(results.filterNot { it.shouldDelete }.map { it.snapshot.id })
            .containsExactlyElementsIn(run.map { it.id })
    }
}
