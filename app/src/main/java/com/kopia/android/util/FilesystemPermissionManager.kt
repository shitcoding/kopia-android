package com.kopia.android.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Manages filesystem permissions for Kopia Android.
 * Handles both traditional storage permissions and MANAGE_EXTERNAL_STORAGE for Android 11+.
 */
object FilesystemPermissionManager {

    private const val TAG = "FilesystemPermissionMgr"

    /**
     * Check if the app has full filesystem access.
     * On Android 11+, this requires MANAGE_EXTERNAL_STORAGE permission.
     * On older versions, this requires READ/WRITE_EXTERNAL_STORAGE.
     */
    fun hasFullFilesystemAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ requires MANAGE_EXTERNAL_STORAGE
            Environment.isExternalStorageManager()
        } else {
            // Android 10 and below use traditional permissions
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request full filesystem access permission.
     * On Android 11+, this opens the system settings page for MANAGE_EXTERNAL_STORAGE.
     * On older versions, the app should use traditional runtime permission requests.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun requestFilesystemPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                Log.d(TAG, "Launched MANAGE_EXTERNAL_STORAGE settings for: ${activity.packageName}")
            } catch (e: Exception) {
                // Fallback to general settings if the specific intent fails
                Log.w(TAG, "Failed to launch specific settings, trying general settings", e)
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    activity.startActivity(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to launch any settings page", e2)
                }
            }
        }
    }

    /**
     * Get a user-friendly explanation of the filesystem permission status.
     */
    fun getPermissionStatusMessage(context: Context): String {
        return if (hasFullFilesystemAccess(context)) {
            "Full filesystem access granted. You can access Kopia repositories in any directory."
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Limited access. Grant 'All files access' permission to access repositories anywhere on your device."
            } else {
                "Storage permissions required. Please grant storage access to use Kopia."
            }
        }
    }

    /**
     * Check if we can access a specific path.
     */
    fun canAccessPath(context: Context, path: String): Boolean {
        return try {
            val file = java.io.File(path)

            // Check if it's in app-specific storage (always accessible)
            val appDirs = listOf(
                context.filesDir.absolutePath,
                context.cacheDir.absolutePath,
                context.getExternalFilesDir(null)?.absolutePath,
                context.externalCacheDir?.absolutePath
            ).filterNotNull()

            if (appDirs.any { path.startsWith(it) }) {
                return true
            }

            // For other paths, check if we have full filesystem access
            if (hasFullFilesystemAccess(context)) {
                // Try to list the directory or check if file exists
                file.exists() || file.canRead()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking path access: $path", e)
            false
        }
    }

    /**
     * Get list of common accessible directories.
     */
    fun getAccessibleDirectories(context: Context): List<AccessibleDirectory> {
        val directories = mutableListOf<AccessibleDirectory>()

        // Always accessible: App internal storage
        directories.add(
            AccessibleDirectory(
                name = "App Internal Storage",
                path = context.filesDir.absolutePath,
                description = "Private app storage",
                alwaysAccessible = true
            )
        )

        // Always accessible: App cache
        directories.add(
            AccessibleDirectory(
                name = "App Cache",
                path = context.cacheDir.absolutePath,
                description = "Temporary storage",
                alwaysAccessible = true
            )
        )

        // App external storage (if available)
        context.getExternalFilesDir(null)?.let { dir ->
            directories.add(
                AccessibleDirectory(
                    name = "App External Storage",
                    path = dir.absolutePath,
                    description = "App-specific external storage",
                    alwaysAccessible = true
                )
            )
        }

        // Public directories (require full permission on Android 11+)
        if (hasFullFilesystemAccess(context)) {
            // Download directory
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && downloadsDir.exists()) {
                directories.add(
                    AccessibleDirectory(
                        name = "Downloads",
                        path = downloadsDir.absolutePath,
                        description = "Public downloads folder",
                        alwaysAccessible = false
                    )
                )
            }

            // Documents directory
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (documentsDir != null && documentsDir.exists()) {
                directories.add(
                    AccessibleDirectory(
                        name = "Documents",
                        path = documentsDir.absolutePath,
                        description = "Public documents folder",
                        alwaysAccessible = false
                    )
                )
            }

            // SD Card root
            val externalStorage = Environment.getExternalStorageDirectory()
            directories.add(
                AccessibleDirectory(
                    name = "Device Storage",
                    path = externalStorage.absolutePath,
                    description = "Root of device storage",
                    alwaysAccessible = false
                )
            )
        }

        return directories
    }

    /**
     * Data class representing an accessible directory.
     */
    data class AccessibleDirectory(
        val name: String,
        val path: String,
        val description: String,
        val alwaysAccessible: Boolean
    )
}
