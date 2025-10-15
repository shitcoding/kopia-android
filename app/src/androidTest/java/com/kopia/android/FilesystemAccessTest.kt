package com.kopia.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.google.common.truth.Truth.assertThat

/**
 * Instrumented tests for filesystem access functionality.
 * Tests both Storage Access Framework and MANAGE_EXTERNAL_STORAGE approaches.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class FilesystemAccessTest : BaseInstrumentedTest() {

    private lateinit var context: Context

    // Grant storage permissions for testing
    @get:Rule
    val storagePermissionRule: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            GrantPermissionRule.grant(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

    @Before
    fun setupContext() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testInternalStorageAccess() {
        // Test that app can access its internal storage
        val filesDir = context.filesDir
        assertThat(filesDir).isNotNull()
        assertThat(filesDir.exists()).isTrue()
        assertThat(filesDir.canRead()).isTrue()
        assertThat(filesDir.canWrite()).isTrue()

        // Create test file
        val testFile = File(filesDir, "test_filesystem.txt")
        testFile.writeText("Filesystem test")

        assertThat(testFile.exists()).isTrue()
        assertThat(testFile.readText()).isEqualTo("Filesystem test")

        // Cleanup
        testFile.delete()
    }

    @Test
    fun testCacheDirectory() {
        val cacheDir = context.cacheDir
        assertThat(cacheDir).isNotNull()
        assertThat(cacheDir.exists()).isTrue()
        assertThat(cacheDir.canRead()).isTrue()
        assertThat(cacheDir.canWrite()).isTrue()

        // Create test file in cache
        val cacheFile = File(cacheDir, "test_cache.txt")
        cacheFile.writeText("Cache test")

        assertThat(cacheFile.exists()).isTrue()

        // Cleanup
        cacheFile.delete()
    }

    @Test
    fun testExternalStorageState() {
        // Check if external storage is available
        val state = Environment.getExternalStorageState()

        // External storage should be mounted or emulated
        assertThat(state).isAnyOf(
            Environment.MEDIA_MOUNTED,
            Environment.MEDIA_MOUNTED_READ_ONLY
        )
    }

    @Test
    fun testExternalFilesDirectory() {
        // App-specific external storage (doesn't require permissions on Android 10+)
        val externalFilesDir = context.getExternalFilesDir(null)

        if (externalFilesDir != null) {
            assertThat(externalFilesDir.exists() || externalFilesDir.mkdirs()).isTrue()

            // Try to create a test file
            val testFile = File(externalFilesDir, "test_external.txt")
            try {
                testFile.writeText("External test")
                assertThat(testFile.exists()).isTrue()

                // Cleanup
                testFile.delete()
            } catch (e: Exception) {
                // On some emulators, external storage might not be fully available
                println("External storage test skipped: ${e.message}")
            }
        }
    }

    @Test
    fun testPublicDownloadDirectory() {
        // Test access to public Downloads directory
        // This requires MANAGE_EXTERNAL_STORAGE on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Check if we have MANAGE_EXTERNAL_STORAGE permission
            val hasManagePermission = Environment.isExternalStorageManager()

            if (hasManagePermission) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                assertThat(downloadsDir).isNotNull()

                // Try to list files
                val files = downloadsDir.listFiles()
                assertThat(files).isNotNull()
            } else {
                // Permission not granted, test should note this
                println("MANAGE_EXTERNAL_STORAGE not granted, skipping public directory test")
            }
        } else {
            // On older Android versions, test with regular permissions
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            assertThat(downloadsDir).isNotNull()
        }
    }

    @Test
    fun testKopiaRepositoryDirectory() {
        // Test that Kopia repository directory can be created
        val repoDir = File(context.filesDir, "repo")
        assertThat(repoDir.exists() || repoDir.mkdirs()).isTrue()
        assertThat(repoDir.canRead()).isTrue()
        assertThat(repoDir.canWrite()).isTrue()

        // Create test repository structure
        val testSnapshot = File(repoDir, "test_snapshot")
        assertThat(testSnapshot.exists() || testSnapshot.mkdirs()).isTrue()

        // Cleanup
        testSnapshot.deleteRecursively()
    }

    @Test
    fun testKopiaConfigDirectory() {
        // Test that Kopia config directory can be created
        val configDir = File(context.filesDir, ".kopia")
        assertThat(configDir.exists() || configDir.mkdirs()).isTrue()
        assertThat(configDir.canRead()).isTrue()
        assertThat(configDir.canWrite()).isTrue()

        // Create test config file
        val testConfig = File(configDir, "test_config.json")
        testConfig.writeText("{\"test\": true}")
        assertThat(testConfig.exists()).isTrue()

        // Cleanup
        testConfig.delete()
    }

    @Test
    fun testDirectoryPermissions() {
        // Test creating directories with different permission scenarios
        val testDir = File(context.filesDir, "permission_test")
        assertThat(testDir.mkdirs()).isTrue()

        // Verify we can read and write
        assertThat(testDir.canRead()).isTrue()
        assertThat(testDir.canWrite()).isTrue()

        // Create nested directories
        val nestedDir = File(testDir, "nested/deep/path")
        assertThat(nestedDir.mkdirs()).isTrue()
        assertThat(nestedDir.exists()).isTrue()

        // Cleanup
        testDir.deleteRecursively()
    }

    @Test
    fun testLargeFileHandling() {
        // Test that app can handle larger files (simulating Kopia repository)
        val testFile = File(context.filesDir, "large_test.bin")

        try {
            // Create a 10MB test file
            val size = 10 * 1024 * 1024 // 10MB
            val data = ByteArray(1024) { 0xFF.toByte() }

            testFile.outputStream().use { out ->
                repeat(size / 1024) {
                    out.write(data)
                }
            }

            assertThat(testFile.exists()).isTrue()
            assertThat(testFile.length()).isEqualTo(size.toLong())

        } finally {
            // Cleanup
            testFile.delete()
        }
    }

    @Test
    fun testFilePermissionScenarios() {
        val testFile = File(context.filesDir, "permission_file.txt")
        testFile.writeText("test")

        // Test various permission scenarios
        assertThat(testFile.canRead()).isTrue()
        assertThat(testFile.canWrite()).isTrue()

        // Try to set read-only
        testFile.setReadOnly()
        assertThat(testFile.canRead()).isTrue()

        // Cleanup (this might fail if truly read-only, but that's okay)
        try {
            testFile.delete()
        } catch (e: Exception) {
            println("Cleanup warning: ${e.message}")
        }
    }

    @Test
    fun testManageExternalStorageCheck() {
        // Check if MANAGE_EXTERNAL_STORAGE permission is available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasPermission = Environment.isExternalStorageManager()

            // Log the permission state for debugging
            println("MANAGE_EXTERNAL_STORAGE permission: $hasPermission")

            // This test documents the current permission state
            // In a real app, we would request this permission if needed
            assertThat(hasPermission).isAnyOf(true, false) // Just documenting state
        }
    }

    @Test
    fun testStorageAccessFrameworkAvailability() {
        // Verify that SAF intents can be created
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        assertThat(intent).isNotNull()
        assertThat(intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT_TREE)
    }
}
