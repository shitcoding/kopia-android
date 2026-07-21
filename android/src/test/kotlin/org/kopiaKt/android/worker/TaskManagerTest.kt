package org.kopiaKt.android.worker

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("TaskManager")
class TaskManagerTest {

    /**
     * Creates a TaskManager backed by a child scope of the test scope.
     * The child scope uses SupervisorJob so individual task failures
     * don't cancel siblings, and calling shutdown() only cancels the child
     * scope without affecting the test scope (avoiding UncompletedCoroutinesError).
     */
    private fun TestScope.createTaskManager(): TaskManager {
        val childScope = CoroutineScope(coroutineContext + SupervisorJob())
        return TaskManager(childScope)
    }

    @Nested
    @DisplayName("Task Lifecycle")
    inner class TaskLifecycle {

        @Test
        fun `startTask returns unique task ID`(): Unit = runTest {
            val tm = createTaskManager()

            val id1 = tm.startTask(TaskKind.BACKUP, "first") { awaitCancellation() }
            val id2 = tm.startTask(TaskKind.RESTORE, "second") { awaitCancellation() }
            val id3 = tm.startTask(TaskKind.MAINTENANCE, "third") { awaitCancellation() }

            assertThat(id1).isNotEqualTo(id2)
            assertThat(id2).isNotEqualTo(id3)
            assertThat(id1).isNotEqualTo(id3)
            assertThat(id1).isNotEmpty()
            assertThat(id2).isNotEmpty()
            assertThat(id3).isNotEmpty()

            tm.shutdown()
        }

        @Test
        fun `getTask returns running task info`(): Unit = runTest {
            val tm = createTaskManager()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "test backup") { controller ->
                started.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started.await()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.id).isEqualTo(taskId)
            assertThat(info.kind).isEqualTo(TaskKind.BACKUP)
            assertThat(info.description).isEqualTo("test backup")
            assertThat(info.status).isEqualTo(TaskStatus.RUNNING)
            assertThat(info.errorMessage).isNull()

            tm.shutdown()
        }

        @Test
        fun `getTask returns null for unknown ID`(): Unit = runTest {
            val tm = createTaskManager()

            val info = tm.getTask("nonexistent-task-id")
            assertThat(info).isNull()

            tm.shutdown()
        }

        @Test
        fun `listTasks returns all active tasks`(): Unit = runTest {
            val tm = createTaskManager()
            val started1 = CompletableDeferred<Unit>()
            val started2 = CompletableDeferred<Unit>()

            tm.startTask(TaskKind.BACKUP, "backup-1") {
                started1.complete(Unit)
                awaitCancellation()
            }
            tm.startTask(TaskKind.RESTORE, "restore-1") {
                started2.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started1.await()
            started2.await()

            val tasks = tm.listTasks()
            assertThat(tasks).hasSize(2)
            assertThat(tasks.map { it.kind }).containsExactly(TaskKind.BACKUP, TaskKind.RESTORE)

            tm.shutdown()
        }

        @Test
        fun `completed task appears in list`(): Unit = runTest {
            val tm = createTaskManager()

            val taskId = tm.startTask(TaskKind.BACKUP, "quick task") {
                // Completes immediately
            }

            advanceUntilIdle()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.SUCCESS)
            assertThat(info.endTime).isNotNull()

            val tasks = tm.listTasks()
            assertThat(tasks.map { it.id }).contains(taskId)

            tm.shutdown()
        }

        @Test
        fun `failed task records error message`(): Unit = runTest {
            val tm = createTaskManager()

            val taskId = tm.startTask(TaskKind.BACKUP, "failing task") {
                throw RuntimeException("disk full")
            }

            advanceUntilIdle()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.FAILED)
            assertThat(info.errorMessage).isEqualTo("disk full")
            assertThat(info.endTime).isNotNull()

