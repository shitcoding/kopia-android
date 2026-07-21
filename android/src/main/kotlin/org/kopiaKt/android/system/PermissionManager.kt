package org.kopiaKt.android.system

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * Unified permission manager for Android backup functionality.
 *
 * Coordinates:
 * - Storage permissions (legacy and scoped storage)
 * - Notification permissions (Android 13+)
 * - Battery optimization exemptions
 * - Network state access
 *
 * Provides helpers for:
 * - Checking permission status
 * - Requesting permissions
 * - Opening system settings
 * - Handling permission results
 */
class PermissionManager(private val context: Context) {

    private val batteryChecker by lazy { BatteryOptimizationChecker(context) }

    /**
     * All permission states for backup functionality.
     */
    data class BackupPermissionState(
        /** Whether storage permission is granted (or not needed) */
        val hasStoragePermission: Boolean,
        /** Whether notification permission is granted (Android 13+) */
        val hasNotificationPermission: Boolean,
        /** Whether exempt from battery optimization */
        val isExemptFromBatteryOptimization: Boolean,
        /** Whether all required permissions are granted */
        val hasAllRequiredPermissions: Boolean,
        /** List of missing permissions that should be requested */
        val missingPermissions: List<Permission>,
    )

    /**
     * Permission types that can be requested.
     */
    enum class Permission {
        /** Legacy external storage read/write (API < 30) */
        STORAGE,

        /** Post notifications (API 33+) */
        NOTIFICATIONS,

        /** Battery optimization exemption */
        BATTERY_OPTIMIZATION,
    }

    /**
     * Checks if storage permission is granted.
     *
     * On Android 10 (API 29) and below: needs READ/WRITE_EXTERNAL_STORAGE
     * On Android 11+ (API 30+): Scoped storage, no permission needed for app-specific directories
     *
     * Note: For SAF access, use SafPermissionManager instead.
     */
    fun hasStoragePermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            // Android 11+ uses scoped storage, no permission needed for app dirs
            true
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            // Android 10: Read permission only (requestLegacyExternalStorage in manifest)
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
        else -> {
            // Android 9 and below: Need both read and write
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks if notification permission is granted.
     *
     * On Android 13+ (API 33+): Requires POST_NOTIFICATIONS permission
     * On earlier versions: Always granted (via manifest)
     */
    fun hasNotificationPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        // Pre-Android 13: Check if notifications are enabled in system settings
        context.getSystemService<NotificationManager>()?.areNotificationsEnabled() == true
    }

    /**
     * Checks if the app can post notifications.
     *
     * Different from hasNotificationPermission() - this checks if notifications
     * are actually enabled, including user preferences in settings.
     */
    fun canPostNotifications(): Boolean = context.getSystemService<NotificationManager>()?.areNotificationsEnabled() == true

    /**
     * Checks if exempt from battery optimization (Doze mode).
     */
    fun isExemptFromBatteryOptimization(): Boolean = batteryChecker.isExemptFromBatteryOptimization()

    /**
     * Gets the complete permission state for backup functionality.
     *
     * @param requireNotifications Whether notifications are required (true for background backups)
     */
    fun getBackupPermissionState(requireNotifications: Boolean = true): BackupPermissionState {
        val hasStorage = hasStoragePermission()
        val hasNotifications = if (requireNotifications) hasNotificationPermission() else true
        val isBatteryExempt = isExemptFromBatteryOptimization()

        val missingPermissions = mutableListOf<Permission>()
        if (!hasStorage && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            missingPermissions.add(Permission.STORAGE)
        }
        if (requireNotifications && !hasNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            missingPermissions.add(Permission.NOTIFICATIONS)
        }
        if (!isBatteryExempt) {
            missingPermissions.add(Permission.BATTERY_OPTIMIZATION)
        }

        return BackupPermissionState(
            hasStoragePermission = hasStorage,
            hasNotificationPermission = hasNotifications,
            isExemptFromBatteryOptimization = isBatteryExempt,
            hasAllRequiredPermissions = hasStorage && hasNotifications,
            missingPermissions = missingPermissions,
        )
    }

    /**
     * Gets the list of runtime permissions to request.
     *
     * Returns only permissions that require runtime request (not settings pages).
     */
    fun getPermissionsToRequest(): Array<String> {
        val permissions = mutableListOf<String>()

        // Storage permission (only on older Android)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!hasStoragePermission()) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!hasStoragePermission()) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        return permissions.toTypedArray()
    }

    /**
     * Requests runtime permissions.
     *
     * @param activity The activity context for showing the permission dialog
     * @param requestCode The request code for onRequestPermissionsResult
     */
    fun requestPermissions(activity: Activity, requestCode: Int) {
        val permissions = getPermissionsToRequest()
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissions, requestCode)
        }
    }

    /**
     * Checks if any permission rationale should be shown.
     *
     * @param activity The activity context
     * @return Map of permission to whether rationale should be shown
     */
    fun shouldShowPermissionRationale(activity: Activity): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()

        getPermissionsToRequest().forEach { permission ->
            result[permission] = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

        return result
    }

    /**
     * Creates an intent to open the app's notification settings.
     */
    fun createNotificationSettingsIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        createAppSettingsIntent()
    }

    /**
     * Creates an intent to open the app's settings page.
     */
    fun createAppSettingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

    /**
     * Creates an intent to request battery optimization exemption.
     *
     * This shows a system dialog asking the user to exempt the app.
     */
    fun createBatteryOptimizationExemptionIntent(): Intent? = batteryChecker.createRequestExemptionIntent()

    /**
     * Creates an intent to open battery optimization settings.
     *
     * This opens the settings page where user can manually toggle exemption.
     */
    fun createBatteryOptimizationSettingsIntent(): Intent = batteryChecker.createBatteryOptimizationSettingsIntent()

    /**
     * Handles permission request results.
     *
     * @param requestCode The request code from onRequestPermissionsResult
     * @param permissions The permissions array
     * @param grantResults The grant results array
     * @return Map of permission to whether it was granted
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Map<String, Boolean> = permissions.zip(grantResults.toList()).associate { (permission, result) ->
        permission to (result == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * Gets a user-friendly description for a permission.
     */
    fun getPermissionDescription(permission: Permission): PermissionDescription = when (permission) {
        Permission.STORAGE -> PermissionDescription(
            title = "Storage Access",
            description = "Required to access files for backup on older Android versions.",
            rationale = "KopiaKt needs storage access to back up your files. On Android 11+, you can use the Storage Access Framework to grant access to specific folders instead.",
        )
        Permission.NOTIFICATIONS -> PermissionDescription(
            title = "Notifications",
            description = "Shows backup progress and completion status.",
            rationale = "Notifications let you know when backups start, their progress, and whether they completed successfully or encountered errors.",
        )
        Permission.BATTERY_OPTIMIZATION -> PermissionDescription(
            title = "Battery Optimization",
            description = "Allows backups to run reliably in the background.",
            rationale = "Without battery optimization exemption, Android may stop backups to save battery. This is especially important for large backups or when backing up over slower connections.",
        )
    }

    companion object {
        /** Request code for permission requests */
        const val REQUEST_CODE_PERMISSIONS = 1001
    }
}

/**
 * Description of a permission for display to users.
 */
data class PermissionDescription(
    /** Short title for the permission */
    val title: String,
    /** Brief description of why it's needed */
    val description: String,
    /** Detailed rationale for showing when user denies */
    val rationale: String,
)
