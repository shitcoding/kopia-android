package com.kopia.android.util

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class BackupSchedulerTest {

    private lateinit var context: Context
    private lateinit var backupScheduler: BackupScheduler
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Initialize WorkManager for testing
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)

        // Clear any existing scheduled work
        workManager.cancelAllWork()

        // Initialize BackupScheduler
        backupScheduler = BackupScheduler.getInstance(context)

        // Clear any existing preferences
        val prefs = context.getSharedPreferences("backup_schedule", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun scheduleBackup_withDefaultConfig_shouldSucceed() {
        // Arrange
        val config = BackupScheduler.ScheduleConfig(
            enabled = true,
            intervalHours = 24,
            requireCharging = false,
            requireWifi = false
        )

        // Act
        val result = backupScheduler.scheduleBackup(config)

        // Assert
        assertThat(result).isTrue()
        assertThat(backupScheduler.isBackupScheduled()).isTrue()
    }

    @Test
    fun scheduleBackup_withDisabledConfig_shouldCancelExistingSchedule() {
        // Arrange - first schedule a backup
        val enabledConfig = BackupScheduler.ScheduleConfig(
            enabled = true,
            intervalHours = 12
        )
        backupScheduler.scheduleBackup(enabledConfig)
        assertThat(backupScheduler.isBackupScheduled()).isTrue()

        // Act - now disable it
        val disabledConfig = BackupScheduler.ScheduleConfig(enabled = false)
        backupScheduler.scheduleBackup(disabledConfig)

        // Assert
        assertThat(backupScheduler.isBackupScheduled()).isFalse()
    }

    @Test
    fun scheduleBackup_withDifferentIntervals_shouldUpdateSchedule() {
        // Arrange & Act - Schedule with 24 hours
        val config24h = BackupScheduler.ScheduleConfig(
            enabled = true,
            intervalHours = 24
        )
        backupScheduler.scheduleBackup(config24h)

        val savedConfig1 = backupScheduler.getConfig()
        assertThat(savedConfig1.intervalHours).isEqualTo(24)

        // Schedule with 12 hours
        val config12h = BackupScheduler.ScheduleConfig(
            enabled = true,
            intervalHours = 12
        )
        backupScheduler.scheduleBackup(config12h)

        val savedConfig2 = backupScheduler.getConfig()
        assertThat(savedConfig2.intervalHours).isEqualTo(12)
    }

    @Test
    fun scheduleBackup_withConstraints_shouldSaveConfiguration() {
        // Arrange
        val config = BackupScheduler.ScheduleConfig(
            enabled = true,
            intervalHours = 6,
            requireCharging = true,
            requireWifi = true,
            sourcePath = "/sdcard/test"
        )

        // Act
        backupScheduler.scheduleBackup(config)

        // Assert
        val savedConfig = backupScheduler.getConfig()
        assertThat(savedConfig.enabled).isTrue()
        assertThat(savedConfig.intervalHours).isEqualTo(6)
        assertThat(savedConfig.requireCharging).isTrue()
        assertThat(savedConfig.requireWifi).isTrue()
        assertThat(savedConfig.sourcePath).isEqualTo("/sdcard/test")
    }

    @Test
    fun cancelBackup_shouldRemoveScheduledWork() {
        // Arrange - first schedule a backup
        val config = BackupScheduler.ScheduleConfig(
            enabled = true,
            intervalHours = 24
        )
        backupScheduler.scheduleBackup(config)
        assertThat(backupScheduler.isBackupScheduled()).isTrue()

        // Act
        backupScheduler.cancelBackup()

        // Assert
        assertThat(backupScheduler.isBackupScheduled()).isFalse()
    }

    @Test
    fun getConfig_withNoSavedConfig_shouldReturnDefaults() {
        // Act
        val config = backupScheduler.getConfig()

        // Assert
        assertThat(config.enabled).isFalse()
        assertThat(config.intervalHours).isEqualTo(24)
        assertThat(config.requireCharging).isFalse()
        assertThat(config.requireWifi).isFalse()
        assertThat(config.sourcePath).isNull()
    }

    @Test
    fun getLastBackupStatus_withNoBackup_shouldReturnNull() {
        // Act
        val status = backupScheduler.getLastBackupStatus()

        // Assert
        assertThat(status).isNull()
    }

    @Test
    fun getLastBackupStatus_withSuccessfulBackup_shouldReturnSuccessMessage() {
        // Arrange - simulate a successful backup
        val prefs = context.getSharedPreferences("backup_schedule", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong("last_backup_time", System.currentTimeMillis())
            putBoolean("last_backup_success", true)
            apply()
        }

        // Act
        val status = backupScheduler.getLastBackupStatus()

        // Assert
        assertThat(status).isNotNull()
        assertThat(status).contains("Success")
    }

    @Test
    fun getLastBackupStatus_withFailedBackup_shouldReturnFailureMessage() {
        // Arrange - simulate a failed backup
        val prefs = context.getSharedPreferences("backup_schedule", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong("last_backup_time", System.currentTimeMillis())
            putBoolean("last_backup_success", false)
            apply()
        }

        // Act
        val status = backupScheduler.getLastBackupStatus()

        // Assert
        assertThat(status).isNotNull()
        assertThat(status).contains("Failed")
    }

    @Test
    fun scheduleBackup_multipleTimesWithSameConfig_shouldReplaceExisting() {
        // Arrange
        val config = BackupScheduler.ScheduleConfig(
            enabled = true,
            intervalHours = 24
        )

        // Act - schedule multiple times
        backupScheduler.scheduleBackup(config)
        backupScheduler.scheduleBackup(config)
        backupScheduler.scheduleBackup(config)

        // Assert - should only have one scheduled work
        val workInfos = workManager.getWorkInfosForUniqueWork("kopia_scheduled_backup").get()
        val activeWorks = workInfos.filter {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
        assertThat(activeWorks).hasSize(1)
    }

    @Test
    fun getInstance_shouldReturnSameInstance() {
        // Act
        val instance1 = BackupScheduler.getInstance(context)
        val instance2 = BackupScheduler.getInstance(context)

        // Assert
        assertThat(instance1).isSameInstanceAs(instance2)
    }
}
