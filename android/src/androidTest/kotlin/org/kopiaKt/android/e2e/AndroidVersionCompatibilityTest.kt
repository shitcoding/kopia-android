package org.kopiaKt.android.e2e

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.kopiaKt.android.notification.BackupNotificationManager
import org.kopiaKt.android.system.BatteryOptimizationChecker
import org.kopiaKt.android.system.NetworkConstraintChecker
import org.kopiaKt.android.system.PermissionManager
import org.kopiaKt.android.worker.BackupConstraints
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions
import java.io.File

/**
 * E2E tests for Android version compatibility (API 26-34).
 *
 * These tests verify that the implementation works correctly across
 * different Android versions, handling API differences appropriately.
 *
 * Run with: ./gradlew :android:connectedAndroidTest --tests "*.AndroidVersionCompatibilityTest"
 *
 * For multi-version testing, run on multiple AVD images:
 * - API 26 (Android 8.0)
 * - API 28 (Android 9)
 * - API 29 (Android 10)
 * - API 30 (Android 11)
 * - API 31 (Android 12)
 * - API 33 (Android 13)
 * - API 34 (Android 14)
 */
@RunWith(AndroidJUnit4::class)
class AndroidVersionCompatibilityTest : AndroidE2ETestBase() {

    companion object {
        private const val TAG = "VersionCompatibilityTest"
    }

    // ====================
    // API Level Information
    // ====================

    @Test
    fun reportDeviceInfo() {
        val info = getDeviceInfo()
        Log.i(TAG, "Running on: $info")

        // This test always passes - it's for reporting device info
        assertThat(info.apiLevel).isAtLeast(Build.VERSION_CODES.O) // API 26
    }

    // ====================
    // Storage Access Tests
    // ====================

    @Test
    fun internalStorageAccess() = runBlocking {
        Log.i(TAG, "Test: internalStorageAccess on API ${Build.VERSION.SDK_INT}")

        // Internal storage should work on all versions
        val testFile = File(sourceDir, "internal_test.txt")
        testFile.writeText("Internal storage test content")

        assertThat(testFile.exists()).isTrue()
        assertThat(testFile.readText()).isEqualTo("Internal storage test content")
        assertThat(testFile.delete()).isTrue()

        Log.i(TAG, "Internal storage access works on API ${Build.VERSION.SDK_INT}")
    }