            tm.shutdown()
        }

        @Test
        fun `cancelTask sets status to CANCELING`(): Unit = runTest {
            val tm = createTaskManager()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "long task") {
                started.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started.await()

            tm.cancelTask(taskId)

            // After cancel request, before the coroutine processes it
            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isAnyOf(TaskStatus.CANCELING, TaskStatus.CANCELED)

            tm.shutdown()
        }

        @Test
        fun `canceled task block receives cancellation`(): Unit = runTest {
            val tm = createTaskManager()
            val cancelReceived = CompletableDeferred<Boolean>()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "cancellable") { controller ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelReceived.complete(true)
                    throw e
                }
            }

            advanceUntilIdle()
            started.await()

            tm.cancelTask(taskId)
            advanceUntilIdle()

            val wasCancelled = cancelReceived.await()
            assertThat(wasCancelled).isTrue()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.CANCELED)

            tm.shutdown()
        }

        @Test
        fun `task records start and end times`(): Unit = runTest {
            val tm = createTaskManager()
            val beforeStart = Instant.now()

            val taskId = tm.startTask(TaskKind.BACKUP, "timed task") {
                delay(50)
            }

            advanceUntilIdle()

            val afterEnd = Instant.now()
            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.startTime).isAtLeast(beforeStart)
            assertThat(info.startTime).isAtMost(afterEnd)
            assertThat(info.endTime).isNotNull()
            assertThat(info.endTime!!).isAtLeast(info.startTime)
            assertThat(info.endTime!!).isAtMost(afterEnd)

            tm.shutdown()
        }
    }

    @Nested
    @DisplayName("Progress Tracking")
    inner class ProgressTracking {

        @Test
        fun `reportProgress updates task info`(): Unit = runTest {
            val tm = createTaskManager()
            val progressSet = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "progress task") { controller ->
                controller.reportProgress("Scanning files...")
                progressSet.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            progressSet.await()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.progressInfo).isEqualTo("Scanning files...")

            tm.shutdown()
        }

        @Test
        fun `reportCounters updates task counters`(): Unit = runTest {
            val tm = createTaskManager()
            val countersSet = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "counter task") { controller ->
                controller.reportCounters(
                    mapOf(
                        "files" to TaskCounterValue(42, "files"),
                        "bytes" to TaskCounterValue(1024, "bytes", "info"),
                    ),
                )
                countersSet.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            countersSet.await()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.counters).hasSize(2)
            assertThat(info.counters["files"]!!.value).isEqualTo(42)
            assertThat(info.counters["files"]!!.units).isEqualTo("files")
            assertThat(info.counters["bytes"]!!.value).isEqualTo(1024)
            assertThat(info.counters["bytes"]!!.level).isEqualTo("info")

            tm.shutdown()
        }

        @Test
        fun `progress visible via getTask during execution`(): Unit = runTest {
            val tm = createTaskManager()
            val phase1 = CompletableDeferred<Unit>()
            val phase2 = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.RESTORE, "phased task") { controller ->
                controller.reportProgress("Phase 1")
                phase1.complete(Unit)
                proceed.await()
                controller.reportProgress("Phase 2")
                phase2.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            phase1.await()

            val info1 = tm.getTask(taskId)
            assertThat(info1).isNotNull()
            assertThat(info1!!.progressInfo).isEqualTo("Phase 1")

            proceed.complete(Unit)
            advanceUntilIdle()
            phase2.await()

            val info2 = tm.getTask(taskId)
            assertThat(info2).isNotNull()
            assertThat(info2!!.progressInfo).isEqualTo("Phase 2")

            tm.shutdown()
        }
    }

    @Nested
    @DisplayName("Task Types")
    inner class TaskTypes {

        @Test
        fun `backup task tracked correctly`(): Unit = runTest {
            val tm = createTaskManager()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "backup job") { controller ->
                controller.reportProgress("Uploading")
                started.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started.await()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.kind).isEqualTo(TaskKind.BACKUP)
            assertThat(info.status).isEqualTo(TaskStatus.RUNNING)

            tm.shutdown()
        }

        @Test
        fun `restore task tracked correctly`(): Unit = runTest {
            val tm = createTaskManager()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.RESTORE, "restore job") { controller ->
                controller.reportProgress("Downloading")
                started.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started.await()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.kind).isEqualTo(TaskKind.RESTORE)
            assertThat(info.status).isEqualTo(TaskStatus.RUNNING)

            tm.shutdown()
        }

        @Test
        fun `maintenance task tracked correctly`(): Unit = runTest {
            val tm = createTaskManager()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.MAINTENANCE, "gc job") { controller ->
                controller.reportProgress("Compacting")
                started.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started.await()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.kind).isEqualTo(TaskKind.MAINTENANCE)
            assertThat(info.status).isEqualTo(TaskStatus.RUNNING)

            tm.shutdown()
        }

        @Test
        fun `estimate task tracked correctly`(): Unit = runTest {
            val tm = createTaskManager()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.ESTIMATE, "estimate job") { controller ->
                controller.reportProgress("Counting")
                started.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started.await()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.kind).isEqualTo(TaskKind.ESTIMATE)
            assertThat(info.status).isEqualTo(TaskStatus.RUNNING)

            tm.shutdown()
        }
    }

    @Nested
    @DisplayName("Concurrency")
    inner class Concurrency {

        @Test
        fun `multiple tasks run concurrently`(): Unit = runTest {
            val tm = createTaskManager()
            val allStarted = List(5) { CompletableDeferred<Unit>() }
            val gate = CompletableDeferred<Unit>()

            (0 until 5).map { i ->
                tm.startTask(TaskKind.BACKUP, "concurrent-$i") {
                    allStarted[i].complete(Unit)
                    gate.await()
                }
            }

            advanceUntilIdle()
            allStarted.forEach { it.await() }

            val tasks = tm.listTasks()
            val runningTasks = tasks.filter { it.status == TaskStatus.RUNNING }
            assertThat(runningTasks).hasSize(5)

            // Let them all finish
            gate.complete(Unit)
            advanceUntilIdle()

            val completedTasks = tm.listTasks().filter { it.status == TaskStatus.SUCCESS }
            assertThat(completedTasks).hasSize(5)

            tm.shutdown()
        }

        @Test
        fun `cancelTask is idempotent`(): Unit = runTest {
            val tm = createTaskManager()
            val started = CompletableDeferred<Unit>()

            val taskId = tm.startTask(TaskKind.BACKUP, "cancel-idem") {
                started.complete(Unit)
                awaitCancellation()
            }

            advanceUntilIdle()
            started.await()

            // Cancel multiple times - should not throw
            tm.cancelTask(taskId)
            tm.cancelTask(taskId)
            tm.cancelTask(taskId)

            advanceUntilIdle()

            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.CANCELED)

            tm.shutdown()
        }

        @Test
        fun `listTasks thread-safe under concurrent mutations`(): Unit = runTest {
            val tm = createTaskManager()

            // Start 10 tasks that complete immediately
            (0 until 10).forEach { i ->
                tm.startTask(TaskKind.BACKUP, "stress-$i") {
                    // Completes immediately
                }
            }

            advanceUntilIdle()

            // Concurrently list tasks - should not throw ConcurrentModificationException
            val listJobs = (0 until 5).map {
                async {
                    tm.listTasks()
                }
            }

            val lists = listJobs.awaitAll()

            // All list operations should return valid results
            lists.forEach { list ->
                assertThat(list).isNotNull()
                assertThat(list).hasSize(10)
            }

            // Final listing should show all 10 tasks as SUCCESS
            val finalList = tm.listTasks()
            assertThat(finalList).hasSize(10)
            finalList.forEach {
                assertThat(it.status).isEqualTo(TaskStatus.SUCCESS)
            }

            tm.shutdown()
        }

        @Test
        fun `task cleanup after completion`(): Unit = runTest {
            val tm = createTaskManager()

            val taskId = tm.startTask(TaskKind.ESTIMATE, "cleanup task") {
                // Complete immediately
            }

            advanceUntilIdle()

            // Completed tasks should still be accessible (not removed prematurely)
            val info = tm.getTask(taskId)
            assertThat(info).isNotNull()
            assertThat(info!!.status).isEqualTo(TaskStatus.SUCCESS)
            assertThat(info.endTime).isNotNull()

            // After explicit cleanup, task should be removed
            tm.removeCompletedTask(taskId)
            val infoAfterCleanup = tm.getTask(taskId)
            assertThat(infoAfterCleanup).isNull()

            tm.shutdown()
        }
    }
}
