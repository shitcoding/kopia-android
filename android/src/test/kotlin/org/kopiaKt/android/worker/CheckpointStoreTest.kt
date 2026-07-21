package org.kopiaKt.android.worker

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * Unit tests for CheckpointStore.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class CheckpointStoreTest {

    private lateinit var context: Context
    private lateinit var checkpointStore: CheckpointStore

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        checkpointStore = CheckpointStore(context)
    }

    @AfterEach
    fun tearDown() = runTest {
        // Clean up any saved checkpoints
        checkpointStore.clearAll()
    }

    @Nested
    @DisplayName("saveCheckpoint")
    inner class SaveCheckpointTests {

        @Test
        fun `saves checkpoint and retrieves it`() = runTest {
            val checkpoint = createTestCheckpoint("source-1")

            checkpointStore.saveCheckpoint(checkpoint)
            val result = checkpointStore.getCheckpoint("source-1")

            assertThat(result).isInstanceOf(CheckpointResult.Found::class.java)
            val found = result as CheckpointResult.Found
            assertThat(found.checkpoint.sourceId).isEqualTo("source-1")
            assertThat(found.checkpoint.sourcePath).isEqualTo("/test/path")
        }

        @Test
        fun `overwrites existing checkpoint`() = runTest {
            val checkpoint1 = createTestCheckpoint("source-1", processedFiles = 100)
            val checkpoint2 = createTestCheckpoint("source-1", processedFiles = 200)

            checkpointStore.saveCheckpoint(checkpoint1)
            checkpointStore.saveCheckpoint(checkpoint2)

            val result = checkpointStore.getCheckpoint("source-1")
            assertThat(result).isInstanceOf(CheckpointResult.Found::class.java)
            assertThat((result as CheckpointResult.Found).checkpoint.processedFiles).isEqualTo(200)
        }
    }

    @Nested
    @DisplayName("getCheckpoint")
    inner class GetCheckpointTests {

        @Test
        fun `returns NotFound for non-existent checkpoint`() = runTest {
            val result = checkpointStore.getCheckpoint("non-existent")

            assertThat(result).isEqualTo(CheckpointResult.NotFound)
        }

        @Test
        fun `returns Stale for old checkpoint`() = runTest {
            val oldCheckpoint = BackupCheckpoint(
                sourceId = "source-1",
                sourcePath = "/test/path",
                repositoryConnectionJson = "{}",
                checkpointTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000), // 25 hours ago
            )

            checkpointStore.saveCheckpoint(oldCheckpoint)
            val result = checkpointStore.getCheckpoint("source-1")

            assertThat(result).isInstanceOf(CheckpointResult.Stale::class.java)
        }

        @Test
        fun `returns Found for valid checkpoint`() = runTest {
            val checkpoint = createTestCheckpoint("source-1")

            checkpointStore.saveCheckpoint(checkpoint)
            val result = checkpointStore.getCheckpoint("source-1")

            assertThat(result).isInstanceOf(CheckpointResult.Found::class.java)
        }
    }

    @Nested
    @DisplayName("clearCheckpoint")
    inner class ClearCheckpointTests {

        @Test
        fun `clears existing checkpoint`() = runTest {
            val checkpoint = createTestCheckpoint("source-1")
            checkpointStore.saveCheckpoint(checkpoint)

            checkpointStore.clearCheckpoint("source-1")

            val result = checkpointStore.getCheckpoint("source-1")
            assertThat(result).isEqualTo(CheckpointResult.NotFound)
        }

        @Test
        fun `does nothing for non-existent checkpoint`() = runTest {
            // Should not throw
            checkpointStore.clearCheckpoint("non-existent")

            val result = checkpointStore.getCheckpoint("non-existent")
            assertThat(result).isEqualTo(CheckpointResult.NotFound)
        }
    }

    @Nested
    @DisplayName("updateCheckpoint")
    inner class UpdateCheckpointTests {

        @Test
        fun `updates existing checkpoint`() = runTest {
            val checkpoint = createTestCheckpoint("source-1", processedFiles = 100)
            checkpointStore.saveCheckpoint(checkpoint)

            checkpointStore.updateCheckpoint("source-1") { existing ->
                existing.copy(processedFiles = 200)
            }

            val result = checkpointStore.getCheckpoint("source-1")
            assertThat(result).isInstanceOf(CheckpointResult.Found::class.java)
            assertThat((result as CheckpointResult.Found).checkpoint.processedFiles).isEqualTo(200)
        }

        @Test
        fun `does nothing for non-existent checkpoint`() = runTest {
            checkpointStore.updateCheckpoint("non-existent") { existing ->
                existing.copy(processedFiles = 999)
            }

            val result = checkpointStore.getCheckpoint("non-existent")
            assertThat(result).isEqualTo(CheckpointResult.NotFound)
        }
    }

    @Nested
    @DisplayName("listActiveCheckpoints")
    inner class ListActiveCheckpointsTests {

        @Test
        fun `returns empty list when no checkpoints`() = runTest {
            val checkpoints = checkpointStore.listActiveCheckpoints()

            assertThat(checkpoints).isEmpty()
        }

        @Test
        fun `returns all active checkpoints`() = runTest {
            checkpointStore.saveCheckpoint(createTestCheckpoint("source-1"))
            checkpointStore.saveCheckpoint(createTestCheckpoint("source-2"))
            checkpointStore.saveCheckpoint(createTestCheckpoint("source-3"))

            val checkpoints = checkpointStore.listActiveCheckpoints()

            assertThat(checkpoints).hasSize(3)
            assertThat(checkpoints.map { it.sourceId }).containsExactly("source-1", "source-2", "source-3")
        }

        @Test
        fun `excludes stale checkpoints`() = runTest {
            checkpointStore.saveCheckpoint(createTestCheckpoint("source-1"))
            checkpointStore.saveCheckpoint(
                BackupCheckpoint(
                    sourceId = "source-2",
                    sourcePath = "/test/path",
                    repositoryConnectionJson = "{}",
                    checkpointTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000),
                ),
            )

            val checkpoints = checkpointStore.listActiveCheckpoints()

            assertThat(checkpoints).hasSize(1)
            assertThat(checkpoints[0].sourceId).isEqualTo("source-1")
        }
    }

    @Nested
    @DisplayName("clearStaleCheckpoints")
    inner class ClearStaleCheckpointsTests {

        @Test
        fun `removes stale checkpoints`() = runTest {
            checkpointStore.saveCheckpoint(createTestCheckpoint("active"))
            checkpointStore.saveCheckpoint(
                BackupCheckpoint(
                    sourceId = "stale",
                    sourcePath = "/test/path",
                    repositoryConnectionJson = "{}",
                    checkpointTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000),
                ),
            )

            val cleared = checkpointStore.clearStaleCheckpoints()

            assertThat(cleared).isEqualTo(1)
            assertThat(checkpointStore.getCheckpoint("active")).isInstanceOf(CheckpointResult.Found::class.java)
            assertThat(checkpointStore.getCheckpoint("stale")).isEqualTo(CheckpointResult.NotFound)
        }

        @Test
        fun `returns zero when no stale checkpoints`() = runTest {
            checkpointStore.saveCheckpoint(createTestCheckpoint("active"))

            val cleared = checkpointStore.clearStaleCheckpoints()

            assertThat(cleared).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("observeCheckpoint")
    inner class ObserveCheckpointTests {

        @Test
        fun `emits null when checkpoint does not exist`() = runTest {
            val checkpoint = checkpointStore.observeCheckpoint("non-existent").first()

            assertThat(checkpoint).isNull()
        }

        @Test
        fun `emits checkpoint when it exists`() = runTest {
            checkpointStore.saveCheckpoint(createTestCheckpoint("source-1"))

            val checkpoint = checkpointStore.observeCheckpoint("source-1").first()

            assertThat(checkpoint).isNotNull()
            assertThat(checkpoint?.sourceId).isEqualTo("source-1")
        }
    }

    @Nested
    @DisplayName("BackupCheckpoint")
    inner class BackupCheckpointTests {

        @Test
        fun `isStale returns false for recent checkpoint`() {
            val checkpoint = createTestCheckpoint("test")

            assertThat(checkpoint.isStale()).isFalse()
        }

        @Test
        fun `isStale returns true for old checkpoint`() {
            val checkpoint = BackupCheckpoint(
                sourceId = "test",
                sourcePath = "/test",
                repositoryConnectionJson = "{}",
                checkpointTime = System.currentTimeMillis() - (25 * 60 * 60 * 1000),
            )

            assertThat(checkpoint.isStale()).isTrue()
        }

        @Test
        fun `ageMillis returns correct age`() {
            val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000)
            val checkpoint = BackupCheckpoint(
                sourceId = "test",
                sourcePath = "/test",
                repositoryConnectionJson = "{}",
                checkpointTime = tenMinutesAgo,
            )

            val age = checkpoint.ageMillis()

            // Allow some tolerance for test execution time
            assertThat(age).isAtLeast(10 * 60 * 1000 - 1000)
            assertThat(age).isAtMost(10 * 60 * 1000 + 1000)
        }
    }

    @Nested
    @DisplayName("CheckpointOptions")
    inner class CheckpointOptionsTests {

        @Test
        fun `has sensible defaults`() {
            val options = CheckpointOptions()

            assertThat(options.intervalMillis).isEqualTo(5 * 60 * 1000) // 5 minutes
            assertThat(options.minBytesBeforeCheckpoint).isEqualTo(10 * 1024 * 1024) // 10 MB
            assertThat(options.maxResumeAttempts).isEqualTo(3)
        }

        @Test
        fun `effectiveIntervalMillis clamps a zero or negative interval to the floor`() {
            // The checkpoint loop delays by effectiveIntervalMillis each cycle; a zero/negative config
            // must not collapse to delay(0) and busy-loop. (task-14)
            assertThat(CheckpointOptions(intervalMillis = 0).effectiveIntervalMillis)
                .isEqualTo(CheckpointOptions.MIN_CHECKPOINT_INTERVAL_MILLIS)
            assertThat(CheckpointOptions(intervalMillis = -5).effectiveIntervalMillis)
                .isEqualTo(CheckpointOptions.MIN_CHECKPOINT_INTERVAL_MILLIS)
            // A valid interval is passed through unchanged.
            assertThat(CheckpointOptions(intervalMillis = 60_000).effectiveIntervalMillis).isEqualTo(60_000)
        }
    }

    private fun createTestCheckpoint(
        sourceId: String,
        processedFiles: Int = 0,
        processedBytes: Long = 0,
    ) = BackupCheckpoint(
        sourceId = sourceId,
        sourcePath = "/test/path",
        repositoryConnectionJson = "{}",
        processedFiles = processedFiles,
        processedBytes = processedBytes,
    )
}
