package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MaintenanceScheduleTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `empty schedule has no runs`() {
        val schedule = MaintenanceSchedule.Empty

        assertThat(schedule.nextFullMaintenanceTime).isNull()
        assertThat(schedule.nextQuickMaintenanceTime).isNull()
        assertThat(schedule.runs).isEmpty()
    }

    @Test
    fun `runsFor returns empty list for unknown task`() {
        val schedule = MaintenanceSchedule.Empty

        assertThat(schedule.runsFor(TaskType.SNAPSHOT_GC)).isEmpty()
    }

    @Test
    fun `withRun adds run info`() {
        val schedule = MaintenanceSchedule.Empty
        val now = Instant.now()
        val runInfo = RunInfo(
            start = now.minusSeconds(60),
            end = now,
            success = true
        )

        val updated = schedule.withRun(TaskType.SNAPSHOT_GC, runInfo)

        assertThat(updated.runsFor(TaskType.SNAPSHOT_GC)).hasSize(1)
        assertThat(updated.runsFor(TaskType.SNAPSHOT_GC)[0].success).isTrue()
    }

    @Test
    fun `withRun limits history`() {
        var schedule = MaintenanceSchedule.Empty
        val now = Instant.now()

        // Add more than max history
        for (i in 0 until 15) {
            val runInfo = RunInfo(
                start = now.minusSeconds((i * 60 + 60).toLong()),
                end = now.minusSeconds((i * 60).toLong()),
                success = true
            )
            schedule = schedule.withRun(TaskType.SNAPSHOT_GC, runInfo, maxHistory = 10)
        }

        assertThat(schedule.runsFor(TaskType.SNAPSHOT_GC)).hasSize(10)
    }

    @Test
    fun `lastSuccessfulRun returns most recent success`() {
        var schedule = MaintenanceSchedule.Empty
        val now = Instant.now()

        // Add a success, then a failure
        val successRun = RunInfo(
            start = now.minusSeconds(120),
            end = now.minusSeconds(60),
            success = true
        )
        val failRun = RunInfo(
            start = now.minusSeconds(60),
            end = now,
            success = false,
            error = "Failed"
        )

        schedule = schedule.withRun(TaskType.SNAPSHOT_GC, successRun)
        schedule = schedule.withRun(TaskType.SNAPSHOT_GC, failRun)

        val lastSuccess = schedule.lastSuccessfulRun(TaskType.SNAPSHOT_GC)
        assertThat(lastSuccess).isNotNull()
        assertThat(lastSuccess!!.success).isTrue()
    }

    @Test
    fun `determineMode returns NONE when not scheduled`() {
        val now = Instant.now()
        val schedule = MaintenanceSchedule(
            nextFullMaintenanceTime = now.plusSeconds(3600),
            nextQuickMaintenanceTime = now.plusSeconds(600)
        )
        val params = MaintenanceParams()

        val mode = schedule.determineMode(now, params)

        assertThat(mode).isEqualTo(MaintenanceMode.NONE)
    }

    @Test
    fun `determineMode returns FULL when full maintenance due`() {
        val now = Instant.now()
        val schedule = MaintenanceSchedule(
            nextFullMaintenanceTime = now.minusSeconds(1),
            nextQuickMaintenanceTime = now.plusSeconds(600)
        )
        val params = MaintenanceParams()

        val mode = schedule.determineMode(now, params)

        assertThat(mode).isEqualTo(MaintenanceMode.FULL)
    }

    @Test
    fun `determineMode returns QUICK when quick maintenance due`() {
        val now = Instant.now()
        val schedule = MaintenanceSchedule(
            nextFullMaintenanceTime = now.plusSeconds(3600),
            nextQuickMaintenanceTime = now.minusSeconds(1)
        )
        val params = MaintenanceParams()

        val mode = schedule.determineMode(now, params)

        assertThat(mode).isEqualTo(MaintenanceMode.QUICK)
    }

    @Test
    fun `determineMode prefers FULL over QUICK`() {
        val now = Instant.now()
        val schedule = MaintenanceSchedule(
            nextFullMaintenanceTime = now.minusSeconds(1),
            nextQuickMaintenanceTime = now.minusSeconds(1)
        )
        val params = MaintenanceParams()

        val mode = schedule.determineMode(now, params)

        assertThat(mode).isEqualTo(MaintenanceMode.FULL)
    }

    @Test
    fun `findSafeDropTime returns null with no GC runs`() {
        val schedule = MaintenanceSchedule.Empty
        val safety = SafetyParameters.Default

        assertThat(schedule.findSafeDropTime(safety)).isNull()
    }

    @Test
    fun `findSafeDropTime returns null with only one GC run`() {
        val now = Instant.now()
        val schedule = MaintenanceSchedule.Empty.withRun(
            TaskType.SNAPSHOT_GC,
            RunInfo(start = now.minusSeconds(60), end = now, success = true)
        )
        val safety = SafetyParameters.Default

        assertThat(schedule.findSafeDropTime(safety)).isNull()
    }

    @Test
    fun `findSafeDropTime returns time with two GC runs and margin`() {
        val now = Instant.now()
        val margin = Duration.ofHours(4)

        // First GC run
        val firstRun = RunInfo(
            start = now.minusHours(5).minusMinutes(1),
            end = now.minusHours(5),
            success = true
        )
        // Second GC run with sufficient margin
        val secondRun = RunInfo(
            start = now.minusMinutes(1),
            end = now,
            success = true
        )

        var schedule = MaintenanceSchedule.Empty
            .withRun(TaskType.SNAPSHOT_GC, firstRun)
            .withRun(TaskType.SNAPSHOT_GC, secondRun)

        val safety = SafetyParameters(marginBetweenSnapshotGC = margin)
        val safeTime = schedule.findSafeDropTime(safety)

        assertThat(safeTime).isNotNull()
        assertThat(safeTime).isEqualTo(firstRun.end)
    }

    @Test
    fun `findSafeDropTime returns null when margin insufficient`() {
        val now = Instant.now()
        val margin = Duration.ofHours(4)

        // Two GC runs too close together
        val firstRun = RunInfo(
            start = now.minusHours(1).minusMinutes(1),
            end = now.minusHours(1),
            success = true
        )
        val secondRun = RunInfo(
            start = now.minusMinutes(1),
            end = now,
            success = true
        )

        val schedule = MaintenanceSchedule.Empty
            .withRun(TaskType.SNAPSHOT_GC, firstRun)
            .withRun(TaskType.SNAPSHOT_GC, secondRun)

        val safety = SafetyParameters(marginBetweenSnapshotGC = margin)
        val safeTime = schedule.findSafeDropTime(safety)

        assertThat(safeTime).isNull()
    }

    @Test
    fun `findSafeDropTime ignores failed runs`() {
        val now = Instant.now()

        val successRun = RunInfo(
            start = now.minusHours(6),
            end = now.minusHours(5),
            success = true
        )
        val failedRun = RunInfo(
            start = now.minusMinutes(1),
            end = now,
            success = false,
            error = "Failed"
        )

        val schedule = MaintenanceSchedule.Empty
            .withRun(TaskType.SNAPSHOT_GC, successRun)
            .withRun(TaskType.SNAPSHOT_GC, failedRun)

        val safety = SafetyParameters.Default
        val safeTime = schedule.findSafeDropTime(safety)

        // Only one successful run, so no safe time
        assertThat(safeTime).isNull()
    }

    @Test
    fun `serialization round-trip preserves values`() {
        val now = Instant.now()
        val schedule = MaintenanceSchedule(
            nextFullMaintenanceTime = now.plusSeconds(3600),
            nextQuickMaintenanceTime = now.plusSeconds(600),
            runs = mapOf(
                TaskType.SNAPSHOT_GC.id to listOf(
                    RunInfo(
                        start = now.minusSeconds(60),
                        end = now,
                        success = true
                    )
                )
            )
        )

        val serialized = json.encodeToString(schedule)
        val deserialized = json.decodeFromString<MaintenanceSchedule>(serialized)

        assertThat(deserialized.nextFullMaintenanceTime).isEqualTo(schedule.nextFullMaintenanceTime)
        assertThat(deserialized.nextQuickMaintenanceTime).isEqualTo(schedule.nextQuickMaintenanceTime)
        assertThat(deserialized.runs[TaskType.SNAPSHOT_GC.id]).hasSize(1)
    }

    @Test
    fun `CycleParams defaults are correct`() {
        val quick = CycleParams.QuickDefault
        val full = CycleParams.FullDefault

        assertThat(quick.enabled).isTrue()
        assertThat(quick.interval).isEqualTo(Duration.ofHours(1))

        assertThat(full.enabled).isTrue()
        assertThat(full.interval).isEqualTo(Duration.ofHours(24))
    }

    @Test
    fun `MaintenanceParams defaults are correct`() {
        val params = MaintenanceParams()

        assertThat(params.owner).isEmpty()
        assertThat(params.quickCycle).isEqualTo(CycleParams.QuickDefault)
        assertThat(params.fullCycle).isEqualTo(CycleParams.FullDefault)
        assertThat(params.extendObjectLocks).isFalse()
        assertThat(params.listParallelism).isEqualTo(1)
    }

    @Test
    fun `TaskType fromId returns correct value`() {
        assertThat(TaskType.fromId("snapshot-gc")).isEqualTo(TaskType.SNAPSHOT_GC)
        assertThat(TaskType.fromId("index-compaction")).isEqualTo(TaskType.INDEX_COMPACTION)
        assertThat(TaskType.fromId("unknown")).isNull()
    }
}

// Extension functions for test convenience
private fun Instant.minusHours(hours: Long): Instant = this.minusSeconds(hours * 3600)
private fun Instant.minusMinutes(minutes: Long): Instant = this.minusSeconds(minutes * 60)
