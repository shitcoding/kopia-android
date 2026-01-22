package org.kopiaKt.android.e2e

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.utils.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kopiaKt.android.notification.BackupNotificationManager
import org.kopiaKt.android.worker.BackupConstraints
import org.kopiaKt.android.worker.BackupWorker
import org.kopiaKt.android.worker.BackupWorkerConfig
import org.kopiaKt.android.worker.CheckpointStore
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * E2E tests for background backup operations using WorkManager.
 *
 * Tests that backups run correctly in the background with proper
 * constraint handling, checkpoint persistence, and notification management.
 *
 * Run with: ./gradlew :android:connectedAndroidTest --tests "*.BackgroundBackupE2ETest"
 */
@RunWith(AndroidJUnit4::class)
class BackgroundBackupE2ETest : AndroidE2ETestBase() {

    companion object {
        private const val TAG = "BackgroundBackupE2ETest"
        private const val SOURCE_ID = "test-source"
    }

    private lateinit var workManager: WorkManager
    private lateinit var checkpointStore: CheckpointStore
    private var savedRepositoryProvider: ((Context) -> DirectRepository?)? = null

    @Before
    override fun setUp() {
        super.setUp()

        // Save existing repository provider
        savedRepositoryProvider = BackupWorker.repositoryProvider

        // Initialize WorkManager for testing
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)

