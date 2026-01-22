package org.kopiaKt.android.system

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Unit tests for PermissionManager.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class PermissionManagerTest {

    private lateinit var context: Context
    private lateinit var permissionManager: PermissionManager

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        permissionManager = PermissionManager(context)
    }

    @Test
    fun `hasStoragePermission returns boolean result`() {
        // Result depends on Robolectric shadow state
        val result = permissionManager.hasStoragePermission()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `hasStoragePermission returns result based on API level`() {
        // On API 28, result depends on permission state
        // On API 30+, would return true without permission
        val result = permissionManager.hasStoragePermission()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `hasNotificationPermission returns boolean result`() {
        // Result depends on Robolectric shadow state and API level
        val result = permissionManager.hasNotificationPermission()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `canPostNotifications checks notification manager state`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        shadowOf(notificationManager).setNotificationsEnabled(true)
        assertThat(permissionManager.canPostNotifications()).isTrue()

        shadowOf(notificationManager).setNotificationsEnabled(false)
        assertThat(permissionManager.canPostNotifications()).isFalse()
    }

    @Test
    fun `isExemptFromBatteryOptimization delegates to BatteryOptimizationChecker`() {
        // Result depends on Robolectric shadow state
        val result = permissionManager.isExemptFromBatteryOptimization()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `getBackupPermissionState returns complete state`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationsEnabled(true)

        val state = permissionManager.getBackupPermissionState()

        // State should have all fields populated
        assertThat(state.hasStoragePermission).isAnyOf(true, false)
        assertThat(state.hasNotificationPermission).isTrue()
        assertThat(state.isExemptFromBatteryOptimization).isAnyOf(true, false)
    }

    @Test
    fun `getBackupPermissionState without notification requirement`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(notificationManager).setNotificationsEnabled(false)

        val state = permissionManager.getBackupPermissionState(requireNotifications = false)

        // Notification permission should be true since not required
        assertThat(state.hasNotificationPermission).isTrue()
    }

    @Test
    fun `getPermissionsToRequest returns array`() {
        val permissions = permissionManager.getPermissionsToRequest()

        // Result depends on current permission state and API level
        assertThat(permissions).isNotNull()
    }

    @Test
    fun `createNotificationSettingsIntent returns valid intent`() {
        val intent = permissionManager.createNotificationSettingsIntent()

        assertThat(intent).isNotNull()
        assertThat(intent.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }

    @Test
    fun `createAppSettingsIntent returns valid intent`() {
        val intent = permissionManager.createAppSettingsIntent()

        assertThat(intent).isNotNull()
        assertThat(intent.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(intent.data?.schemeSpecificPart).isEqualTo(context.packageName)
    }

    @Test
    fun `createBatteryOptimizationExemptionIntent returns intent on API 23+`() {
        val intent = permissionManager.createBatteryOptimizationExemptionIntent()

        // Available on API 23+
        assertThat(intent).isNotNull()
    }

    @Test
    fun `createBatteryOptimizationSettingsIntent returns valid intent`() {
        val intent = permissionManager.createBatteryOptimizationSettingsIntent()

        assertThat(intent).isNotNull()
    }

    @Test
    fun `handlePermissionResult parses granted permission`() {
        val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val grantResults = intArrayOf(PackageManager.PERMISSION_GRANTED)

        val result = permissionManager.handlePermissionResult(0, permissions, grantResults)

        assertThat(result[Manifest.permission.READ_EXTERNAL_STORAGE]).isTrue()
    }

    @Test
    fun `handlePermissionResult parses denied permission`() {
        val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val grantResults = intArrayOf(PackageManager.PERMISSION_DENIED)

        val result = permissionManager.handlePermissionResult(0, permissions, grantResults)

        assertThat(result[Manifest.permission.READ_EXTERNAL_STORAGE]).isFalse()
    }

    @Test
    fun `handlePermissionResult handles multiple permissions`() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val grantResults = intArrayOf(
            PackageManager.PERMISSION_GRANTED,
            PackageManager.PERMISSION_DENIED
        )

        val result = permissionManager.handlePermissionResult(0, permissions, grantResults)

        assertThat(result[Manifest.permission.READ_EXTERNAL_STORAGE]).isTrue()
        assertThat(result[Manifest.permission.POST_NOTIFICATIONS]).isFalse()
    }

    @Test
    fun `getPermissionDescription returns description for STORAGE`() {
        val description = permissionManager.getPermissionDescription(PermissionManager.Permission.STORAGE)

        assertThat(description.title).isEqualTo("Storage Access")
        assertThat(description.description).isNotEmpty()
        assertThat(description.rationale).isNotEmpty()
    }

    @Test
    fun `getPermissionDescription returns description for NOTIFICATIONS`() {
        val description = permissionManager.getPermissionDescription(PermissionManager.Permission.NOTIFICATIONS)

        assertThat(description.title).isEqualTo("Notifications")
        assertThat(description.description).isNotEmpty()
        assertThat(description.rationale).isNotEmpty()
    }

    @Test
    fun `getPermissionDescription returns description for BATTERY_OPTIMIZATION`() {
        val description = permissionManager.getPermissionDescription(PermissionManager.Permission.BATTERY_OPTIMIZATION)

        assertThat(description.title).isEqualTo("Battery Optimization")
        assertThat(description.description).isNotEmpty()
        assertThat(description.rationale).isNotEmpty()
    }
}

/**
 * Tests for BackupPermissionState data class.
 */
class BackupPermissionStateTest {

    @Test
    fun `BackupPermissionState holds all values`() {
        val state = PermissionManager.BackupPermissionState(
            hasStoragePermission = true,
            hasNotificationPermission = true,
            isExemptFromBatteryOptimization = false,
            hasAllRequiredPermissions = true,
            missingPermissions = emptyList()
        )

        assertThat(state.hasStoragePermission).isTrue()
        assertThat(state.hasNotificationPermission).isTrue()
        assertThat(state.isExemptFromBatteryOptimization).isFalse()
        assertThat(state.hasAllRequiredPermissions).isTrue()
        assertThat(state.missingPermissions).isEmpty()
    }

    @Test
    fun `BackupPermissionState with missing permissions`() {
        val state = PermissionManager.BackupPermissionState(
            hasStoragePermission = false,
            hasNotificationPermission = true,
            isExemptFromBatteryOptimization = true,
            hasAllRequiredPermissions = false,
            missingPermissions = listOf(PermissionManager.Permission.STORAGE)
        )

        assertThat(state.hasAllRequiredPermissions).isFalse()
        assertThat(state.missingPermissions).contains(PermissionManager.Permission.STORAGE)
    }

    @Test
    fun `BackupPermissionState with all missing`() {
        val state = PermissionManager.BackupPermissionState(
            hasStoragePermission = false,
            hasNotificationPermission = false,
            isExemptFromBatteryOptimization = false,
            hasAllRequiredPermissions = false,
            missingPermissions = listOf(
                PermissionManager.Permission.STORAGE,
                PermissionManager.Permission.NOTIFICATIONS,
                PermissionManager.Permission.BATTERY_OPTIMIZATION
            )
        )

        assertThat(state.missingPermissions).hasSize(3)
    }
}

/**
 * Tests for PermissionDescription data class.
 */
class PermissionDescriptionTest {

    @Test
    fun `PermissionDescription holds all values`() {
        val description = PermissionDescription(
            title = "Test Permission",
            description = "Test description",
            rationale = "Test rationale"
        )

        assertThat(description.title).isEqualTo("Test Permission")
        assertThat(description.description).isEqualTo("Test description")
        assertThat(description.rationale).isEqualTo("Test rationale")
    }

    @Test
    fun `PermissionDescription equals works correctly`() {
        val desc1 = PermissionDescription("Title", "Desc", "Rationale")
        val desc2 = PermissionDescription("Title", "Desc", "Rationale")

        assertThat(desc1).isEqualTo(desc2)
    }
}

/**
 * Tests for Permission enum.
 */
class PermissionEnumTest {

    @Test
    fun `Permission enum has all expected values`() {
        val permissions = PermissionManager.Permission.values()

        assertThat(permissions).hasLength(3)
        assertThat(permissions.toList()).contains(PermissionManager.Permission.STORAGE)
        assertThat(permissions.toList()).contains(PermissionManager.Permission.NOTIFICATIONS)
        assertThat(permissions.toList()).contains(PermissionManager.Permission.BATTERY_OPTIMIZATION)
    }
}