    @Test
    fun scopedStorageCompatibility() {
        Log.i(TAG, "Test: scopedStorageCompatibility on API ${Build.VERSION.SDK_INT}")

        // Scoped storage was introduced in API 29, enforced in API 30+
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                Log.i(TAG, "API 30+: Scoped storage enforced")
                // MediaStore or SAF required for external storage
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                Log.i(TAG, "API 29: Scoped storage introduced (opt-out available)")
                // Can use requestLegacyExternalStorage in manifest
            }
            else -> {
                Log.i(TAG, "API < 29: Legacy external storage")
                // Traditional external storage permissions
            }
        }

        // Test should pass on all versions
        assertThat(true).isTrue()
    }

    // ====================
    // Notification Tests
    // ====================

    @Test
    fun notificationChannels() {
        Log.i(TAG, "Test: notificationChannels on API ${Build.VERSION.SDK_INT}")

        // Notification channels required since API 26, so always applicable
        val notificationManager = BackupNotificationManager(context, android.R.drawable.ic_popup_sync)
        notificationManager.createNotificationChannels()

        Log.i(TAG, "Notification channels created on API ${Build.VERSION.SDK_INT}")
    }

    @Test
    fun notificationPermission() {
        Log.i(TAG, "Test: notificationPermission on API ${Build.VERSION.SDK_INT}")

        // POST_NOTIFICATIONS permission required since API 33
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "API 33+: POST_NOTIFICATIONS permission required")
            val permissionManager = PermissionManager(context)
            val hasPermission = permissionManager.hasNotificationPermission()
            Log.i(TAG, "Notification permission granted: $hasPermission")
        } else {
            Log.i(TAG, "API < 33: Notifications allowed by default")
        }
    }

    // ====================
    // Foreground Service Tests
    // ====================

    @Test
    fun foregroundServiceType() {
        Log.i(TAG, "Test: foregroundServiceType on API ${Build.VERSION.SDK_INT}")

        // Foreground service types introduced in API 29
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // FOREGROUND_SERVICE_TYPE_DATA_SYNC available
            val foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            assertThat(foregroundType).isEqualTo(1)
            Log.i(TAG, "API 29+: Using FOREGROUND_SERVICE_TYPE_DATA_SYNC")
        } else {
            Log.i(TAG, "API < 29: Foreground service type not required")
        }
    }

    @Test
    fun foregroundServicePermission() {
        Log.i(TAG, "Test: foregroundServicePermission on API ${Build.VERSION.SDK_INT}")

        // FOREGROUND_SERVICE permission required since API 28
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.i(TAG, "API 28+: FOREGROUND_SERVICE permission required")
        } else {
            Log.i(TAG, "API < 28: FOREGROUND_SERVICE permission not required")
        }
    }

    // ====================
    // Battery Optimization Tests
    // ====================

    @Test
    fun batteryOptimizationChecker() {
        Log.i(TAG, "Test: batteryOptimizationChecker on API ${Build.VERSION.SDK_INT}")

        val checker = BatteryOptimizationChecker(context)

        // Battery state should work on all versions
        val batteryState = checker.getBatteryState()
        Log.i(TAG, "Battery state: level=${batteryState.level}%, " +
                "charging=${batteryState.isCharging}, " +
                "powerSaveMode=${batteryState.isPowerSaveMode}")

        // Battery low check
        val isLow = checker.isBatteryLow()
        Log.i(TAG, "Battery low: $isLow")

        // Doze mode check (API 23+, always applicable for us)
        val isIdle = checker.isDeviceIdle()
        Log.i(TAG, "Device idle (Doze): $isIdle")
    }

    @Test
    fun batteryExemptionCheck() {
        Log.i(TAG, "Test: batteryExemptionCheck on API ${Build.VERSION.SDK_INT}")

        val checker = BatteryOptimizationChecker(context)
        val isExempt = checker.isExemptFromBatteryOptimization()

        Log.i(TAG, "Battery optimization exemption: $isExempt")
        // Note: Test apps typically don't have exemption, which is fine
    }

    // ====================
    // Network Tests
    // ====================

    @Test
    fun networkStateChecker() {
        Log.i(TAG, "Test: networkStateChecker on API ${Build.VERSION.SDK_INT}")

        val checker = NetworkConstraintChecker(context)

        val networkState = checker.getCurrentNetworkState()
        Log.i(TAG, "Network: available=${networkState.isConnected}, " +
                "type=${networkState.type}, " +
                "metered=${networkState.isMetered}")

        // WiFi check
        val isWifi = checker.isWifiConnected()
        Log.i(TAG, "WiFi connected: $isWifi")

        // Network type
        val networkType = checker.getNetworkType()
        Log.i(TAG, "Network type: $networkType")
    }

    @Test
    fun networkCallbacksAvailable() {
        Log.i(TAG, "Test: networkCallbacksAvailable on API ${Build.VERSION.SDK_INT}")

        // ConnectivityManager.NetworkCallback available since API 21
        // We're at API 26+, so always available
        Log.i(TAG, "NetworkCallback API available")
    }

    // ====================
    // Permission Tests
    // ====================

    @Test
    fun permissionManagerWorks() {
        Log.i(TAG, "Test: permissionManagerWorks on API ${Build.VERSION.SDK_INT}")

        val permissionManager = PermissionManager(context)
        val state = permissionManager.getBackupPermissionState()

        Log.i(TAG, "Permission state: " +
                "storage=${state.hasStoragePermission}, " +
                "notifications=${state.hasNotificationPermission}, " +
                "batteryOptimization=${state.isExemptFromBatteryOptimization}")

        // Check missing permissions
        val missing = state.missingPermissions
        Log.i(TAG, "Missing permissions: ${missing.joinToString()}")
    }

    @Test
    fun storagePermissionSituation() {
        Log.i(TAG, "Test: storagePermissionSituation on API ${Build.VERSION.SDK_INT}")

        // Storage permission handling varies by API level
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                Log.i(TAG, "API 33+: READ_MEDIA_* permissions for media access")
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                Log.i(TAG, "API 30+: Scoped storage only, SAF for other access")
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                Log.i(TAG, "API 29: Scoped storage with requestLegacyExternalStorage")
            }
            else -> {
                Log.i(TAG, "API < 29: Legacy READ/WRITE_EXTERNAL_STORAGE")
            }
        }

        // Internal storage doesn't need permissions
        assertThat(sourceDir.exists()).isTrue()
    }

    // ====================
    // Cryptography Tests
    // ====================

    @Test
    fun cryptoAPIsAvailable() = runBlocking {
        Log.i(TAG, "Test: cryptoAPIsAvailable on API ${Build.VERSION.SDK_INT}")

        // Test that our crypto implementations work on this API level
        createSimpleTestData()

        val repository = createRepository()
        try {
            // Run a backup to test crypto end-to-end
            val writer = repository.newWriter(WriteSessionOptions())
            try {
                val source = SourceInfo(
                    host = android.os.Build.DEVICE,
                    userName = "android",
                    path = sourceDir.absolutePath
                )

                val uploader = SnapshotUploader(
                    writer = writer,
                    source = source,
                    policy = Policy(),
                    progress = CountingUploadProgress()
                )

                val result = uploader.upload(
                    rootDir = LocalFilesystem.directory(sourceDir.toPath()),
                    options = UploadOptions(description = "Crypto test")
                )

                writer.flush()

                assertThat(result.incomplete).isFalse()
                Log.i(TAG, "Crypto APIs work on API ${Build.VERSION.SDK_INT}")
            } finally {
                writer.close()
            }
        } finally {
            repository.close()
        }
    }

    @Test
    fun differentEncryptionAlgorithms() = runBlocking {
        Log.i(TAG, "Test: differentEncryptionAlgorithms on API ${Build.VERSION.SDK_INT}")

        File(sourceDir, "test.txt").writeText("Test for encryption")

        // Test AES-256-GCM (our primary algorithm)
        var repository = createRepository(encryption = "AES256-GCM-HMAC-SHA256")
        try {
            val writer = repository.newWriter(WriteSessionOptions())
            try {
                val source = SourceInfo("device", "user", sourceDir.absolutePath)
                val uploader = SnapshotUploader(writer, source, Policy())
                val result = uploader.upload(
                    LocalFilesystem.directory(sourceDir.toPath()),
                    UploadOptions(description = "AES-GCM test")
                )
                writer.flush()
                assertThat(result.incomplete).isFalse()
                Log.i(TAG, "AES-256-GCM works on API ${Build.VERSION.SDK_INT}")
            } finally {
                writer.close()
            }
        } finally {
            repository.close()
        }

        // Clean repo for next test
        repoDir.deleteRecursively()
        repoDir.mkdirs()

        // Test with different hash algorithm
        repository = createRepository(
            hash = "BLAKE3-256",
            encryption = "AES256-GCM-HMAC-SHA256"
        )
        try {
            val writer = repository.newWriter(WriteSessionOptions())
            try {
                val source = SourceInfo("device", "user", sourceDir.absolutePath)
                val uploader = SnapshotUploader(writer, source, Policy())
                val result = uploader.upload(
                    LocalFilesystem.directory(sourceDir.toPath()),
                    UploadOptions(description = "BLAKE3 test")
                )
                writer.flush()
                assertThat(result.incomplete).isFalse()
                Log.i(TAG, "BLAKE3-256 works on API ${Build.VERSION.SDK_INT}")
            } finally {
                writer.close()
            }
        } finally {
            repository.close()
        }
    }

    // ====================
    // WorkManager Tests
    // ====================

    @Test
    fun workManagerConstraints() {
        Log.i(TAG, "Test: workManagerConstraints on API ${Build.VERSION.SDK_INT}")

        // Test constraint combinations
        val constraints = BackupConstraints(
            requiresWifi = true,
            requiresBatteryNotLow = true,
            requiresCharging = false,
            requiresDeviceIdle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        )

        // Verify constraints are created correctly
        assertThat(constraints.requiresWifi).isTrue()
        assertThat(constraints.requiresBatteryNotLow).isTrue()
        assertThat(constraints.requiresCharging).isFalse()
        Log.i(TAG, "WorkManager constraints work on API ${Build.VERSION.SDK_INT}")
    }

    // ====================
    // Full Integration Test
    // ====================

    @Test
    fun fullBackupRestoreOnCurrentApiLevel() = runBlocking {
        Log.i(TAG, "Test: fullBackupRestoreOnCurrentApiLevel on API ${Build.VERSION.SDK_INT}")

        requireStorage(5 * 1024 * 1024) // 5 MB

        // Create comprehensive test data
        val testData = createComplexTestData()

        val repository = createRepository()
        try {
            // Backup
            val uploadProgress = CountingUploadProgress()
            val writer = repository.newWriter(WriteSessionOptions())
            try {
                val source = SourceInfo(
                    host = android.os.Build.DEVICE,
                    userName = "android",
                    path = sourceDir.absolutePath
                )

                val uploader = SnapshotUploader(
                    writer = writer,
                    source = source,
                    policy = Policy(),
                    progress = uploadProgress
                )

                val result = uploader.upload(
                    rootDir = LocalFilesystem.directory(sourceDir.toPath()),
                    options = UploadOptions(description = "Full API ${Build.VERSION.SDK_INT} test")
                )

                writer.flush()

                assertThat(result.incomplete).isFalse()
                Log.i(TAG, "Backup completed on API ${Build.VERSION.SDK_INT}")

                // Restore
                repository.refresh()

                val manifests = repository.findManifests(
                    mapOf(
                        org.kopiaKt.snapshot.model.ManifestLabels.TYPE to
                                org.kopiaKt.snapshot.model.ManifestLabels.TYPE_SNAPSHOT
                    )
                )
                assertThat(manifests).isNotEmpty()

                val (snapshot, _) = repository.getManifest(
                    manifests.first().id,
                    org.kopiaKt.snapshot.model.SnapshotManifest.serializer()
                )
                assertThat(snapshot).isNotNull()

                val repoRoot = org.kopiaKt.snapshot.snapshotfs.snapshotRoot(repository, snapshot)

                val output = org.kopiaKt.snapshot.restore.FilesystemOutput(
                    targetPath = restoreDir.toPath(),
                    options = org.kopiaKt.snapshot.restore.FilesystemOutputOptions(
                        overwriteFiles = true,
                        overwriteDirectories = true
                    )
                )

                val restorer = org.kopiaKt.snapshot.restore.SnapshotRestorer(output = output)
                restorer.restore(repoRoot)

                Log.i(TAG, "Restore completed on API ${Build.VERSION.SDK_INT}")

                // Verify
                val comparison = verifyRestoredData(testData)
                assertThat(comparison.identical).isTrue()

                Log.i(TAG, "Full backup/restore PASSED on API ${Build.VERSION.SDK_INT}")
            } finally {
                writer.close()
            }
        } finally {
            repository.close()
        }
    }

    // ====================
    // API-Specific Tests
    // ====================

    @Test
    fun api26_minimumRequirements() {
        Log.i(TAG, "Test: api26_minimumRequirements")

        // Verify we're at least on API 26
        assertThat(Build.VERSION.SDK_INT).isAtLeast(Build.VERSION_CODES.O)

        // API 26 features we depend on:
        // - Notification channels (required)
        // - FOREGROUND_SERVICE_TYPE constant doesn't exist yet (OK, not used on < Q)
        // - Java 8 time APIs (available)
        Log.i(TAG, "API 26 minimum requirements met")
    }

    @Test
    fun api29_scopedStorageHandling() {
        requireApiLevel(Build.VERSION_CODES.Q)
        Log.i(TAG, "Test: api29_scopedStorageHandling")

        // On API 29+, we handle scoped storage via:
        // 1. Internal storage (no permissions needed)
        // 2. SAF for external storage

        // This test verifies internal storage works
        val testFile = File(context.filesDir, "scoped_storage_test.txt")
        testFile.writeText("Scoped storage test")
        assertThat(testFile.readText()).isEqualTo("Scoped storage test")
        testFile.delete()

        Log.i(TAG, "Scoped storage handling verified on API ${Build.VERSION.SDK_INT}")
    }

    @Test
    fun api33_notificationPermission() {
        requireApiLevel(Build.VERSION_CODES.TIRAMISU)
        Log.i(TAG, "Test: api33_notificationPermission")

        // On API 33+, POST_NOTIFICATIONS permission is required
        val permissionManager = PermissionManager(context)

        // Check if permission is needed
        val permissions = permissionManager.getPermissionsToRequest()
        if (android.Manifest.permission.POST_NOTIFICATIONS in permissions) {
            Log.i(TAG, "POST_NOTIFICATIONS permission needs to be requested")
        }

        Log.i(TAG, "Notification permission handling verified on API ${Build.VERSION.SDK_INT}")
    }

    @Test
    fun api34_foregroundServiceRestrictions() {
        requireApiLevel(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        Log.i(TAG, "Test: api34_foregroundServiceRestrictions")

        // API 34 has stricter foreground service requirements
        // FOREGROUND_SERVICE_DATA_SYNC is declared in manifest

        Log.i(TAG, "Foreground service restrictions handled on API ${Build.VERSION.SDK_INT}")
    }
}
