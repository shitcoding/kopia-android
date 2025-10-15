package com.kopia.android.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class for backing up and restoring app settings and configuration.
 * Allows users to export their settings and restore them on a new device.
 */
class SettingsBackupManager(private val context: Context) {

    private val TAG = "SettingsBackupManager"

    /**
     * Data class representing a backup of app settings
     */
    data class SettingsBackup(
        val timestamp: Long,
        val appVersion: String,
        val settings: Map<String, Map<String, Any>>
    )

    /**
     * Export all app settings to a JSON file
     * @param destinationUri The URI where the backup file should be saved
     * @return Result with the backup file path or error
     */
    suspend fun exportSettings(destinationUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting settings export")

            // Collect all settings from different SharedPreferences
            val allSettings = mutableMapOf<String, Map<String, Any>>()

            // Kopia settings
            val kopiaSettings = getSharedPreferencesAsMap("kopia_settings")
            if (kopiaSettings.isNotEmpty()) {
                allSettings["kopia_settings"] = kopiaSettings
            }

            // Backup schedule settings
            val backupSchedule = getSharedPreferencesAsMap("backup_schedule")
            if (backupSchedule.isNotEmpty()) {
                allSettings["backup_schedule"] = backupSchedule
            }

            // Create backup object
            val backup = SettingsBackup(
                timestamp = System.currentTimeMillis(),
                appVersion = getAppVersion(),
                settings = allSettings
            )

            // Convert to JSON
            val json = backupToJson(backup)

            // Write to destination
            val outputStream = context.contentResolver.openOutputStream(destinationUri)
            if (outputStream == null) {
                Log.e(TAG, "Failed to open output stream for export")
                return@withContext Result.failure(Exception("Cannot open output stream"))
            }

            outputStream.use { out ->
                out.write(json.toByteArray())
            }

            Log.i(TAG, "Settings exported successfully to $destinationUri")
            return@withContext Result.success(destinationUri.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Failed to export settings", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * Import app settings from a backup file
     * @param sourceUri The URI of the backup file
     * @return Result with success message or error
     */
    suspend fun importSettings(sourceUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting settings import from $sourceUri")

            // Read the backup file
            val inputStream = context.contentResolver.openInputStream(sourceUri)
            if (inputStream == null) {
                Log.e(TAG, "Failed to open input stream for import")
                return@withContext Result.failure(Exception("Cannot open input stream"))
            }

            val json = inputStream.use { input ->
                input.bufferedReader().readText()
            }

            // Parse JSON
            val backup = jsonToBackup(json)

            // Restore settings
            backup.settings.forEach { (prefName, settings) ->
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val editor = prefs.edit()

                settings.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        else -> Log.w(TAG, "Unsupported value type for key: $key")
                    }
                }

                editor.apply()
                Log.i(TAG, "Restored ${settings.size} settings for $prefName")
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val backupDate = dateFormat.format(Date(backup.timestamp))

            Log.i(TAG, "Settings imported successfully from backup dated $backupDate")
            return@withContext Result.success("Settings imported from backup dated $backupDate")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * Create a backup file in the app's internal storage
     * @return Result with the backup file path or error
     */
    suspend fun createLocalBackup(): Result<File> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Creating local settings backup")

            // Create backups directory if it doesn't exist
            val backupsDir = File(context.filesDir, "backups")
            if (!backupsDir.exists()) {
                backupsDir.mkdirs()
            }

            // Generate backup filename with timestamp
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val backupFile = File(backupsDir, "settings_backup_$timestamp.json")

            // Collect all settings
            val allSettings = mutableMapOf<String, Map<String, Any>>()

            val kopiaSettings = getSharedPreferencesAsMap("kopia_settings")
            if (kopiaSettings.isNotEmpty()) {
                allSettings["kopia_settings"] = kopiaSettings
            }

            val backupSchedule = getSharedPreferencesAsMap("backup_schedule")
            if (backupSchedule.isNotEmpty()) {
                allSettings["backup_schedule"] = backupSchedule
            }

            // Create backup object
            val backup = SettingsBackup(
                timestamp = System.currentTimeMillis(),
                appVersion = getAppVersion(),
                settings = allSettings
            )

            // Convert to JSON and write to file
            val json = backupToJson(backup)
            FileOutputStream(backupFile).use { out ->
                out.write(json.toByteArray())
            }

            Log.i(TAG, "Local backup created at ${backupFile.absolutePath}")
            return@withContext Result.success(backupFile)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create local backup", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * Restore settings from a local backup file
     * @param backupFile The backup file to restore from
     * @return Result with success message or error
     */
    suspend fun restoreLocalBackup(backupFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!backupFile.exists()) {
                return@withContext Result.failure(Exception("Backup file not found"))
            }

            Log.i(TAG, "Restoring from local backup: ${backupFile.absolutePath}")

            // Read backup file
            val json = FileInputStream(backupFile).use { input ->
                input.bufferedReader().readText()
            }

            // Parse and restore
            val backup = jsonToBackup(json)

            backup.settings.forEach { (prefName, settings) ->
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val editor = prefs.edit()

                settings.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                    }
                }

                editor.apply()
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val backupDate = dateFormat.format(Date(backup.timestamp))

            Log.i(TAG, "Settings restored from local backup")
            return@withContext Result.success("Settings restored from $backupDate")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore local backup", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * List all available local backups
     * @return List of backup files sorted by date (newest first)
     */
    fun listLocalBackups(): List<File> {
        val backupsDir = File(context.filesDir, "backups")
        if (!backupsDir.exists()) {
            return emptyList()
        }

        return backupsDir.listFiles { file ->
            file.isFile && file.name.startsWith("settings_backup_") && file.name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Delete a local backup file
     * @param backupFile The backup file to delete
     * @return true if deleted successfully
     */
    fun deleteLocalBackup(backupFile: File): Boolean {
        return try {
            if (backupFile.exists()) {
                val deleted = backupFile.delete()
                if (deleted) {
                    Log.i(TAG, "Deleted backup: ${backupFile.absolutePath}")
                }
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete backup", e)
            false
        }
    }

    /**
     * Get SharedPreferences as a Map
     */
    private fun getSharedPreferencesAsMap(prefName: String): Map<String, Any> {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        return prefs.all.filterValues { it != null }.mapValues { it.value!! }
    }

    /**
     * Convert backup object to JSON string
     */
    private fun backupToJson(backup: SettingsBackup): String {
        val json = JSONObject()
        json.put("timestamp", backup.timestamp)
        json.put("appVersion", backup.appVersion)

        val settingsJson = JSONObject()
        backup.settings.forEach { (prefName, settings) ->
            val prefJson = JSONObject()
            settings.forEach { (key, value) ->
                prefJson.put(key, value)
            }
            settingsJson.put(prefName, prefJson)
        }
        json.put("settings", settingsJson)

        return json.toString(2) // Pretty print with 2-space indent
    }

    /**
     * Parse JSON string to backup object
     */
    private fun jsonToBackup(json: String): SettingsBackup {
        val jsonObject = JSONObject(json)

        val timestamp = jsonObject.getLong("timestamp")
        val appVersion = jsonObject.getString("appVersion")

        val settingsMap = mutableMapOf<String, Map<String, Any>>()
        val settingsJson = jsonObject.getJSONObject("settings")

        settingsJson.keys().forEach { prefName ->
            val prefJson = settingsJson.getJSONObject(prefName)
            val prefMap = mutableMapOf<String, Any>()

            prefJson.keys().forEach { key ->
                prefMap[key] = prefJson.get(key)
            }

            settingsMap[prefName] = prefMap
        }

        return SettingsBackup(
            timestamp = timestamp,
            appVersion = appVersion,
            settings = settingsMap
        )
    }

    /**
     * Get the current app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    companion object {
        @Volatile
        private var instance: SettingsBackupManager? = null

        @Synchronized
        fun getInstance(context: Context): SettingsBackupManager {
            if (instance == null) {
                instance = SettingsBackupManager(context.applicationContext)
            }
            return instance!!
        }
    }
}
