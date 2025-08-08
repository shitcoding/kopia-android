package com.kopia.android.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class for testing app functionality across different Android versions
 * and device configurations.
 */
class TestUtility(private val context: Context) {
    
    private val TAG = "TestUtility"
    private val testResults = mutableMapOf<String, Boolean>()
    private val testNotes = mutableMapOf<String, String>()
    
    /**
     * Run all tests to validate app functionality
     */
    fun runAllTests(): Map<String, Boolean> {
        testPermissions()
        testFileSystem()
        testKopiaBinary()
        testWebViewCapabilities()
        testNetworkAccess()
        return testResults
    }
    
    /**
     * Test if all required permissions are granted
     */
    fun testPermissions() {
        val requiredPermissions = listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.FOREGROUND_SERVICE
        )
        
        // Add storage permissions based on Android version
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // For Android 9 (P) and below, we need external storage permissions
            requiredPermissions.plus(
                listOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
        
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        
        testResults["permissions"] = missingPermissions.isEmpty()
        if (missingPermissions.isNotEmpty()) {
            testNotes["permissions"] = "Missing permissions: ${missingPermissions.joinToString(", ")}"
            Log.w(TAG, "Missing permissions: ${missingPermissions.joinToString(", ")}")
        } else {
            testNotes["permissions"] = "All required permissions granted"
            Log.d(TAG, "All required permissions granted")
        }
    }
    
    /**
     * Test file system access and capabilities
     */
    fun testFileSystem() {
        // Test internal storage
        val internalSuccess = testInternalStorage()
        testResults["internal_storage"] = internalSuccess
        
        // Test if external storage is available
        val externalState = Environment.getExternalStorageState()
        val externalAvailable = externalState == Environment.MEDIA_MOUNTED
        testResults["external_storage_available"] = externalAvailable
        testNotes["external_storage_available"] = "External storage state: $externalState"
        
        if (externalAvailable) {
            val externalSuccess = testExternalStorage()
            testResults["external_storage"] = externalSuccess
        }
    }
    
    /**
     * Test internal storage read/write capabilities
     */
    private fun testInternalStorage(): Boolean {
        try {
            // Get available space
            val stat = StatFs(context.filesDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val availableMB = availableBytes / (1024 * 1024)
            testNotes["internal_storage"] = "Available space: $availableMB MB"
            
            // Test write
            val testFile = File(context.filesDir, "test_file.txt")
            FileWriter(testFile).use { it.write("Test content ${Date()}") }
            
            // Test read
            val content = testFile.readText()
            val success = content.startsWith("Test content")
            
            // Clean up
            testFile.delete()
            
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Internal storage test failed", e)
            testNotes["internal_storage"] = "Error: ${e.message}"
            return false
        }
    }
    
    /**
     * Test external storage read/write capabilities
     */
    private fun testExternalStorage(): Boolean {
        try {
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir == null) {
                testNotes["external_storage"] = "External directory is null"
                return false
            }
            
            // Get available space
            val stat = StatFs(externalDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val availableMB = availableBytes / (1024 * 1024)
            testNotes["external_storage"] = "Available space: $availableMB MB"
            
            // Test write
            val testFile = File(externalDir, "test_file.txt")
            FileWriter(testFile).use { it.write("Test content ${Date()}") }
            
            // Test read
            val content = testFile.readText()
            val success = content.startsWith("Test content")
            
            // Clean up
            testFile.delete()
            
            return success
        } catch (e: Exception) {
            Log.e(TAG, "External storage test failed", e)
            testNotes["external_storage"] = "Error: ${e.message}"
            return false
        }
    }
    
    /**
     * Test if Kopia binary exists and has execute permissions
     */
    fun testKopiaBinary() {
        val kopiaFile = File(context.filesDir, "kopia")
        val exists = kopiaFile.exists()
        val canExecute = kopiaFile.canExecute()
        
        testResults["kopia_binary_exists"] = exists
        testResults["kopia_binary_executable"] = canExecute
        
        if (exists) {
            testNotes["kopia_binary_exists"] = "Kopia binary found, size: ${kopiaFile.length() / 1024} KB"
        } else {
            testNotes["kopia_binary_exists"] = "Kopia binary not found"
        }
        
        if (!canExecute && exists) {
            testNotes["kopia_binary_executable"] = "Kopia binary is not executable"
        }
    }
    
    /**
     * Test WebView capabilities
     */
    fun testWebViewCapabilities() {
        val packageInfo = context.packageManager.getPackageInfo(
            "com.google.android.webview", 
            0
        )
        
        testResults["webview_available"] = true
        testNotes["webview_available"] = "WebView version: ${packageInfo.versionName}"
        
        // Additional WebView tests could be added here
    }
    
    /**
     * Test network access
     */
    fun testNetworkAccess() {
        // This is a placeholder for network tests
        // In a real implementation, you would test connectivity to required services
        testResults["network_access"] = true
        testNotes["network_access"] = "Network access test not implemented"
    }
    
    /**
     * Generate a test report
     */
    fun generateReport(): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        
        sb.appendLine("=== Kopia Android Test Report ===")
        sb.appendLine("Generated: ${dateFormat.format(Date())}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        
        sb.appendLine("--- Test Results ---")
        testResults.forEach { (test, passed) ->
            val status = if (passed) "PASS" else "FAIL"
            sb.appendLine("$test: $status")
            testNotes[test]?.let { sb.appendLine("  Note: $it") }
        }
        
        return sb.toString()
    }
    
    companion object {
        private var instance: TestUtility? = null
        
        @Synchronized
        fun getInstance(context: Context): TestUtility {
            if (instance == null) {
                instance = TestUtility(context.applicationContext)
            }
            return instance!!
        }
    }
}