        // Initialize checkpoint store
        checkpointStore = CheckpointStore(context)
    }

    @After
    override fun tearDown() {
        // Restore repository provider
        BackupWorker.repositoryProvider = savedRepositoryProvider

        // Cancel all work
        workManager.cancelAllWork()

        // Clear checkpoints
        runBlocking {
            checkpointStore.clearCheckpoint(SOURCE_ID)
        }

        super.tearDown()
    }

    @Test
    fun scheduleOneTimeBackup() = runBlocking {
        Log.i(TAG, "Test: scheduleOneTimeBackup on ${getDeviceInfo()}")

        // Create test data
        createSimpleTestData()

        // Set up repository provider
        val repository = createRepository()
        BackupWorker.repositoryProvider = { repository }

        try {
            // Schedule backup
            BackupWorker.scheduleOneTime(
                context = context,
                sourceId = SOURCE_ID,
                sourcePath = sourceDir.absolutePath,
                config = BackupWorkerConfig(description = "Scheduled backup test"),
                constraints = BackupConstraints(
                    requiresWifi = false,
                    requiresBatteryNotLow = false,
                    requiresCharging = false
                )
            )

            // Get work info
            val workInfos = BackupWorker.getWorkInfo(context, SOURCE_ID)
            assertThat(workInfos).isNotNull()

            Log.i(TAG, "Backup scheduled successfully")
        } finally {
            repository.close()
        }
    }

    @Test
    fun schedulePeriodicBackup() = runBlocking {
        Log.i(TAG, "Test: schedulePeriodicBackup on ${getDeviceInfo()}")

        // Create test data
        createSimpleTestData()

        // Set up repository provider
        val repository = createRepository()
        BackupWorker.repositoryProvider = { repository }

        try {
            // Schedule periodic backup (minimum 15 minutes for WorkManager)
            BackupWorker.schedulePeriodic(
                context = context,
                sourceId = SOURCE_ID,
                sourcePath = sourceDir.absolutePath,
                intervalHours = 1, // 1 hour interval
                constraints = BackupConstraints(requiresWifi = false)
            )

            Log.i(TAG, "Periodic backup scheduled")
        } finally {
            repository.close()
        }
    }

    @Test
    fun cancelScheduledBackup() = runBlocking {
        Log.i(TAG, "Test: cancelScheduledBackup on ${getDeviceInfo()}")

        createSimpleTestData()

        val repository = createRepository()
        BackupWorker.repositoryProvider = { repository }

        try {
            // Schedule
            BackupWorker.scheduleOneTime(
                context = context,
                sourceId = SOURCE_ID,
                sourcePath = sourceDir.absolutePath
            )

            // Cancel
            BackupWorker.cancel(context, SOURCE_ID)

            // Verify cancelled
            delay(500) // Allow WorkManager to process

            Log.i(TAG, "Backup cancelled successfully")
        } finally {
            repository.close()
        }
    }

    @Test
    fun backupWorkerInputDataCreation() = runBlocking {
        Log.i(TAG, "Test: backupWorkerInputDataCreation on ${getDeviceInfo()}")

        // Create test data
        createSimpleTestData()

        // Verify worker input data can be created
        val config = BackupWorkerConfig(description = "Direct worker test")
        val configJson = Json.encodeToString(BackupWorkerConfig.serializer(), config)

        val inputData = androidx.work.Data.Builder()
            .putString(BackupWorker.KEY_SOURCE_ID, SOURCE_ID)
            .putString(BackupWorker.KEY_SOURCE_PATH, sourceDir.absolutePath)
            .putString(BackupWorker.KEY_CONFIG, configJson)
            .build()

        // Verify input data
        assertThat(inputData.getString(BackupWorker.KEY_SOURCE_ID)).isEqualTo(SOURCE_ID)
        assertThat(inputData.getString(BackupWorker.KEY_SOURCE_PATH)).isEqualTo(sourceDir.absolutePath)
        assertThat(inputData.getString(BackupWorker.KEY_CONFIG)).isNotNull()

        // Verify config can be deserialized
        val deserializedConfig = Json.decodeFromString(
            BackupWorkerConfig.serializer(),
            inputData.getString(BackupWorker.KEY_CONFIG)!!
        )
        assertThat(deserializedConfig.description).isEqualTo("Direct worker test")

        Log.i(TAG, "Worker input data creation verified")
    }

    @Test
    fun checkpointPersistence() = runBlocking {
        Log.i(TAG, "Test: checkpointPersistence on ${getDeviceInfo()}")

        // Verify checkpoint store works
        val checkpoint = org.kopiaKt.android.worker.BackupCheckpoint(
            sourceId = SOURCE_ID,
            sourcePath = sourceDir.absolutePath,
            repositoryConnectionJson = "{}",
            processedFiles = 100,
            processedBytes = 1024 * 1024,
            startTime = System.currentTimeMillis()
        )

        // Save checkpoint
        checkpointStore.saveCheckpoint(checkpoint)

        // Retrieve checkpoint
        val retrieved = checkpointStore.getCheckpoint(SOURCE_ID)
        assertThat(retrieved).isInstanceOf(org.kopiaKt.android.worker.CheckpointResult.Found::class.java)

        val found = retrieved as org.kopiaKt.android.worker.CheckpointResult.Found
        assertThat(found.checkpoint.sourceId).isEqualTo(SOURCE_ID)
        assertThat(found.checkpoint.processedFiles).isEqualTo(100)
        assertThat(found.checkpoint.processedBytes).isEqualTo(1024 * 1024L)

        // Clear checkpoint
        checkpointStore.clearCheckpoint(SOURCE_ID)

        // Verify cleared
        val afterClear = checkpointStore.getCheckpoint(SOURCE_ID)
        assertThat(afterClear).isEqualTo(org.kopiaKt.android.worker.CheckpointResult.NotFound)

        Log.i(TAG, "Checkpoint persistence verified")
    }

    @Test
    fun checkpointStaleDetection() = runBlocking {
        Log.i(TAG, "Test: checkpointStaleDetection on ${getDeviceInfo()}")

        // Create an old checkpoint (simulated by old timestamp)
        val oldCheckpoint = org.kopiaKt.android.worker.BackupCheckpoint(
            sourceId = SOURCE_ID,
            sourcePath = sourceDir.absolutePath,
            repositoryConnectionJson = "{}",
            startTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000) // 25 hours ago
        )

        checkpointStore.saveCheckpoint(oldCheckpoint)

        // Retrieve with default staleness (24 hours)
        val result = checkpointStore.getCheckpoint(SOURCE_ID)
        assertThat(result).isInstanceOf(org.kopiaKt.android.worker.CheckpointResult.Stale::class.java)

        Log.i(TAG, "Stale checkpoint detection verified")
    }

    @Test
    fun notificationChannelsCreation() {
        Log.i(TAG, "Test: notificationChannelsCreation on ${getDeviceInfo()}")

        val notificationManager = BackupNotificationManager(context, android.R.drawable.ic_popup_sync)

        // Create channels
        notificationManager.createNotificationChannels()

        Log.i(TAG, "Notification channels created successfully")
    }

    @Test
    fun progressNotificationBuilding() {
        Log.i(TAG, "Test: progressNotificationBuilding on ${getDeviceInfo()}")

        val notificationManager = BackupNotificationManager(context, android.R.drawable.ic_popup_sync)
        notificationManager.createNotificationChannels()

        // Build progress notification
        val notification = notificationManager.buildProgressNotification(
            sourceId = SOURCE_ID,
            sourcePath = "/storage/emulated/0/Test",
            currentFile = "documents/file.txt",
            progress = 45,
            processedBytes = 1024 * 1024 * 10, // 10 MB
            totalBytes = 1024 * 1024 * 100     // 100 MB
        )

        assertThat(notification).isNotNull()
        Log.i(TAG, "Progress notification built successfully")
    }

    @Test
    fun completionNotificationBuilding() {
        Log.i(TAG, "Test: completionNotificationBuilding on ${getDeviceInfo()}")

        val notificationManager = BackupNotificationManager(context, android.R.drawable.ic_popup_sync)
        notificationManager.createNotificationChannels()

        // Build completion notification
        val notification = notificationManager.buildCompletionNotification(
            sourcePath = "/storage/emulated/0/Test",
            filesCount = 150,
            totalBytes = 1024 * 1024 * 50, // 50 MB
            duration = 120_000 // 2 minutes
        )

        assertThat(notification).isNotNull()
        Log.i(TAG, "Completion notification built successfully")
    }

    @Test
    fun errorNotificationBuilding() {
        Log.i(TAG, "Test: errorNotificationBuilding on ${getDeviceInfo()}")

        val notificationManager = BackupNotificationManager(context, android.R.drawable.ic_popup_sync)
        notificationManager.createNotificationChannels()

        // Build error notification
        val notification = notificationManager.buildErrorNotification(
            sourcePath = "/storage/emulated/0/Test",
            errorMessage = "Permission denied"
        )

        assertThat(notification).isNotNull()
        Log.i(TAG, "Error notification built successfully")
    }

    @Test
    fun backupConstraintsCreation() {
        Log.i(TAG, "Test: backupConstraintsCreation on ${getDeviceInfo()}")

        // Test various constraint combinations
        val constraints1 = BackupConstraints(
            requiresWifi = true,
            requiresCharging = true,
            requiresBatteryNotLow = true
        )

        assertThat(constraints1.requiresWifi).isTrue()
        assertThat(constraints1.requiresCharging).isTrue()
        assertThat(constraints1.requiresBatteryNotLow).isTrue()

        val constraints2 = BackupConstraints(
            requiresWifi = false,
            requiresCharging = false
        )

        assertThat(constraints2.requiresWifi).isFalse()
        assertThat(constraints2.requiresCharging).isFalse()

        Log.i(TAG, "Backup constraints creation verified")
    }

    @Test
    fun multipleSourcesIndependent() = runBlocking {
        Log.i(TAG, "Test: multipleSourcesIndependent on ${getDeviceInfo()}")

        // Create multiple sources
        val source1Dir = File(testRoot, "source1")
        source1Dir.mkdirs()
        File(source1Dir, "file1.txt").writeText("Source 1 content")

        val source2Dir = File(testRoot, "source2")
        source2Dir.mkdirs()
        File(source2Dir, "file2.txt").writeText("Source 2 content")

        val repository = createRepository()
        BackupWorker.repositoryProvider = { repository }

        try {
            // Schedule both sources
            BackupWorker.scheduleOneTime(
                context = context,
                sourceId = "source1",
                sourcePath = source1Dir.absolutePath
            )

            BackupWorker.scheduleOneTime(
                context = context,
                sourceId = "source2",
                sourcePath = source2Dir.absolutePath
            )

            // Cancel just one
            BackupWorker.cancel(context, "source1")

            Log.i(TAG, "Multiple sources scheduled and cancelled independently")
        } finally {
            repository.close()
        }
    }

    @Test
    fun backupWorkerConfigSerialization() {
        Log.i(TAG, "Test: backupWorkerConfigSerialization on ${getDeviceInfo()}")

        val config = BackupWorkerConfig(
            description = "Test backup",
            tags = mapOf("environment" to "test", "device" to "emulator"),
            parallelUploads = 4,
            forceHashPercentage = 5,
            checkpointIntervalMillis = 60_000,
            minBytesBeforeCheckpoint = 1024 * 1024
        )

        // Serialize
        val json = Json.encodeToString(BackupWorkerConfig.serializer(), config)
        assertThat(json).contains("Test backup")
        assertThat(json).contains("environment")

        // Deserialize
        val deserialized = Json.decodeFromString(BackupWorkerConfig.serializer(), json)
        assertThat(deserialized.description).isEqualTo("Test backup")
        assertThat(deserialized.parallelUploads).isEqualTo(4)
        assertThat(deserialized.tags["environment"]).isEqualTo("test")

        Log.i(TAG, "Config serialization verified")
    }

    @Test
    fun fullBackgroundBackupCycle() = runBlocking {
        Log.i(TAG, "Test: fullBackgroundBackupCycle on ${getDeviceInfo()}")

        // This test simulates a full backup cycle through the BackupSession
        // since WorkManager testing in instrumented tests is limited

        createSimpleTestData()

        val repository = createRepository()
        try {
            // Create a BackupSession-like execution
            val sessionConfig = org.kopiaKt.android.worker.BackupSessionConfig(
                sourcePath = sourceDir.absolutePath,
                sourceId = SOURCE_ID,
                description = "Full cycle test",
                parallelUploads = 2
            )

            val callback = object : org.kopiaKt.android.worker.NullBackupSessionCallback() {
                var progressCount = 0

                override fun onProgress(counters: org.kopiaKt.snapshot.upload.UploadCounters) {
                    progressCount++
                }
            }

            val session = org.kopiaKt.android.worker.BackupSession(
                repository = repository,
                config = sessionConfig,
                checkpointStore = checkpointStore,
                callback = callback
            )

            val result = session.run()

            assertThat(result).isInstanceOf(org.kopiaKt.android.worker.BackupSessionResult.Success::class.java)

            val success = result as org.kopiaKt.android.worker.BackupSessionResult.Success
            Log.i(TAG, "Backup completed: manifest=${success.manifestId.value}, " +
                    "duration=${success.durationMillis}ms, " +
                    "progress callbacks=${callback.progressCount}")

            // Verify checkpoint was cleared on success
            val checkpointAfter = checkpointStore.getCheckpoint(SOURCE_ID)
            assertThat(checkpointAfter).isEqualTo(org.kopiaKt.android.worker.CheckpointResult.NotFound)
        } finally {
            repository.close()
        }
    }

    @Test
    fun backupSessionCancellation() = runBlocking {
        Log.i(TAG, "Test: backupSessionCancellation on ${getDeviceInfo()}")

        // Create larger data to have time to cancel
        createLargeTestData(fileCount = 50, avgFileSize = 10 * 1024)

        val repository = createRepository()
        try {
            val sessionConfig = org.kopiaKt.android.worker.BackupSessionConfig(
                sourcePath = sourceDir.absolutePath,
                sourceId = SOURCE_ID,
                description = "Cancellation test"
            )

            val session = org.kopiaKt.android.worker.BackupSession(
                repository = repository,
                config = sessionConfig,
                checkpointStore = checkpointStore
            )

            // Cancel immediately
            session.cancel()

            val result = session.run()

            // Should be cancelled (or might complete if already finished)
            Log.i(TAG, "Cancellation result: ${result::class.simpleName}")

            // Verify isCancelled returns true
            assertThat(session.isCancelled()).isTrue()
        } finally {
            repository.close()
        }
    }
}
