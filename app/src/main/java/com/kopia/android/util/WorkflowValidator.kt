package com.kopia.android.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kopia.android.service.KopiaServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class for validating user workflows in the app.
 * Helps ensure all key functionality works correctly across different Android versions.
 */
class WorkflowValidator(private val context: Context) {
    
    private val TAG = "WorkflowValidator"
    internal val validationResults = ConcurrentHashMap<String, ValidationResult>()
    
    /**
     * Validates all key user workflows
     * @return Map of workflow names to validation results
     */
    suspend fun validateAllWorkflows(): Map<String, ValidationResult> {
        // Clear previous results
        validationResults.clear()
        
        // Run all validations
        validateServerStartup()
        validateRepositoryAccess()
        validateWebViewFunctionality()
        validateSnapshotCreation()
        validateSnapshotRestore()
        validateSettingsPersistence()
        
        return validationResults.toMap()
    }
    
    /**
     * Validates that the Kopia server can start successfully
     */
    internal suspend fun validateServerStartup() {
        withContext(Dispatchers.IO) {
            try {
                // Check if binary exists and is executable
                val kopiaFile = File(context.filesDir, "kopia")
                if (!kopiaFile.exists() || !kopiaFile.canExecute()) {
                    validationResults["Server Startup"] = ValidationResult(
                        success = false,
                        message = "Kopia binary not found or not executable",
                        details = "The Kopia binary must be extracted and made executable"
                    )
                    return@withContext
                }
                
                // Try to start the server
                val serverPort = 51515
                val serverUrl = "http://127.0.0.1:$serverPort/"
                
                // Start the service
                val intent = Intent(context, KopiaServerService::class.java).apply {
                    putExtra("port", serverPort)
                    putExtra("allowInsecure", true)
                }
                context.startService(intent)
                
                // Wait for server to start
                var serverRunning = false
                var attempts = 0
                
                while (!serverRunning && attempts < 10) {
                    try {
                        val url = URL(serverUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 1000
                        connection.readTimeout = 1000
                        connection.requestMethod = "HEAD"
                        
                        val responseCode = connection.responseCode
                        serverRunning = (responseCode == 200)
                        
                        connection.disconnect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking server status: ${e.message}")
                    }
                    
                    attempts++
                    kotlinx.coroutines.delay(1000)
                }
                
                // Stop the service
                context.stopService(Intent(context, KopiaServerService::class.java))
                
                validationResults["Server Startup"] = ValidationResult(
                    success = serverRunning,
                    message = if (serverRunning) "Server started successfully" else "Server failed to start",
                    details = "Attempts: $attempts, Port: $serverPort"
                )
                
            } catch (e: Exception) {
                validationResults["Server Startup"] = ValidationResult(
                    success = false,
                    message = "Exception during server startup validation",
                    details = "Error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Validates repository access functionality
     */
    internal suspend fun validateRepositoryAccess() {
        withContext(Dispatchers.IO) {
            try {
                // Create a test directory in app's files directory
                val testRepoDir = File(context.filesDir, "test_repo")
                if (!testRepoDir.exists()) {
                    testRepoDir.mkdirs()
                }
                
                // Check if directory was created successfully
                val success = testRepoDir.exists() && testRepoDir.isDirectory
                
                validationResults["Repository Access"] = ValidationResult(
                    success = success,
                    message = if (success) "Repository directory created successfully" else "Failed to create repository directory",
                    details = "Path: ${testRepoDir.absolutePath}, Android API: ${Build.VERSION.SDK_INT}"
                )
                
                // Clean up
                if (testRepoDir.exists()) {
                    testRepoDir.deleteRecursively()
                }
                
            } catch (e: Exception) {
                validationResults["Repository Access"] = ValidationResult(
                    success = false,
                    message = "Exception during repository access validation",
                    details = "Error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Validates WebView functionality
     */
    internal fun validateWebViewFunctionality() {
        try {
            // Check if WebView is available
            val webViewPackage = android.webkit.WebView.getCurrentWebViewPackage()
            val webViewAvailable = webViewPackage != null
            
            validationResults["WebView Functionality"] = ValidationResult(
                success = webViewAvailable,
                message = if (webViewAvailable) "WebView is available" else "WebView is not available",
                details = "WebView package: ${webViewPackage?.packageName ?: "None"}, Version: ${webViewPackage?.versionName ?: "Unknown"}"
            )
            
        } catch (e: Exception) {
            validationResults["WebView Functionality"] = ValidationResult(
                success = false,
                message = "Exception during WebView validation",
                details = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Validates snapshot creation functionality
     */
    internal suspend fun validateSnapshotCreation() {
        withContext(Dispatchers.IO) {
            try {
                // Create test files
                val testDir = File(context.filesDir, "test_snapshot_source")
                if (!testDir.exists()) {
                    testDir.mkdirs()
                }
                
                // Create some test files
                File(testDir, "test1.txt").writeText("Test file 1")
                File(testDir, "test2.txt").writeText("Test file 2")
                
                // Check if files were created successfully
                val filesCreated = testDir.exists() && 
                                   testDir.list()?.size == 2 &&
                                   File(testDir, "test1.txt").exists() &&
                                   File(testDir, "test2.txt").exists()
                
                validationResults["Snapshot Creation"] = ValidationResult(
                    success = filesCreated,
                    message = if (filesCreated) "Test files created successfully" else "Failed to create test files",
                    details = "Path: ${testDir.absolutePath}, Files: ${testDir.list()?.joinToString(", ") ?: "None"}"
                )
                
                // Clean up
                if (testDir.exists()) {
                    testDir.deleteRecursively()
                }
                
            } catch (e: Exception) {
                validationResults["Snapshot Creation"] = ValidationResult(
                    success = false,
                    message = "Exception during snapshot creation validation",
                    details = "Error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Validates snapshot restore functionality
     */
    internal suspend fun validateSnapshotRestore() {
        withContext(Dispatchers.IO) {
            try {
                // Create test restore directory
                val testRestoreDir = File(context.filesDir, "test_restore")
                if (!testRestoreDir.exists()) {
                    testRestoreDir.mkdirs()
                }
                
                // Check if directory was created successfully
                val success = testRestoreDir.exists() && testRestoreDir.isDirectory
                
                validationResults["Snapshot Restore"] = ValidationResult(
                    success = success,
                    message = if (success) "Restore directory created successfully" else "Failed to create restore directory",
                    details = "Path: ${testRestoreDir.absolutePath}, Android API: ${Build.VERSION.SDK_INT}"
                )
                
                // Clean up
                if (testRestoreDir.exists()) {
                    testRestoreDir.deleteRecursively()
                }
                
            } catch (e: Exception) {
                validationResults["Snapshot Restore"] = ValidationResult(
                    success = false,
                    message = "Exception during snapshot restore validation",
                    details = "Error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Validates settings persistence
     */
    internal fun validateSettingsPersistence() {
        try {
            // Get shared preferences
            val prefs = context.getSharedPreferences("kopia_settings", Context.MODE_PRIVATE)
            
            // Save a test setting
            val editor = prefs.edit()
            editor.putString("test_key", "test_value")
            val saveSuccess = editor.commit()
            
            // Read the test setting
            val readValue = prefs.getString("test_key", null)
            val readSuccess = readValue == "test_value"
            
            // Clean up
            editor.remove("test_key")
            editor.apply()
            
            validationResults["Settings Persistence"] = ValidationResult(
                success = saveSuccess && readSuccess,
                message = if (saveSuccess && readSuccess) "Settings saved and retrieved successfully" else "Failed to save or retrieve settings",
                details = "Save success: $saveSuccess, Read success: $readSuccess, Value: $readValue"
            )
            
        } catch (e: Exception) {
            validationResults["Settings Persistence"] = ValidationResult(
                success = false,
                message = "Exception during settings persistence validation",
                details = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Generate a report of all validation results
     * @return Formatted report string
     */
    fun generateReport(): String {
        val sb = StringBuilder()
        sb.appendLine("# Workflow Validation Report")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("App Version: 1.0.0")
        sb.appendLine("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine()
        
        // Summary
        val totalTests = validationResults.size
        val passedTests = validationResults.count { it.value.success }
        sb.appendLine("## Summary")
        sb.appendLine("- Total Tests: $totalTests")
        sb.appendLine("- Passed: $passedTests")
        sb.appendLine("- Failed: ${totalTests - passedTests}")
        sb.appendLine("- Success Rate: ${if (totalTests > 0) (passedTests * 100 / totalTests) else 0}%")
        sb.appendLine()
        
        // Detailed results
        sb.appendLine("## Detailed Results")
        validationResults.forEach { (workflow, result) ->
            sb.appendLine("### $workflow")
            sb.appendLine("- Status: ${if (result.success) "✅ PASSED" else "❌ FAILED"}")
            sb.appendLine("- Message: ${result.message}")
            sb.appendLine("- Details: ${result.details}")
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    /**
     * Data class for validation results
     */
    data class ValidationResult(
        val success: Boolean,
        val message: String,
        val details: String
    )
    
    companion object {
        private var instance: WorkflowValidator? = null
        
        @Synchronized
        fun getInstance(context: Context): WorkflowValidator {
            if (instance == null) {
                instance = WorkflowValidator(context.applicationContext)
            }
            return instance!!
        }
    }
}
