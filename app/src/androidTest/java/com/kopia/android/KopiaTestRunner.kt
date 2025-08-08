package com.kopia.android

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.StrictMode
import androidx.test.runner.AndroidJUnitRunner

/**
 * Custom test runner for Kopia Android app tests.
 * Configures the test environment and provides additional functionality.
 */
class KopiaTestRunner : AndroidJUnitRunner() {
    
    override fun onCreate(arguments: Bundle) {
        // Disable strict mode for tests to avoid issues with file operations
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        
        super.onCreate(arguments)
    }
    
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        // Return a test application if needed, or the default application
        return super.newApplication(cl, className, context)
    }
    
    /**
     * Called before each test.
     * Use this to set up test environment, e.g., clearing shared preferences,
     * setting up mock data, etc.
     */
    override fun onStart() {
        super.onStart()
        
        // Set up test environment
        setupTestEnvironment()
    }
    
    /**
     * Called after each test.
     * Use this to clean up test environment.
     */
    override fun onDestroy() {
        // Clean up test environment
        cleanupTestEnvironment()
        
        super.onDestroy()
    }
    
    /**
     * Set up the test environment.
     */
    private fun setupTestEnvironment() {
        // Example: Clear shared preferences
        targetContext.getSharedPreferences("com.kopia.android.preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        
        // Example: Create test directories
        val testDir = targetContext.filesDir.resolve("test_data")
        if (!testDir.exists()) {
            testDir.mkdirs()
        }
    }
    
    /**
     * Clean up the test environment.
     */
    private fun cleanupTestEnvironment() {
        // Example: Delete test files
        val testDir = targetContext.filesDir.resolve("test_data")
        if (testDir.exists()) {
            testDir.deleteRecursively()
        }
    }
}
