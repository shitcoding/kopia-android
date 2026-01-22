package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MaintenanceRunnerTest {

    @Test
    fun `MaintenanceOptions has correct defaults`() {
        val options = MaintenanceOptions()

        assertThat(options.mode).isEqualTo(MaintenanceMode.AUTO)
        assertThat(options.force).isFalse()
        assertThat(options.safety).isEqualTo(SafetyParameters.Default)
        assertThat(options.gcDelete).isTrue()
        assertThat(options.onProgress).isNull()
    }

    @Test
    fun `MaintenanceOptions can be customized`() {
        var progressMessage: String? = null
        val customSafety = SafetyParameters(
            minContentAgeSubjectToGC = Duration.ofHours(48)
        )

        val options = MaintenanceOptions(
            mode = MaintenanceMode.FULL,
            force = true,
            safety = customSafety,
            gcDelete = false,
            onProgress = { progressMessage = it }
        )

        assertThat(options.mode).isEqualTo(MaintenanceMode.FULL)
        assertThat(options.force).isTrue()
        assertThat(options.safety.minContentAgeSubjectToGC).isEqualTo(Duration.ofHours(48))
        assertThat(options.gcDelete).isFalse()

        options.onProgress?.invoke("Test progress")
        assertThat(progressMessage).isEqualTo("Test progress")
    }

    @Test
    fun `MaintenanceResult tracks success`() {
        val now = Instant.now()
        val result = MaintenanceResult(
            mode = MaintenanceMode.FULL,
            success = true,
            startTime = now.minusSeconds(60),
            endTime = now
        )

        assertThat(result.mode).isEqualTo(MaintenanceMode.FULL)
        assertThat(result.success).isTrue()
        assertThat(result.error).isNull()
        assertThat(result.gcStats).isNull()
        assertThat(result.retentionDeletedCount).isEqualTo(0)
        assertThat(result.duration.seconds).isEqualTo(60)
    }

    @Test
    fun `MaintenanceResult tracks failure`() {
        val now = Instant.now()
        val result = MaintenanceResult(
            mode = MaintenanceMode.FULL,
            success = false,
            error = "Something went wrong",
            startTime = now.minusSeconds(30),
            endTime = now
        )

        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Something went wrong")
    }

    @Test
    fun `MaintenanceResult tracks GC stats`() {
        val now = Instant.now()
        val gcStats = SnapshotGCStats(
            deletedContentCount = 10,
            deletedContentSize = 1000
        )

        val result = MaintenanceResult(
            mode = MaintenanceMode.FULL,
            success = true,
            gcStats = gcStats,
            retentionDeletedCount = 5,
            startTime = now.minusSeconds(120),
            endTime = now
        )

        assertThat(result.gcStats).isNotNull()
        assertThat(result.gcStats!!.deletedContentCount).isEqualTo(10)
        assertThat(result.retentionDeletedCount).isEqualTo(5)
    }

    @Test
    fun `MaintenanceMode enum values`() {
        assertThat(MaintenanceMode.NONE.name).isEqualTo("NONE")
        assertThat(MaintenanceMode.QUICK.name).isEqualTo("QUICK")
        assertThat(MaintenanceMode.FULL.name).isEqualTo("FULL")
        assertThat(MaintenanceMode.AUTO.name).isEqualTo("AUTO")
    }

    // Note: Full integration tests for MaintenanceRunner.run() would require
    // a mock DirectRepository. Those tests would verify:
    // - Retention policy is applied to each source
    // - GC is run with correct options
    // - Schedule is updated after successful run
    // - Cancellation works correctly
}
