package org.kopiaKt.android.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The kind of async operation being tracked.
 */
enum class TaskKind { BACKUP, RESTORE, MAINTENANCE, ESTIMATE }

/**
 * Status of a tracked task.
 */
enum class TaskStatus { RUNNING, CANCELING, CANCELED, SUCCESS, FAILED }

/**
 * A single counter value with units and optional severity level.
 *
 * Mirrors Go Kopia's `uitask.CounterValue`.
 */
data class TaskCounterValue(
    val value: Long,
    val units: String,
    val level: String = "",
)

/**
 * Snapshot of a task's current state.
 *
 * Mirrors Go Kopia's `uitask.Info`.
 */
data class TaskInfo(
    val id: String,
    val kind: TaskKind,
    val description: String,
    val status: TaskStatus,
    val progressInfo: String = "",
    val counters: Map<String, TaskCounterValue> = emptyMap(),
    val errorMessage: String? = null,
    val startTime: Instant,
    val endTime: Instant? = null,
)

/**
 * Controller passed to task blocks to report progress and respond to cancellation.
 *
 * Mirrors Go Kopia's `uitask.Controller`.
 */
interface TaskController {
    /**
     * The id of the task this controller drives.
     *
     * [TaskManager.startTask] returns it only to its caller, so without this a block cannot tell
     * whether some piece of shared state still refers to *this* run — which is how a backup avoids
     * clearing the registration of the run that replaced it.
     */
    val taskId: String

    /** Update the human-readable progress string. */
    fun reportProgress(info: String)

    /** Update the flexible counter map. */
    fun reportCounters(counters: Map<String, TaskCounterValue>)

    /** True once cancellation has been requested. */
    val isCancelled: Boolean

    /** Throws [CancellationException] if the task has been cancelled. */
    suspend fun checkCancellation()
}

/**
 * Unified task tracker for all async operations (backup, restore, maintenance, estimate).
 *
 * Provides:
 *  - Unique task IDs
 *  - Progress / counter reporting visible to the UI
 *  - Cooperative cancellation
 *  - Task lifecycle tracking (RUNNING -> SUCCESS / FAILED / CANCELED)
 *
 * Thread-safe: all internal state is stored in [ConcurrentHashMap] and
 * mutable fields inside [TaskEntry] are guarded by `synchronized`.
 *
 * @param scope The [CoroutineScope] used to launch task coroutines.
 *              Defaults to a [SupervisorJob] + [Dispatchers.Default] so that
 *              one failing task does not cancel siblings.
 */
class TaskManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val nextId = AtomicLong(1)
    private val tasks = ConcurrentHashMap<String, TaskEntry>()

    /**
     * Start a new task in the background.
     *
     * The [block] receives a [TaskController] for progress reporting and
     * cancellation checks. It runs in [scope] and the method returns
     * immediately with the task ID.
     *
     * @param kind     The type of operation.
     * @param description  Human-readable description.
     * @param onStarted  Runs on the CALLING thread once the task is visible to [getTask], before
     *   [block] is launched. For a caller that has to publish the new id somewhere — as a backup
     *   does, to tell the dashboard which task is uploading its source — doing it from inside
     *   [block] means it happens after this method has already returned to the UI, which can be too
     *   late to be seen. Keep it cheap: it runs before the caller gets its id back.
     * @param block    The suspending work to perform.
     * @return A unique task ID.
     */
    fun startTask(
        kind: TaskKind,
        description: String,
        onStarted: (taskId: String) -> Unit = {},
        block: suspend (TaskController) -> Unit,
    ): String {
        val taskId = "task-${nextId.getAndIncrement()}"
        val entry = TaskEntry(
            id = taskId,
            kind = kind,
            description = description,
            startTime = Instant.now(),
        )
        tasks[taskId] = entry
        onStarted(taskId)

        entry.job = scope.launch {
            val controller = TaskControllerImpl(entry)
            try {
                block(controller)
                entry.complete(TaskStatus.SUCCESS)
            } catch (e: CancellationException) {
                entry.complete(TaskStatus.CANCELED)
                throw e // record the status, then let the job settle as cancelled
            } catch (e: Exception) {
                entry.fail(e.message ?: "Unknown error")
            }
        }

        return taskId
    }

    /**
     * Retrieve the current state of a task, or null if the ID is unknown.
     */
    fun getTask(taskId: String): TaskInfo? = tasks[taskId]?.toInfo()

    /**
     * List all tasks (both active and completed).
     */
    fun listTasks(): List<TaskInfo> = tasks.values.map { it.toInfo() }

    /**
     * Request cooperative cancellation of a task.
     *
     * Idempotent: calling this on an already-cancelling / cancelled / finished
     * task is a no-op.
     */
    fun cancelTask(taskId: String) {
        val entry = tasks[taskId] ?: return
        entry.requestCancel()
    }

    /**
     * Remove a completed task from the tracked list.
     *
     * Only removes tasks in terminal states (SUCCESS, FAILED, CANCELED).
     * Has no effect on running or cancelling tasks.
     */
    fun removeCompletedTask(taskId: String) {
        val entry = tasks[taskId] ?: return
        val info = entry.toInfo()
        if (info.status in setOf(TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELED)) {
            tasks.remove(taskId)
        }
    }

    /**
     * Cancel all running tasks and release the underlying [CoroutineScope].
     *
     * After shutdown, no new tasks can be started.
     */
    fun shutdown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Internal state holder for a single task
    // ------------------------------------------------------------------

    private class TaskEntry(
        val id: String,
        val kind: TaskKind,
        val description: String,
        val startTime: Instant,
    ) {
        @Volatile var job: Job? = null

        // Guarded by `this`
        private var status: TaskStatus = TaskStatus.RUNNING
        private var progressInfo: String = ""
        private var counters: Map<String, TaskCounterValue> = emptyMap()
        private var errorMessage: String? = null
        private var endTime: Instant? = null

        @Synchronized
        fun toInfo(): TaskInfo = TaskInfo(
            id = id,
            kind = kind,
            description = description,
            status = status,
            progressInfo = progressInfo,
            counters = counters,
            errorMessage = errorMessage,
            startTime = startTime,
            endTime = endTime,
        )

        @Synchronized
        fun updateProgress(info: String) {
            if (status == TaskStatus.RUNNING || status == TaskStatus.CANCELING) {
                progressInfo = info
            }
        }

        @Synchronized
        fun updateCounters(newCounters: Map<String, TaskCounterValue>) {
            if (status == TaskStatus.RUNNING || status == TaskStatus.CANCELING) {
                counters = newCounters
            }
        }

        @Synchronized
        fun requestCancel() {
            if (status == TaskStatus.RUNNING) {
                status = TaskStatus.CANCELING
            }
            job?.cancel()
        }

        @Synchronized
        fun complete(finalStatus: TaskStatus) {
            when {
                status == TaskStatus.CANCELING && finalStatus == TaskStatus.CANCELED ->
                    status = TaskStatus.CANCELED
                status == TaskStatus.CANCELING && finalStatus == TaskStatus.SUCCESS ->
                    status = TaskStatus.SUCCESS
                status == TaskStatus.RUNNING ->
                    status = finalStatus
            }
            endTime = Instant.now()
        }

        @Synchronized
        fun fail(message: String) {
            status = TaskStatus.FAILED
            errorMessage = message
            endTime = Instant.now()
        }

        @Synchronized
        fun isCancelled(): Boolean = status == TaskStatus.CANCELING || status == TaskStatus.CANCELED
    }

    // ------------------------------------------------------------------
    // TaskController implementation bound to a TaskEntry
    // ------------------------------------------------------------------

    private class TaskControllerImpl(private val entry: TaskEntry) : TaskController {

        override val taskId: String get() = entry.id

        override fun reportProgress(info: String) {
            entry.updateProgress(info)
        }

        override fun reportCounters(counters: Map<String, TaskCounterValue>) {
            entry.updateCounters(counters)
        }

        override val isCancelled: Boolean
            get() = entry.isCancelled()

        override suspend fun checkCancellation() {
            if (entry.isCancelled()) {
                throw CancellationException("Task ${entry.id} was cancelled")
            }
        }
    }
}
