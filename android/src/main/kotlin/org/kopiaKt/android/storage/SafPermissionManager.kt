package org.kopiaKt.android.storage

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore instance for persisted URI preferences.
 */
private val Context.safUriDataStore: DataStore<Preferences> by preferencesDataStore(name = "saf_uris")

/**
 * Manages Storage Access Framework (SAF) permissions and URI persistence.
 *
 * Android requires explicit permission persistence for SAF URIs to survive
 * app restarts. This class handles:
 * - Taking and releasing persistent permissions
 * - Storing metadata about persisted URIs (e.g., display names)
 * - Checking permission status
 * - Cleaning up stale permissions
 */
class SafPermissionManager(private val context: Context) {

    private val contentResolver = context.contentResolver

    /**
     * Data class representing a persisted storage location.
     */
    data class PersistedStorage(
        /**
         * The tree URI for the storage location.
         */
        val uri: Uri,

        /**
         * User-friendly display name for this storage.
         */
        val displayName: String,

        /**
         * Whether the app has read permission.
         */
        val hasReadPermission: Boolean,

        /**
         * Whether the app has write permission.
         */
        val hasWritePermission: Boolean,

        /**
         * Timestamp when permission was granted (milliseconds since epoch).
         */
        val persistedTime: Long
    ) {
        /**
         * Whether the storage can be used for backups (requires both read and write).
         */
        val isUsableForBackup: Boolean
            get() = hasReadPermission && hasWritePermission

        /**
         * Whether the storage can be used for restores (requires read only).
         */
        val isUsableForRestore: Boolean
            get() = hasReadPermission
    }

