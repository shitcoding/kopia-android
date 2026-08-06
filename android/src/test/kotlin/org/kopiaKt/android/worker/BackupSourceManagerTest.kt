package org.kopiaKt.android.worker

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for BackupSourceManager.
 *
 * Tests are organized into four categories:
 * - Source CRUD (8 tests)
 * - Source State (7 tests)
 * - Concurrency (4 tests)
 * - Edge Cases (3 tests)
 */
class BackupSourceManagerTest {

    private lateinit var manager: BackupSourceManager

    @BeforeEach
    fun setup() {
        manager = BackupSourceManager()
    }

    @Nested
    @DisplayName("Terminal failure of a background run")
    inner class LastFailureTests {

        /**
         * A backup that dies in a background process leaves nothing behind the user can see: the
         * interactive await is long gone, and the error notification is dropped outright on API 33+
         * when POST_NOTIFICATIONS was denied. Their belief that the photos are backed up is then
         * wrong with no signal at all, which is the failure this whole area exists to prevent.
         */
        @Test
        @DisplayName("a recorded failure is readable afterwards")
        fun `recordFailure is readable`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")

            manager.recordFailure(source.id, "Connect to a repository before backing up")

            val updated = manager.getSource(source.id)!!
            assertThat(updated.lastError).isEqualTo("Connect to a repository before backing up")
            assertThat(updated.lastErrorTime).isNotNull()
        }

        @Test
        @DisplayName("a later success clears the failure")
        fun `success clears the failure`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")
            manager.recordFailure(source.id, "Storage permission was refused")

            manager.updateLastSnapshotTime(source.id, Instant.now())

