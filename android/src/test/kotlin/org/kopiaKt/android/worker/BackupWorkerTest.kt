package org.kopiaKt.android.worker

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Unit tests for BackupWorker.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class BackupWorkerTest {

    private lateinit var context: Context

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        // Clear the repository provider before each test
        BackupWorker.repositoryProvider = null
    }

    @AfterEach
    fun tearDown() {
        BackupWorker.repositoryProvider = null
    }

    @Nested
    @DisplayName("BackupConstraints")
    inner class BackupConstraintsTests {

        @Test
        fun `default constraints require WiFi`() {
            val constraints = BackupConstraints()

            assertThat(constraints.requiresWifi).isTrue()
        }

        @Test
        fun `default constraints require battery not low`() {
            val constraints = BackupConstraints()

            assertThat(constraints.requiresBatteryNotLow).isTrue()
        }

        @Test
        fun `default constraints do not require charging`() {
            val constraints = BackupConstraints()

            assertThat(constraints.requiresCharging).isFalse()
        }

        @Test
        fun `default constraints do not require device idle`() {
            val constraints = BackupConstraints()

            assertThat(constraints.requiresDeviceIdle).isFalse()
        }

        @Test
        fun `toWorkConstraints converts WiFi requirement`() {
            val constraints = BackupConstraints(requiresWifi = true)
            val workConstraints = constraints.toWorkConstraints()

            assertThat(workConstraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        }

        @Test
        fun `toWorkConstraints converts no WiFi requirement`() {
            val constraints = BackupConstraints(requiresWifi = false)
            val workConstraints = constraints.toWorkConstraints()

            assertThat(workConstraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        }

        @Test
        fun `toWorkConstraints converts charging requirement`() {
            val constraints = BackupConstraints(requiresCharging = true)
            val workConstraints = constraints.toWorkConstraints()

            assertThat(workConstraints.requiresCharging()).isTrue()
        }

        @Test
        fun `toWorkConstraints converts battery requirement`() {
            val constraints = BackupConstraints(requiresBatteryNotLow = true)
            val workConstraints = constraints.toWorkConstraints()

            assertThat(workConstraints.requiresBatteryNotLow()).isTrue()
        }

        @Test
        fun `toWorkConstraints converts idle requirement`() {
            val constraints = BackupConstraints(requiresDeviceIdle = true)
            val workConstraints = constraints.toWorkConstraints()

            assertThat(workConstraints.requiresDeviceIdle()).isTrue()
        }

        @Test
        fun `toWorkConstraints converts storage requirement`() {
            val constraints = BackupConstraints(requiresStorageNotLow = true)
            val workConstraints = constraints.toWorkConstraints()

            assertThat(workConstraints.requiresStorageNotLow()).isTrue()
        }
    }

    @Nested
    @DisplayName("BackupWorkerConfig")
    inner class BackupWorkerConfigTests {

        @Test
        fun `default config has empty description`() {
            val config = BackupWorkerConfig()

            assertThat(config.description).isEmpty()
        }

        @Test
        fun `default config has empty tags`() {
            val config = BackupWorkerConfig()

            assertThat(config.tags).isEmpty()
        }

        @Test
        fun `config is serializable`() {
            val config = BackupWorkerConfig(
                description = "Test backup",
                tags = mapOf("env" to "test"),
                parallelUploads = 2,
            )

            val json = Json.encodeToString(config)
            val decoded = Json.decodeFromString<BackupWorkerConfig>(json)

            assertThat(decoded.description).isEqualTo("Test backup")
            assertThat(decoded.tags).containsEntry("env", "test")
            assertThat(decoded.parallelUploads).isEqualTo(2)
        }

        @Test
        fun `parallelUploads is clamped to valid range`() {
            // Note: This tests the default calculation, actual clamping happens in BackupSessionConfig
            val config = BackupWorkerConfig()

            assertThat(config.parallelUploads).isAtLeast(1)
            assertThat(config.parallelUploads).isAtMost(4)
        }
    }

    @Nested
    @DisplayName("Worker Scheduling")
    inner class WorkerSchedulingTests {

        @Test
        fun `scheduleOneTime enqueues work`() {
            BackupWorker.scheduleOneTime(
                context = context,
                sourceId = "test-source",
                sourcePath = "/test/path",
            )

            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork("backup_test-source").get()

            assertThat(workInfos).isNotEmpty()
        }

        @Test
        fun `schedulePeriodic enqueues periodic work`() {
            BackupWorker.schedulePeriodic(
                context = context,
                sourceId = "test-source",
                sourcePath = "/test/path",
                intervalHours = 24,
            )

            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork("backup_periodic_test-source").get()

            assertThat(workInfos).isNotEmpty()
        }

        @Test
        fun `cancel removes scheduled work`() {
            BackupWorker.scheduleOneTime(
                context = context,
                sourceId = "cancel-test",
                sourcePath = "/test/path",
            )

            BackupWorker.cancel(context, "cancel-test")

            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork("backup_cancel-test").get()

            // Work should be cancelled or empty
            assertThat(workInfos.all { it.state.isFinished || it.state == androidx.work.WorkInfo.State.CANCELLED }).isTrue()
        }
    }

    @Nested
    @DisplayName("Repository Provider")
    inner class RepositoryProviderTests {

        @Test
        fun `repositoryProvider can be set`() {
            val mockProvider: (Context) -> org.kopiaKt.core.repository.DirectRepository? = { null }

            BackupWorker.repositoryProvider = mockProvider

            assertThat(BackupWorker.repositoryProvider).isEqualTo(mockProvider)
        }
    }

    @Nested
    @DisplayName("Input Data Keys")
    inner class InputDataKeysTests {

        @Test
        fun `KEY_SOURCE_ID is defined`() {
            assertThat(BackupWorker.KEY_SOURCE_ID).isEqualTo("source_id")
        }

        @Test
        fun `KEY_SOURCE_PATH is defined`() {
            assertThat(BackupWorker.KEY_SOURCE_PATH).isEqualTo("source_path")
        }

        @Test
        fun `KEY_CONFIG is defined`() {
            assertThat(BackupWorker.KEY_CONFIG).isEqualTo("config")
        }

        @Test
        fun `KEY_ERROR is defined`() {
            assertThat(BackupWorker.KEY_ERROR).isEqualTo("error")
        }

        @Test
        fun `KEY_MANIFEST_ID is defined`() {
            assertThat(BackupWorker.KEY_MANIFEST_ID).isEqualTo("manifest_id")
        }

        @Test
        fun `ACTION_CANCEL_BACKUP is defined`() {
            assertThat(BackupWorker.ACTION_CANCEL_BACKUP).isEqualTo("org.kopiaKt.android.CANCEL_BACKUP")
        }
    }
}
