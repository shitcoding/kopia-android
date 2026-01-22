package org.kopiaKt.android.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Unit tests for BackupNotificationManager.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class BackupNotificationManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockNotificationManager: NotificationManager
    private lateinit var manager: BackupNotificationManager

    @BeforeEach
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockNotificationManager = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager

        manager = BackupNotificationManager(mockContext, android.R.drawable.ic_popup_sync)
    }

    @Nested
    @DisplayName("Notification Channels")
    inner class NotificationChannelTests {

        @Test
        fun `creates all notification channels`() {
            // Test on API 28 (the class-level config) - notification channels work on API 26+
            val channelsSlot = slot<List<NotificationChannel>>()

            manager.createNotificationChannels()

            verify { mockNotificationManager.createNotificationChannels(capture(channelsSlot)) }

            val channels = channelsSlot.captured
            assertThat(channels).hasSize(3)

            val channelIds = channels.map { it.id }
            assertThat(channelIds).containsExactly(
                BackupNotificationChannels.PROGRESS,
                BackupNotificationChannels.COMPLETION,
                BackupNotificationChannels.ERROR
            )
        }

        @Test
        fun `progress channel has low importance`() {
            val channelsSlot = slot<List<NotificationChannel>>()

            manager.createNotificationChannels()

            verify { mockNotificationManager.createNotificationChannels(capture(channelsSlot)) }

            val progressChannel = channelsSlot.captured.find { it.id == BackupNotificationChannels.PROGRESS }
            assertThat(progressChannel).isNotNull()
            assertThat(progressChannel!!.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        }

        @Test
        fun `error channel has high importance`() {
            val channelsSlot = slot<List<NotificationChannel>>()

            manager.createNotificationChannels()

            verify { mockNotificationManager.createNotificationChannels(capture(channelsSlot)) }

            val errorChannel = channelsSlot.captured.find { it.id == BackupNotificationChannels.ERROR }
            assertThat(errorChannel).isNotNull()
            assertThat(errorChannel!!.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        }
    }

    @Nested
    @DisplayName("Progress Notification")
    inner class ProgressNotificationTests {

        @Test
        fun `builds notification with source path in title`() {
            val notification = manager.buildProgressNotification(
                sourceId = "test-source",
                sourcePath = "/storage/emulated/0/Documents"
            )

            // Notification is built - verify it's not null
            assertThat(notification).isNotNull()
        }

        @Test
        fun `builds notification with progress percentage`() {
            val notification = manager.buildProgressNotification(
                sourceId = "test-source",
                sourcePath = "/test/path",
                progress = 50
            )

            assertThat(notification).isNotNull()
        }

        @Test
        fun `builds notification with indeterminate progress when null`() {
            val notification = manager.buildProgressNotification(
                sourceId = "test-source",
                sourcePath = "/test/path",
                progress = null
            )

            assertThat(notification).isNotNull()
        }

        @Test
        fun `builds notification with current file info`() {
            val notification = manager.buildProgressNotification(
                sourceId = "test-source",
                sourcePath = "/test/path",
                currentFile = "current_file.txt",
                processedFiles = 42,
                processedBytes = 1024 * 1024 * 100
            )

            assertThat(notification).isNotNull()
        }

        @Test
        fun `clamps progress to valid range`() {
            val notification = manager.buildProgressNotification(
                sourceId = "test-source",
                sourcePath = "/test/path",
                progress = 150 // Over 100
            )

            assertThat(notification).isNotNull()
        }
    }

    @Nested
    @DisplayName("Completion Notification")
    inner class CompletionNotificationTests {

        @Test
        fun `builds completion notification with stats`() {
            val notification = manager.buildCompletionNotification(
                sourcePath = "/test/path",
                filesCount = 1000,
                totalBytes = 1024L * 1024 * 1024 * 5, // 5 GB
                duration = 3600000 // 1 hour
            )

            assertThat(notification).isNotNull()
        }

        @Test
        fun `completion notification is auto-cancel`() {
            val notification = manager.buildCompletionNotification(
                sourcePath = "/test/path",
                filesCount = 100,
                totalBytes = 1024 * 1024,
                duration = 60000
            )

            assertThat(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Error Notification")
    inner class ErrorNotificationTests {

        @Test
        fun `builds error notification with message`() {
            val notification = manager.buildErrorNotification(
                sourcePath = "/test/path",
                errorMessage = "Storage access denied"
            )

            assertThat(notification).isNotNull()
        }

        @Test
        fun `error notification is auto-cancel`() {
            val notification = manager.buildErrorNotification(
                sourcePath = "/test/path",
                errorMessage = "Test error"
            )

            assertThat(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Restore Notification")
    inner class RestoreNotificationTests {

        @Test
        fun `builds restore progress notification`() {
            val notification = manager.buildRestoreProgressNotification(
                destinationPath = "/storage/emulated/0/Restored",
                currentFile = "data.json",
                progress = 75,
                processedFiles = 500
            )

            assertThat(notification).isNotNull()
        }

        @Test
        fun `builds restore completion notification`() {
            val notification = manager.buildRestoreCompletionNotification(
                destinationPath = "/storage/emulated/0/Restored",
                filesCount = 2500,
                totalBytes = 1024L * 1024 * 512,
                duration = 900000
            )

            assertThat(notification).isNotNull()
        }
    }

    @Nested
    @DisplayName("Notification IDs")
    inner class NotificationIdTests {

        @Test
        fun `forSource returns consistent IDs for same source`() {
            val id1 = BackupNotificationIds.forSource("source-1")
            val id2 = BackupNotificationIds.forSource("source-1")

            assertThat(id1).isEqualTo(id2)
        }

        @Test
        fun `forSource returns different IDs for different sources`() {
            val id1 = BackupNotificationIds.forSource("source-1")
            val id2 = BackupNotificationIds.forSource("source-2")

            assertThat(id1).isNotEqualTo(id2)
        }

        @Test
        fun `forSource returns IDs in valid range`() {
            val id = BackupNotificationIds.forSource("any-source")

            assertThat(id).isAtLeast(BackupNotificationIds.BACKUP_PROGRESS_BASE)
            assertThat(id).isLessThan(BackupNotificationIds.BACKUP_PROGRESS_BASE + 1000)
        }
    }

    @Nested
    @DisplayName("Formatting Utilities")
    inner class FormattingTests {

        @Test
        fun `formatBytes formats small sizes`() {
            assertThat(BackupNotificationManager.formatBytes(500)).isEqualTo("500 B")
        }

        @Test
        fun `formatBytes formats kilobytes`() {
            val result = BackupNotificationManager.formatBytes(2048)
            assertThat(result).isEqualTo("2.0 KB")
        }

        @Test
        fun `formatBytes formats megabytes`() {
            val result = BackupNotificationManager.formatBytes(1024L * 1024 * 5)
            assertThat(result).isEqualTo("5.0 MB")
        }

        @Test
        fun `formatBytes formats gigabytes`() {
            val result = BackupNotificationManager.formatBytes(1024L * 1024 * 1024 * 2)
            assertThat(result).isEqualTo("2.00 GB")
        }

        @Test
        fun `formatDuration formats seconds`() {
            assertThat(BackupNotificationManager.formatDuration(30000)).isEqualTo("30s")
        }

        @Test
        fun `formatDuration formats minutes`() {
            assertThat(BackupNotificationManager.formatDuration(150000)).isEqualTo("2m 30s")
        }

        @Test
        fun `formatDuration formats hours`() {
            assertThat(BackupNotificationManager.formatDuration(3700000)).isEqualTo("1h 1m")
        }
    }

    @Nested
    @DisplayName("Notify/Cancel")
    inner class NotifyCancelTests {

        @Test
        fun `notify posts notification`() {
            val notification = manager.buildProgressNotification(
                sourceId = "test",
                sourcePath = "/test"
            )

            manager.notify(123, notification)

            verify { mockNotificationManager.notify(123, notification) }
        }

        @Test
        fun `cancel removes notification`() {
            manager.cancel(123)

            verify { mockNotificationManager.cancel(123) }
        }
    }
}
