package com.kopia.android.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.kopia.android.util.FileUtils
import com.kopia.android.util.FilesystemPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Manager class for Kopia repository operations.
 * Supports both internal storage and external filesystem paths.
 */
class RepositoryManager(private val context: Context) {

    private val kopiaPath: String = File(context.filesDir, "kopia").absolutePath
    private val repoDir: File = File(context.filesDir, "repo")
    private val configDir: File = File(context.filesDir, ".kopia")

    /**
     * Check if a path can be accessed for repository operations.
     */
    fun canAccessPath(path: String): Boolean {
        return FilesystemPermissionManager.canAccessPath(context, path)
    }

    /**
     * Get a list of recommended repository locations.
     */
    fun getRecommendedLocations(): List<FilesystemPermissionManager.AccessibleDirectory> {
        return FilesystemPermissionManager.getAccessibleDirectories(context)
    }
    
    /**
     * Initialize a new repository at the specified location
     * 
     * @param repositoryUri The URI of the repository location
     * @return Result of the operation
     */
    suspend fun initializeRepository(repositoryUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Ensure repository directory exists
            if (!repoDir.exists()) {
                repoDir.mkdirs()
            }
            
            // Ensure config directory exists
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            
            // Build command to initialize repository
            val command = listOf(
                kopiaPath,
                "repository",
                "create",
                "filesystem",
                "--path=${repoDir.absolutePath}"
            )
            
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            processBuilder.environment().apply {
                put("HOME", context.filesDir.absolutePath)
                put("KOPIA_CONFIG_PATH", configDir.absolutePath)
            }
            
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            return@withContext if (exitCode == 0) {
                Result.success("Repository initialized successfully")
            } else {
                Result.failure(IOException("Failed to initialize repository: $output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Connect to an existing repository
     * 
     * @param repositoryUri The URI of the repository location
     * @return Result of the operation
     */
    suspend fun connectRepository(repositoryUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Ensure repository directory exists
            if (!repoDir.exists()) {
                repoDir.mkdirs()
            }
            
            // Copy repository files from SAF to internal storage if needed
            val documentFile = DocumentFile.fromTreeUri(context, repositoryUri)
            if (documentFile == null || !documentFile.exists()) {
                return@withContext Result.failure(IOException("Invalid repository location"))
            }
            
            // Build command to connect to repository
            val command = listOf(
                kopiaPath,
                "repository",
                "connect",
                "filesystem",
                "--path=${repoDir.absolutePath}"
            )
            
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            processBuilder.environment().apply {
                put("HOME", context.filesDir.absolutePath)
                put("KOPIA_CONFIG_PATH", configDir.absolutePath)
            }
            
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            return@withContext if (exitCode == 0) {
                Result.success("Connected to repository successfully")
            } else {
                Result.failure(IOException("Failed to connect to repository: $output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * List snapshots in the repository
     * 
     * @return Result containing the list of snapshots
     */
    suspend fun listSnapshots(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val command = listOf(
                kopiaPath,
                "snapshot",
                "list",
                "--json"
            )
            
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            processBuilder.environment().apply {
                put("HOME", context.filesDir.absolutePath)
                put("KOPIA_CONFIG_PATH", configDir.absolutePath)
            }
            
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            return@withContext if (exitCode == 0) {
                Result.success(output)
            } else {
                Result.failure(IOException("Failed to list snapshots: $output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Restore a snapshot to the specified location
     * 
     * @param snapshotId The ID of the snapshot to restore
     * @param targetUri The URI where to restore the snapshot
     * @return Result of the operation
     */
    suspend fun restoreSnapshot(snapshotId: String, targetUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Create a temporary directory for restoration
            val restoreDir = File(context.filesDir, "restore")
            if (restoreDir.exists()) {
                restoreDir.deleteRecursively()
            }
            restoreDir.mkdirs()
            
            // Build command to restore snapshot
            val command = listOf(
                kopiaPath,
                "snapshot",
                "restore",
                snapshotId,
                restoreDir.absolutePath
            )
            
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            processBuilder.environment().apply {
                put("HOME", context.filesDir.absolutePath)
                put("KOPIA_CONFIG_PATH", configDir.absolutePath)
            }
            
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                // Copy restored files to the target location
                val targetDoc = DocumentFile.fromTreeUri(context, targetUri)
                if (targetDoc == null || !targetDoc.exists()) {
                    return@withContext Result.failure(IOException("Invalid target location"))
                }
                
                val success = FileUtils.copyDirectoryFromInternalToSaf(context, restoreDir, targetUri)
                return@withContext if (success) {
                    Result.success("Snapshot restored successfully")
                } else {
                    Result.failure(IOException("Failed to copy restored files to target location"))
                }
            } else {
                return@withContext Result.failure(IOException("Failed to restore snapshot: $output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
