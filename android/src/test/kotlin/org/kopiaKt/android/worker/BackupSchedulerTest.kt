package org.kopiaKt.android.worker

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for BackupScheduler.
 *
 * Uses a WorkSchedulingPort interface to avoid Robolectric/WorkManager dependencies,
 * enabling fast pure-JVM unit tests with MockK.
 *
 * Tests are organized into four categories:
 * - Scheduling (4 tests)
 * - Source Integration (4 tests)
 * - Multi-Source (2 tests)
 * - Edge Cases (2 tests)
 */
class BackupSchedulerTest {

    private lateinit var schedulingPort: WorkSchedulingPort
    private lateinit var sourceManager: BackupSourceManager
    private lateinit var scheduler: BackupScheduler

    @BeforeEach
    fun setup() {
        schedulingPort = mockk(relaxed = true)
        sourceManager = BackupSourceManager()
        scheduler = BackupScheduler(schedulingPort, sourceManager)
    }

    @Nested
    @DisplayName("Scheduling")
    inner class SchedulingTests {

        @Test
        @DisplayName("schedulePeriodicBackup delegates to WorkSchedulingPort")
        fun `schedulePeriodicBackup delegates to WorkSchedulingPort`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")

            scheduler.schedulePeriodicBackup(
                sourceId = source.id,
                intervalHours = 24,
                constraints = BackupConstraints(),
            )

            verify {
                schedulingPort.schedulePeriodic(
                    sourceId = source.id,
                    sourcePath = "/test/path",
                    intervalHours = 24,
                    constraints = BackupConstraints(),
                )
            }
        }

        @Test
        @DisplayName("scheduleOneTimeBackup delegates to WorkSchedulingPort")
        fun `scheduleOneTimeBackup delegates to WorkSchedulingPort`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")

            scheduler.scheduleOneTimeBackup(
                sourceId = source.id,
                constraints = BackupConstraints(),
            )

