package org.kopiaKt.android

import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.kopiaKt.android.worker.BackupConstraints
import org.kopiaKt.android.worker.toWorkConstraints

/**
 * Build verification tests for the android module.
 */
class BuildVerificationTest {

    @Test
    fun `BackupConstraints has sensible defaults`() {
        val constraints = BackupConstraints()

        assertThat(constraints.requiresCharging).isFalse()
        assertThat(constraints.requiresWifi).isTrue()
        assertThat(constraints.requiresBatteryNotLow).isTrue()
        assertThat(constraints.requiresDeviceIdle).isFalse()
        assertThat(constraints.requiresStorageNotLow).isTrue()
    }

    @Test
    fun `BackupConstraints can be customized`() {
        val constraints = BackupConstraints(
            requiresCharging = true,
            requiresWifi = false,
            requiresDeviceIdle = true,
        )

        assertThat(constraints.requiresCharging).isTrue()
        assertThat(constraints.requiresWifi).isFalse()
        assertThat(constraints.requiresDeviceIdle).isTrue()
    }

    @Test
    fun `BackupConstraints converts to WorkManager Constraints correctly`() {
        val backupConstraints = BackupConstraints(
            requiresCharging = true,
            requiresWifi = true,
            requiresBatteryNotLow = true,
            requiresDeviceIdle = false,
            requiresStorageNotLow = true,
        )

        val workConstraints = backupConstraints.toWorkConstraints()

        assertThat(workConstraints.requiresCharging()).isTrue()
        assertThat(workConstraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(workConstraints.requiresBatteryNotLow()).isTrue()
        assertThat(workConstraints.requiresDeviceIdle()).isFalse()
        assertThat(workConstraints.requiresStorageNotLow()).isTrue()
    }

    @Test
    fun `BackupConstraints with requiresWifi false uses CONNECTED network type`() {
        val constraints = BackupConstraints(requiresWifi = false)
        val workConstraints = constraints.toWorkConstraints()

        assertThat(workConstraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }
}