            val updated = manager.getSource(source.id)!!
            assertThat(updated.lastError).isNull()
            assertThat(updated.lastErrorTime).isNull()
        }

        @Test
        @DisplayName("a second failure replaces the first")
        fun `later failure replaces the earlier one`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")
            manager.recordFailure(source.id, "first")

            manager.recordFailure(source.id, "second")

            assertThat(manager.getSource(source.id)!!.lastError).isEqualTo("second")
        }

        @Test
        @DisplayName("recording against an unknown source is a no-op, not a crash")
        fun `unknown source is a no-op`() {
            manager.recordFailure("nobody@nowhere:/gone", "boom")

            assertThat(manager.getSource("nobody@nowhere:/gone")).isNull()
        }
    }

    @Nested
    @DisplayName("Source CRUD")
    inner class SourceCrudTests {

        @Test
        @DisplayName("createSource stores source with stable ID")
        fun `createSource stores source with stable ID`() {
            val source = manager.createSource(
                "/storage/emulated/0/Documents",
                "/storage/emulated/0/Documents",
                "Documents",
            )

            assertThat(source.id).isNotNull()
            assertThat(source.id).isNotEmpty()
        }

        @Test
        @DisplayName("createSource with SAF URI")
        fun `createSource with SAF URI`() {
            val safUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
            val source = manager.createSource(safUri, safUri, "SAF Documents")

            assertThat(source.path).isEqualTo(safUri)
            assertThat(source.displayName).isEqualTo("SAF Documents")
        }

        @Test
        @DisplayName("createSource with file path")
        fun `createSource with file path`() {
            val path = "/storage/emulated/0/DCIM"
            val source = manager.createSource(path, path, "Camera")

            assertThat(source.path).isEqualTo(path)
            assertThat(source.displayName).isEqualTo("Camera")
        }

        @Test
        @DisplayName("deleteSource removes source")
        fun `deleteSource removes source`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")

            manager.deleteSource(source.id)

            assertThat(manager.getSource(source.id)).isNull()
        }

        @Test
        @DisplayName("deleteSource for unknown ID is no-op")
        fun `deleteSource for unknown ID is no-op`() {
            // Should not throw any exception
            manager.deleteSource("non-existent-id")
        }

        @Test
        @DisplayName("getSource returns source info")
        fun `getSource returns source info`() {
            val created = manager.createSource("/test/path", "/test/path", "Test Source")

            val retrieved = manager.getSource(created.id)

            assertThat(retrieved).isNotNull()
            assertThat(retrieved!!.id).isEqualTo(created.id)
            assertThat(retrieved.path).isEqualTo("/test/path")
            assertThat(retrieved.displayName).isEqualTo("Test Source")
        }

        @Test
        @DisplayName("listSources returns all sources")
        fun `listSources returns all sources`() {
            manager.createSource("/path/1", "/path/1", "Source 1")
            manager.createSource("/path/2", "/path/2", "Source 2")
            manager.createSource("/path/3", "/path/3", "Source 3")

            val sources = manager.listSources()

            assertThat(sources).hasSize(3)
            assertThat(sources.map { it.displayName }).containsExactly("Source 1", "Source 2", "Source 3")
        }

        @Test
        @DisplayName("listSources empty when none exist")
        fun `listSources empty when none exist`() {
            val sources = manager.listSources()

            assertThat(sources).isEmpty()
        }
    }

    @Nested
    @DisplayName("Source State")
    inner class SourceStateTests {

        @Test
        @DisplayName("new source starts in IDLE state")
        fun `new source starts in IDLE state`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")

            assertThat(source.status).isEqualTo(SourceStatus.IDLE)
        }

        @Test
        @DisplayName("source transitions to UPLOADING on status change")
        fun `source transitions to UPLOADING on status change`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")

            manager.setSourceStatus(source.id, SourceStatus.UPLOADING)

            val updated = manager.getSource(source.id)
            assertThat(updated!!.status).isEqualTo(SourceStatus.UPLOADING)
        }

        @Test
        @DisplayName("source transitions back to IDLE on status change")
        fun `source transitions back to IDLE on status change`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")
            manager.setSourceStatus(source.id, SourceStatus.UPLOADING)

            manager.setSourceStatus(source.id, SourceStatus.IDLE)

            val updated = manager.getSource(source.id)
            assertThat(updated!!.status).isEqualTo(SourceStatus.IDLE)
        }

        @Test
        @DisplayName("the running task is cleared with the status")
        fun `the running task is cleared with the status`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")

            manager.setSourceStatus(source.id, SourceStatus.UPLOADING, "task-1")
            assertThat(manager.getSource(source.id)!!.currentTaskId).isEqualTo("task-1")

            manager.clearRunningTask(source.id, "task-1")

            val updated = manager.getSource(source.id)!!
            assertThat(updated.status).isEqualTo(SourceStatus.IDLE)
            assertThat(updated.currentTaskId).isNull()
        }

        @Test
        @DisplayName("a finished run does not clear the next run's registration")
        fun `a finished run does not clear the next run's registration`() {
            // A cancelled run tears itself down after it has stopped being the current one, and the
            // user can start the next backup of the same folder in between. An unconditional clear
            // would leave the dashboard idle for the whole of a backup that is actually running.
            val source = manager.createSource("/test/path", "/test/path", "Test")
            manager.setSourceStatus(source.id, SourceStatus.UPLOADING, "task-1")
            manager.setSourceStatus(source.id, SourceStatus.UPLOADING, "task-2")

            manager.clearRunningTask(source.id, "task-1")

            val updated = manager.getSource(source.id)!!
            assertThat(updated.status).isEqualTo(SourceStatus.UPLOADING)
            assertThat(updated.currentTaskId).isEqualTo("task-2")
        }

        @Test
        @DisplayName("source records last snapshot time")
        fun `source records last snapshot time`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")
            val snapshotTime = Instant.parse("2026-01-15T10:30:00Z")

            manager.updateLastSnapshotTime(source.id, snapshotTime)

            val updated = manager.getSource(source.id)
            assertThat(updated!!.lastSnapshotTime).isEqualTo(snapshotTime)
        }
    }

    @Nested
    @DisplayName("Concurrency")
    inner class ConcurrencyTests {

        @Test
        @DisplayName("concurrent createSource calls safe")
        fun `concurrent createSource calls safe`() {
            val threadCount = 20
            val barrier = CyclicBarrier(threadCount)
            val latch = CountDownLatch(threadCount)
            val executor = Executors.newFixedThreadPool(threadCount)

            try {
                repeat(threadCount) { i ->
                    executor.submit {
                        barrier.await(5, TimeUnit.SECONDS)
                        manager.createSource("/path/$i", "/path/$i", "Source $i")
                        latch.countDown()
                    }
                }

                assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
                assertThat(manager.listSources()).hasSize(threadCount)
            } finally {
                executor.shutdown()
            }
        }

        @Test
        @DisplayName("concurrent listSources calls safe")
        fun `concurrent listSources calls safe`() {
            // Pre-populate some sources
            repeat(10) { i ->
                manager.createSource("/path/$i", "/path/$i", "Source $i")
            }

            val threadCount = 20
            val barrier = CyclicBarrier(threadCount)
            val latch = CountDownLatch(threadCount)
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = mutableListOf<Throwable>()

            try {
                repeat(threadCount) {
                    executor.submit {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            val sources = manager.listSources()
                            assertThat(sources).isNotEmpty()
                        } catch (e: Throwable) {
                            synchronized(errors) { errors.add(e) }
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
                assertThat(errors).isEmpty()
            } finally {
                executor.shutdown()
            }
        }

        @Test
        @DisplayName("getSource returns consistent snapshot")
        fun `getSource returns consistent snapshot`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")

            val threadCount = 20
            val barrier = CyclicBarrier(threadCount)
            val latch = CountDownLatch(threadCount)
            val executor = Executors.newFixedThreadPool(threadCount)
            val errors = mutableListOf<Throwable>()

            try {
                repeat(threadCount) {
                    executor.submit {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            val retrieved = manager.getSource(source.id)
                            assertThat(retrieved).isNotNull()
                            assertThat(retrieved!!.id).isEqualTo(source.id)
                            assertThat(retrieved.path).isEqualTo("/test/path")
                        } catch (e: Throwable) {
                            synchronized(errors) { errors.add(e) }
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
                assertThat(errors).isEmpty()
            } finally {
                executor.shutdown()
            }
        }

        @Test
        @DisplayName("deleteSource while listing is safe")
        fun `deleteSource while listing is safe`() {
            // Create many sources
            val sourceIds = (0 until 50).map { i ->
                manager.createSource("/path/$i", "/path/$i", "Source $i").id
            }

            val threadCount = 10
            val barrier = CyclicBarrier(threadCount * 2)
            val latch = CountDownLatch(threadCount * 2)
            val executor = Executors.newFixedThreadPool(threadCount * 2)
            val errors = mutableListOf<Throwable>()

            try {
                // Half the threads list, half delete
                repeat(threadCount) { i ->
                    executor.submit {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            manager.listSources() // Should not throw ConcurrentModificationException
                        } catch (e: Throwable) {
                            synchronized(errors) { errors.add(e) }
                        } finally {
                            latch.countDown()
                        }
                    }
                    executor.submit {
                        try {
                            barrier.await(5, TimeUnit.SECONDS)
                            if (i < sourceIds.size) {
                                manager.deleteSource(sourceIds[i])
                            }
                        } catch (e: Throwable) {
                            synchronized(errors) { errors.add(e) }
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
                assertThat(errors).isEmpty()
            } finally {
                executor.shutdown()
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("createSource with empty display name uses path")
        fun `createSource with empty display name uses path`() {
            val source = manager.createSource("/storage/emulated/0/Documents", "/storage/emulated/0/Documents", "")

            assertThat(source.displayName).isEqualTo("/storage/emulated/0/Documents")
        }

        @Test
        @DisplayName("createSource keys on the supplied identity")
        fun `createSource keys on the supplied identity`() {
            val ids = (0 until 100).map { i ->
                manager.createSource("local@phone:/path/$i", "/path/$i", "Source $i").id
            }.toSet()

            assertThat(ids).hasSize(100)
        }

        @Test
        @DisplayName("re-registering an identity keeps its accumulated state")
        fun `re-registering an identity keeps its accumulated state`() {
            val id = "local@phone:/test/path"
            val first = manager.createSource(id, "/test/path", "Test")
            manager.setSourceStatus(id, SourceStatus.UPLOADING)
            manager.updateLastSnapshotTime(id, Instant.ofEpochSecond(1000))

            val second = manager.createSource(id, "/test/path", "Renamed")

            // Adding the same path twice is the same source, not a second row with a fresh history.
            assertThat(manager.listSources()).hasSize(1)
            assertThat(second.displayName).isEqualTo("Renamed")
            assertThat(second.status).isEqualTo(SourceStatus.UPLOADING)
            assertThat(second.lastSnapshotTime).isEqualTo(Instant.ofEpochSecond(1000))
            assertThat(second.createdAt).isEqualTo(first.createdAt)
        }

        @Test
        @DisplayName("deleteSource on already deleted is no-op")
        fun `deleteSource on already deleted is no-op`() {
            val source = manager.createSource("/test/path", "/test/path", "Test")
            manager.deleteSource(source.id)

            // Second delete should not throw
            manager.deleteSource(source.id)

            assertThat(manager.getSource(source.id)).isNull()
        }
    }
}