            verify {
                schedulingPort.scheduleOneTime(
                    sourceId = source.id,
                    sourcePath = "/test/path",
                    constraints = BackupConstraints(),
                )
            }
        }

        @Test
        @DisplayName("interval below 15 minutes is clamped to minimum 1 hour")
        fun `interval below minimum is clamped`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")

            // WorkManager minimum for periodic is 15 minutes, but we use hours.
            // The minimum meaningful periodic interval is 1 hour.
            // Passing 0 or negative should be clamped to 1.
            scheduler.schedulePeriodicBackup(
                sourceId = source.id,
                intervalHours = 0,
                constraints = BackupConstraints(),
            )

            verify {
                schedulingPort.schedulePeriodic(
                    sourceId = source.id,
                    sourcePath = "/test/path",
                    intervalHours = 1,
                    constraints = BackupConstraints(),
                )
            }
        }

        @Test
        @DisplayName("constraints are passed through to scheduling port")
        fun `constraints passed through to scheduling port`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")
            val constraints = BackupConstraints(
                requiresCharging = true,
                requiresWifi = false,
                requiresBatteryNotLow = false,
                requiresDeviceIdle = true,
                requiresStorageNotLow = false,
            )

            scheduler.schedulePeriodicBackup(
                sourceId = source.id,
                intervalHours = 6,
                constraints = constraints,
            )

            verify {
                schedulingPort.schedulePeriodic(
                    sourceId = source.id,
                    sourcePath = "/test/path",
                    intervalHours = 6,
                    constraints = constraints,
                )
            }
        }
    }

    @Nested
    @DisplayName("Source Integration")
    inner class SourceIntegrationTests {

        @Test
        @DisplayName("pauseSource cancels scheduled work and updates source state to PAUSED")
        fun `pauseSource cancels scheduled work and updates source state`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")

            scheduler.pauseSource(source.id)

            verify { schedulingPort.cancel(source.id) }
            val updated = sourceManager.getSource(source.id)
            assertThat(updated).isNotNull()
            assertThat(updated!!.status).isEqualTo(SourceStatus.PAUSED)
        }

        @Test
        @DisplayName("resumeSource re-creates work and updates source state to IDLE")
        fun `resumeSource re-creates work and updates source state`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")
            sourceManager.pauseSource(source.id) // Start in paused state

            scheduler.resumeSource(
                sourceId = source.id,
                intervalHours = 12,
                constraints = BackupConstraints(requiresCharging = true),
            )

            verify {
                schedulingPort.schedulePeriodic(
                    sourceId = source.id,
                    sourcePath = "/test/path",
                    intervalHours = 12,
                    constraints = BackupConstraints(requiresCharging = true),
                )
            }
            val updated = sourceManager.getSource(source.id)
            assertThat(updated).isNotNull()
            assertThat(updated!!.status).isEqualTo(SourceStatus.IDLE)
        }

        @Test
        @DisplayName("updateSchedule cancels old work and creates new schedule")
        fun `updateSchedule cancels old and creates new`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")

            scheduler.updateSchedule(
                sourceId = source.id,
                intervalHours = 48,
                constraints = BackupConstraints(requiresWifi = false),
            )

            verifyOrder {
                schedulingPort.cancel(source.id)
                schedulingPort.schedulePeriodic(
                    sourceId = source.id,
                    sourcePath = "/test/path",
                    intervalHours = 48,
                    constraints = BackupConstraints(requiresWifi = false),
                )
            }
        }

        @Test
        @DisplayName("cancelScheduledBackup delegates cancel to scheduling port")
        fun `cancelScheduledBackup delegates cancel`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")
            scheduler.schedulePeriodicBackup(source.id, 24)

            scheduler.cancelScheduledBackup(source.id)

            verify { schedulingPort.cancel(source.id) }
        }
    }

    @Nested
    @DisplayName("Multi-Source")
    inner class MultiSourceTests {

        @Test
        @DisplayName("multiple sources have independent schedules")
        fun `multiple sources have independent schedules`() {
            val source1 = sourceManager.createSource("/path/1", "/path/1", "Source 1")
            val source2 = sourceManager.createSource("/path/2", "/path/2", "Source 2")
            val source3 = sourceManager.createSource("/path/3", "/path/3", "Source 3")

            scheduler.schedulePeriodicBackup(source1.id, 6)
            scheduler.schedulePeriodicBackup(source2.id, 12)
            scheduler.schedulePeriodicBackup(source3.id, 24)

            verify {
                schedulingPort.schedulePeriodic(
                    sourceId = source1.id,
                    sourcePath = "/path/1",
                    intervalHours = 6,
                    constraints = BackupConstraints(),
                )
            }
            verify {
                schedulingPort.schedulePeriodic(
                    sourceId = source2.id,
                    sourcePath = "/path/2",
                    intervalHours = 12,
                    constraints = BackupConstraints(),
                )
            }
            verify {
                schedulingPort.schedulePeriodic(
                    sourceId = source3.id,
                    sourcePath = "/path/3",
                    intervalHours = 24,
                    constraints = BackupConstraints(),
                )
            }
        }

        @Test
        @DisplayName("cancelScheduledBackup only affects target source")
        fun `cancelScheduledBackup only affects target source`() {
            val source1 = sourceManager.createSource("/path/1", "/path/1", "Source 1")
            val source2 = sourceManager.createSource("/path/2", "/path/2", "Source 2")

            scheduler.schedulePeriodicBackup(source1.id, 6)
            scheduler.schedulePeriodicBackup(source2.id, 12)

            scheduler.cancelScheduledBackup(source1.id)

            // Only source1 should be cancelled
            verify(exactly = 1) { schedulingPort.cancel(source1.id) }
            verify(exactly = 0) { schedulingPort.cancel(source2.id) }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("schedulePeriodicBackup for unknown source throws IllegalArgumentException")
        fun `schedulePeriodicBackup for unknown source throws`() {
            val exception = assertThrows<IllegalArgumentException> {
                scheduler.schedulePeriodicBackup("non-existent-id", 24)
            }

            assertThat(exception.message).contains("non-existent-id")
        }

        @Test
        @DisplayName("hasActiveSchedule returns false after cancel")
        fun `hasActiveSchedule returns false after cancel`() {
            val source = sourceManager.createSource("/test/path", "/test/path", "Test")

            scheduler.schedulePeriodicBackup(source.id, 24)
            assertThat(scheduler.hasActiveSchedule(source.id)).isTrue()

            scheduler.cancelScheduledBackup(source.id)
            assertThat(scheduler.hasActiveSchedule(source.id)).isFalse()
        }
    }
}
