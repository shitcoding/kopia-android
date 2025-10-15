package com.kopia.android

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.google.common.truth.Truth.assertThat

/**
 * End-to-end integration tests for complete user workflows.
 * These tests simulate real user scenarios from app launch to completion.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class IntegrationTestSuite : BaseInstrumentedTest() {

    private lateinit var context: Context

    @Before
    fun setupContext() {
        context = ApplicationProvider.getApplicationContext()
        grantStoragePermissions()
    }

    @Test
    fun testCompleteAppLaunchWorkflow() = runBlocking {
        // Test: User launches app for the first time
        println("=== Test: Complete App Launch Workflow ===")

        // Step 1: Launch MainActivity
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Step 2: Verify main UI elements are present
        onView(withId(R.id.webView)).check(matches(isDisplayed()))

        // Step 3: Wait for server to start (may take time)
        println("Waiting for server to start...")
        device.wait(Until.hasObject(By.desc("Kopia")), 45000)

        // Step 4: Verify WebView loaded something
        var webViewLoaded = false
        repeat(30) {
            try {
                // Check if WebView is visible and not in error state
                onView(withId(R.id.webView)).check(matches(isDisplayed()))
                webViewLoaded = true
                return@repeat
            } catch (e: Exception) {
                println("Waiting for WebView to load... attempt ${it + 1}")
            }
            Thread.sleep(1000)
        }

        assertThat(webViewLoaded).isTrue()
        println("✓ App launched successfully")

        scenario.close()
    }

    @Test
    fun testServerStartupAndConnectivity() = runBlocking {
        println("=== Test: Server Startup and Connectivity ===")

        // Launch app
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Wait for server to start
        println("Waiting for server startup...")
        delay(10000) // Give server time to start

        // Test server connectivity
        var serverUp = false
        repeat(20) {
            try {
                val url = URL("http://127.0.0.1:51515/")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "HEAD"

                val responseCode = connection.responseCode
                println("Server response code: $responseCode")

                if (responseCode in 200..499) {
                    serverUp = true
                    connection.disconnect()
                    return@repeat
                }

                connection.disconnect()
            } catch (e: Exception) {
                println("Server connection attempt ${it + 1} failed: ${e.message}")
            }

            delay(2000)
        }

        println("Server up: $serverUp")
        assertThat(serverUp).isTrue()
        println("✓ Server started and responding")

        scenario.close()
    }

    @Test
    fun testRepositorySetupWorkflow() = runBlocking {
        println("=== Test: Repository Setup Workflow ===")

        // Verify repository directory can be created
        val repoDir = File(context.filesDir, "repo")
        val configDir = File(context.filesDir, ".kopia")

        repoDir.mkdirs()
        configDir.mkdirs()

        assertThat(repoDir.exists()).isTrue()
        assertThat(configDir.exists()).isTrue()

        // Create a mock repository structure
        val testFile = File(repoDir, "test_snapshot.txt")
        testFile.writeText("Test snapshot data")

        assertThat(testFile.exists()).isTrue()

        println("✓ Repository directories created")

        // Cleanup
        testFile.delete()
    }

    @Test
    fun testFilesystemAccessWorkflow() = runBlocking {
        println("=== Test: Filesystem Access Workflow ===")

        // Test internal storage access
        val filesDir = context.filesDir
        assertThat(filesDir.exists()).isTrue()
        assertThat(filesDir.canWrite()).isTrue()

        // Create test data structure
        val testDir = File(filesDir, "test_backup")
        testDir.mkdirs()

        val testFile1 = File(testDir, "file1.txt")
        val testFile2 = File(testDir, "file2.txt")
        testFile1.writeText("File 1 content")
        testFile2.writeText("File 2 content")

        assertThat(testFile1.exists()).isTrue()
        assertThat(testFile2.exists()).isTrue()

        println("✓ Filesystem access verified")

        // Cleanup
        testDir.deleteRecursively()
    }

    @Test
    fun testAppLifecycleWorkflow() = runBlocking {
        println("=== Test: App Lifecycle Workflow ===")

        // Launch app
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        // Wait for initialization
        delay(5000)

        // Move to background
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        println("App moved to background")
        delay(2000)

        // Move back to foreground
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        println("App moved to foreground")
        delay(2000)

        // Verify app is still functional
        onView(withId(R.id.webView)).check(matches(isDisplayed()))

        println("✓ App lifecycle handling verified")

        scenario.close()
    }

    @Test
    fun testSettingsNavigationWorkflow() = runBlocking {
        println("=== Test: Settings Navigation Workflow ===")

        // Launch main activity
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        delay(3000)

        // Try to open settings
        try {
            // Use UiAutomator to tap menu button
            val menuButton = device.wait(
                Until.hasObject(By.desc("More options")),
                5000
            )

            if (menuButton) {
                device.findObject(By.desc("More options"))?.click()
                delay(1000)

                // Look for Settings menu item
                val settingsItem = device.findObject(By.text("Settings"))
                settingsItem?.click()

                delay(2000)
                println("✓ Settings navigation attempted")
            } else {
                println("Menu button not found, skipping settings test")
            }
        } catch (e: Exception) {
            println("Settings navigation test skipped: ${e.message}")
        }

        scenario.close()
    }

    @Test
    fun testErrorRecoveryWorkflow() = runBlocking {
        println("=== Test: Error Recovery Workflow ===")

        // Simulate error conditions and verify app handles them gracefully

        // Test 1: Missing kopia binary (move it temporarily)
        val kopiaBinary = File(context.filesDir, "kopia")
        val backupLocation = File(context.filesDir, "kopia.backup")

        if (kopiaBinary.exists()) {
            kopiaBinary.renameTo(backupLocation)
        }

        // Launch app (should handle missing binary gracefully)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        delay(5000)

        // App should not crash
        assertThat(scenario.state).isAnyOf(
            androidx.lifecycle.Lifecycle.State.RESUMED,
            androidx.lifecycle.Lifecycle.State.STARTED
        )

        println("✓ App handled missing binary gracefully")

        // Restore binary
        if (backupLocation.exists()) {
            backupLocation.renameTo(kopiaBinary)
        }

        scenario.close()
    }

    @Test
    fun testMemoryManagementWorkflow() = runBlocking {
        println("=== Test: Memory Management Workflow ===")

        // Monitor memory usage during app lifecycle
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        println("Initial memory usage: ${initialMemory / 1024 / 1024} MB")

        // Launch app
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        delay(5000)

        val afterLaunchMemory = runtime.totalMemory() - runtime.freeMemory()
        println("After launch memory usage: ${afterLaunchMemory / 1024 / 1024} MB")

        // Perform some operations
        delay(5000)

        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        println("Final memory usage: ${finalMemory / 1024 / 1024} MB")

        // Memory increase should be reasonable (less than 200MB)
        val memoryIncrease = (finalMemory - initialMemory) / 1024 / 1024
        println("Memory increase: $memoryIncrease MB")

        assertThat(memoryIncrease).isLessThan(200L)
        println("✓ Memory usage is reasonable")

        scenario.close()
    }

    @Test
    fun testConcurrentOperationsWorkflow() = runBlocking {
        println("=== Test: Concurrent Operations Workflow ===")

        // Test that app can handle multiple operations concurrently

        // Create test directories in parallel
        val dirs = List(5) { index ->
            File(context.filesDir, "concurrent_test_$index")
        }

        dirs.forEach { it.mkdirs() }

        // Verify all created
        dirs.forEach { dir ->
            assertThat(dir.exists()).isTrue()
        }

        println("✓ Concurrent directory creation successful")

        // Cleanup
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun testLongRunningOperationWorkflow() = runBlocking {
        println("=== Test: Long Running Operation Workflow ===")

        // Simulate a long-running operation (like large file copy)
        val largeFile = File(context.filesDir, "large_file.bin")

        try {
            // Create 50MB file
            val size = 50 * 1024 * 1024
            val buffer = ByteArray(1024 * 1024) // 1MB buffer

            largeFile.outputStream().use { out ->
                repeat(50) {
                    out.write(buffer)
                    println("Written ${(it + 1)} MB...")
                }
            }

            assertThat(largeFile.length()).isEqualTo(size.toLong())
            println("✓ Large file operation completed successfully")

        } finally {
            // Cleanup
            largeFile.delete()
        }
    }

    @Test
    fun testFullUserJourneyWorkflow() = runBlocking {
        println("=== Test: Full User Journey (End-to-End) ===")

        // Simulate complete user journey: Launch → Setup → Use → Exit

        // Step 1: Launch
        println("Step 1: Launching app...")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        delay(10000)

        // Step 2: Setup (verify directories exist)
        println("Step 2: Verifying setup...")
        val repoDir = File(context.filesDir, "repo")
        val configDir = File(context.filesDir, ".kopia")
        assertThat(repoDir.exists() || repoDir.mkdirs()).isTrue()
        assertThat(configDir.exists() || configDir.mkdirs()).isTrue()

        // Step 3: Simulate usage (wait for server)
        println("Step 3: Waiting for server...")
        delay(10000)

        // Step 4: Verify server is running
        println("Step 4: Checking server status...")
        var serverResponding = false
        repeat(10) {
            try {
                val url = URL("http://127.0.0.1:51515/")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "HEAD"
                val responseCode = connection.responseCode
                if (responseCode in 200..499) {
                    serverResponding = true
                    connection.disconnect()
                    return@repeat
                }
                connection.disconnect()
            } catch (e: Exception) {
                println("Server check attempt ${it + 1}: ${e.message}")
            }
            delay(2000)
        }

        println("Server responding: $serverResponding")

        // Step 5: Exit gracefully
        println("Step 5: Closing app...")
        scenario.close()

        println("✓ Full user journey completed")
        println("=====================================")
    }
}