    companion object {
        private val KEY_PERSISTED_URIS = stringSetPreferencesKey("persisted_uris")
        private val KEY_URI_DISPLAY_NAMES = stringSetPreferencesKey("uri_display_names")

        /**
         * Required intent flags for taking persistent permissions.
         */
        const val INTENT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /**
         * Creates an intent to pick a storage location for backup.
         */
        fun createPickDirectoryIntent(): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(INTENT_FLAGS)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }
    }

    /**
     * Takes persistent permissions for a URI and saves metadata.
     *
     * Call this after receiving a result from ACTION_OPEN_DOCUMENT_TREE.
     *
     * @param uri The tree URI from the picker result
     * @param displayName User-friendly name to associate with this storage
     * @return The persisted storage info, or null if permission couldn't be taken
     */
    suspend fun takePermission(uri: Uri, displayName: String? = null): PersistedStorage? {
        return try {
            // Take the persistent permission
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            // Save to our preferences
            val actualDisplayName = displayName ?: extractDisplayName(uri)
            saveUriMetadata(uri, actualDisplayName)

            // Return the persisted storage info
            getPersistedStorage(uri)
        } catch (e: SecurityException) {
            // Permission couldn't be taken
            null
        }
    }

    /**
     * Releases persistent permission for a URI.
     *
     * @param uri The tree URI to release
     */
    suspend fun releasePermission(uri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Already released or never had permission
        }

        // Remove from our preferences
        removeUriMetadata(uri)
    }

    /**
     * Gets information about a specific persisted storage location.
     *
     * @param uri The tree URI to check
     * @return Storage info or null if not persisted
     */
    suspend fun getPersistedStorage(uri: Uri): PersistedStorage? {
        val permission = contentResolver.persistedUriPermissions.find { it.uri == uri }
            ?: return null

        val displayName = getDisplayName(uri) ?: extractDisplayName(uri)

        return PersistedStorage(
            uri = uri,
            displayName = displayName,
            hasReadPermission = permission.isReadPermission,
            hasWritePermission = permission.isWritePermission,
            persistedTime = permission.persistedTime
        )
    }

    /**
     * Gets all currently persisted storage locations.
     */
    suspend fun getAllPersistedStorages(): List<PersistedStorage> {
        return contentResolver.persistedUriPermissions.mapNotNull { permission ->
            val displayName = getDisplayName(permission.uri) ?: extractDisplayName(permission.uri)

            PersistedStorage(
                uri = permission.uri,
                displayName = displayName,
                hasReadPermission = permission.isReadPermission,
                hasWritePermission = permission.isWritePermission,
                persistedTime = permission.persistedTime
            )
        }
    }

    /**
     * Observes changes to persisted storage locations.
     */
    fun observePersistedStorages(): Flow<List<PersistedStorage>> {
        return context.safUriDataStore.data.map {
            getAllPersistedStorages()
        }
    }

    /**
     * Checks if we have valid read/write permissions for a URI.
     *
     * @param uri The tree URI to check
     * @param requireWrite If true, also require write permission
     * @return True if the required permissions are available
     */
    fun hasPermission(uri: Uri, requireWrite: Boolean = false): Boolean {
        val permission = contentResolver.persistedUriPermissions.find { it.uri == uri }
            ?: return false

        return permission.isReadPermission && (!requireWrite || permission.isWritePermission)
    }

    /**
     * Checks if a permission is likely stale (URI no longer accessible).
     *
     * This can happen when:
     * - External storage is unmounted
     * - USB device is disconnected
     * - Cloud provider is unavailable
     *
     * @param uri The tree URI to check
     * @return True if the permission appears stale
     */
    fun isPermissionStale(uri: Uri): Boolean {
        if (!hasPermission(uri)) return true

        return try {
            // Try to query the URI to check if it's still accessible
            val cursor = contentResolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )
            val isAccessible = cursor?.use { it.count > 0 } ?: false
            !isAccessible
        } catch (_: SecurityException) {
            true // the persisted grant was revoked -> genuinely stale
        } catch (_: java.io.FileNotFoundException) {
            true // the backing document was removed -> genuinely stale
        } catch (_: Exception) {
            // Any OTHER failure (a transient ContentResolver/provider hiccup, RemoteException, ...) must
            // NOT be treated as stale — the caller would release the persisted permission, and the user
            // would then have to re-pick the backup folder. Keep the permission; a truly-revoked grant
            // fails later at actual use with a SecurityException. See task-14.
            false
        }
    }

    /**
     * Cleans up stale permissions that are no longer accessible.
     *
     * @return List of URIs that were cleaned up
     */
    suspend fun cleanupStalePermissions(): List<Uri> {
        val staleUris = mutableListOf<Uri>()

        for (permission in contentResolver.persistedUriPermissions) {
            if (isPermissionStale(permission.uri)) {
                staleUris.add(permission.uri)
                releasePermission(permission.uri)
            }
        }

        return staleUris
    }

    /**
     * Updates the display name for a persisted URI.
     */
    suspend fun updateDisplayName(uri: Uri, displayName: String) {
        saveUriMetadata(uri, displayName)
    }

    /**
     * Gets the stored display name for a URI.
     */
    private suspend fun getDisplayName(uri: Uri): String? {
        val preferences = context.safUriDataStore.data.first()
        val displayNames = preferences[KEY_URI_DISPLAY_NAMES] ?: emptySet()

        return displayNames.find { it.startsWith("${uri}|") }
            ?.substringAfter("|")
    }

    /**
     * Saves URI metadata to DataStore.
     */
    private suspend fun saveUriMetadata(uri: Uri, displayName: String) {
        context.safUriDataStore.edit { preferences ->
            val currentUris = preferences[KEY_PERSISTED_URIS]?.toMutableSet() ?: mutableSetOf()
            currentUris.add(uri.toString())
            preferences[KEY_PERSISTED_URIS] = currentUris

            val currentNames = preferences[KEY_URI_DISPLAY_NAMES]?.toMutableSet() ?: mutableSetOf()
            // Remove old entry if exists
            currentNames.removeAll { it.startsWith("${uri}|") }
            currentNames.add("${uri}|${displayName}")
            preferences[KEY_URI_DISPLAY_NAMES] = currentNames
        }
    }

    /**
     * Removes URI metadata from DataStore.
     */
    private suspend fun removeUriMetadata(uri: Uri) {
        context.safUriDataStore.edit { preferences ->
            val currentUris = preferences[KEY_PERSISTED_URIS]?.toMutableSet() ?: mutableSetOf()
            currentUris.remove(uri.toString())
            preferences[KEY_PERSISTED_URIS] = currentUris

            val currentNames = preferences[KEY_URI_DISPLAY_NAMES]?.toMutableSet() ?: mutableSetOf()
            currentNames.removeAll { it.startsWith("${uri}|") }
            preferences[KEY_URI_DISPLAY_NAMES] = currentNames
        }
    }

    /**
     * Extracts a display name from a URI.
     */
    private fun extractDisplayName(uri: Uri): String {
        // Try to get the last path segment
        val path = uri.path ?: return uri.toString()

        // Extract meaningful name from path
        // Common patterns:
        // - content://com.android.externalstorage.documents/tree/primary%3AKopia
        // - content://com.android.externalstorage.documents/tree/1234-5678%3ABackup
        return try {
            val decoded = Uri.decode(path)
            val parts = decoded.split(":")
            if (parts.size > 1) {
                val volumeId = parts[0].substringAfterLast("/")
                val folderPath = parts.getOrNull(1) ?: ""

                when {
                    volumeId == "primary" -> "Internal Storage${if (folderPath.isNotEmpty()) "/$folderPath" else ""}"
                    volumeId.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}")) -> "SD Card${if (folderPath.isNotEmpty()) "/$folderPath" else ""}"
                    else -> folderPath.ifEmpty { volumeId }
                }
            } else {
                decoded.substringAfterLast("/")
            }
        } catch (_: Exception) {
            uri.lastPathSegment ?: uri.toString()
        }
    }
}
