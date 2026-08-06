package org.kopiaKt.app.bridge

import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.SourceStatus
import org.kopiaKt.android.worker.TaskManager
import org.kopiaKt.android.worker.toProgressData
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.snapshot.upload.UploadCounters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * The dashboard's live-progress block against the real managers, not mocks of them.
 *
 * Every other bridge test stubs [TaskManager] and [BackupSourceManager], so the one thing that
 * matters here — that a running backup's counters actually reach `listAllSources`, through a real
 * task started by the real `startBackup` — is precisely what a mocked test cannot see. The block
 * rendered nothing for the whole life of the feature because nobody ever joined the two.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class SourceProgressJoinTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val context get() = RuntimeEnvironment.getApplication()

    private lateinit var taskManager: TaskManager
    private lateinit var sourceManager: BackupSourceManager
    private lateinit var repositoryManager: KopiaRepositoryManager
    private lateinit var bridge: KopiaWebBridge

    @BeforeEach
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(ProgressWorkerFactory)
                .build(),
        )
        ProgressWorkerFactory.reset()

        taskManager = TaskManager()
        // No context: the in-memory manager, so the test does not depend on SharedPreferences.
        sourceManager = BackupSourceManager()
        repositoryManager = mockk<KopiaRepositoryManager>(relaxed = true)
        every { repositoryManager.getRepository() } returns mockk<DirectRepository>(relaxed = true)

        bridge = KopiaWebBridge(
            taskManager = taskManager,
            sourceManager = sourceManager,
            repositoryManager = repositoryManager,
            context = context,
        )
        sourceManager.createSource(SOURCE_ID, "/sdcard/DCIM", "DCIM")
    }

    @AfterEach
    fun tearDown() {
        // A failed assertion would otherwise leave the stand-in worker suspended and the task
        // coroutine running for the rest of the JVM's life.
        ProgressWorkerFactory.release.complete(Unit)
        taskManager.shutdown()
    }

    @Test
    fun `the source is uploading, under its task, by the time startBackup returns`() {
        // A TaskManager whose coroutines are queued and never run: the task's own block gets no
        // chance to do anything. The dashboard refetches the moment startBackup returns and only
        // keeps POLLING if that answer already says UPLOADING (useBackupApi.ts refetchInterval), so
        // a link published a beat later, from inside the block, can be missed for the whole run.
        // Suspending the dispatcher is what makes that losing race reproducible rather than rare.
        val queued = TaskManager(CoroutineScope(StandardTestDispatcher() + SupervisorJob()))
        val queuedBridge = KopiaWebBridge(
            taskManager = queued,
            sourceManager = sourceManager,
            repositoryManager = repositoryManager,
            context = context,
        )

        val taskId = json.parseToJsonElement(queuedBridge.startBackup(SOURCE_ID))
            .jsonObject["data"]!!.jsonPrimitive.content

        val started = statusFrom(queuedBridge)
        assertEquals("UPLOADING", started["status"]!!.jsonPrimitive.content)
        assertEquals(taskId, started.stringOrNull("currentTaskId"))

        queued.shutdown()
    }

    @Test
    fun `a running backup reports its task and that task's counters to the dashboard`() {
        val taskId = startBackup()

        val live = runBlocking { awaitStatus { it.counters() != null } }

        // The handle the dashboard opens the progress sheet on. Without it, tapping an uploading
        // row falls through to the snapshot list.
        assertEquals(taskId, live.stringOrNull("currentTaskId"))
        assertEquals("UPLOADING", live["status"]!!.jsonPrimitive.content)

        // Go's named counters, the same vocabulary the Tasks screen and the progress sheet read.
        // "Processed Bytes" is hashed + cached, which is what the progress bar divides by the
        // estimate -- see task-37.
        val counters = live.counters()!!
        assertEquals(50, counters["Processed Bytes"]!!.jsonObject["value"]!!.jsonPrimitive.long)
        assertEquals(200, counters["Estimated Bytes"]!!.jsonObject["value"]!!.jsonPrimitive.long)

        ProgressWorkerFactory.release.complete(Unit)
    }

    @Test
    fun `a source whose backup has ended keeps neither the task id nor its counters`() {
        startBackup()
        runBlocking { awaitStatus { it.counters() != null } }

        ProgressWorkerFactory.release.complete(Unit)

        val settled = runBlocking { awaitStatus { it["status"]!!.jsonPrimitive.content == "IDLE" } }
        // A finished run must not leave the row looking busy forever.
        assertNull(settled.stringOrNull("currentTaskId"))
        assertNull(settled.counters())
    }

    @Test
    fun `a task id that outlived its task is not reported as a running one`() {
        val taskId = startBackup()
        runBlocking { awaitStatus { it.counters() != null } }
        ProgressWorkerFactory.release.complete(Unit)
        runBlocking { awaitStatus { it["status"]!!.jsonPrimitive.content == "IDLE" } }

        // Clearing the id when the run ends is the first line of defence; this is the second. Put
        // the source back into the state a lost clear would leave it in -- an id whose task is over.
        sourceManager.setSourceStatus(SOURCE_ID, SourceStatus.UPLOADING, taskId)

        val stale = currentStatus()
        assertNull(stale.stringOrNull("currentTaskId"))
        assertNull(stale.counters())
        // And it must not still claim to be uploading: a busy row with nothing to tap into, that
        // the dashboard would go on polling for a change that can never come.
        assertEquals("IDLE", stale["status"]!!.jsonPrimitive.content)
    }

    /**
     * The source's live counters, or null while it has none — the wire sends an explicit null.
     *
     * Deliberately does not tolerate an empty map: a task reports nothing until its first progress
     * publish, and `{}` reaching the dashboard draws a full, static bar captioned "0 B".
     */
    private fun JsonObject.counters(): JsonObject? = this["uploadCounters"] as? JsonObject

    /** JsonNull reads as absent here: a null field and a missing one mean the same to the UI. */
    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Starts a real backup task through the bridge and returns its id. */
    private fun startBackup(): String {
        val result = json.parseToJsonElement(bridge.startBackup(SOURCE_ID)).jsonObject
        return result["data"]!!.jsonPrimitive.content
    }

    /**
     * Polls `listAllSources` until [predicate] holds. The counters travel from the worker through
     * WorkManager's progress flow into the task, so there is no single signal to await.
     */
    private suspend fun awaitStatus(predicate: (JsonObject) -> Boolean): JsonObject = withTimeout(TIMEOUT_MILLIS) {
        var status = currentStatus()
        while (!predicate(status)) {
            delay(POLL_MILLIS)
            status = currentStatus()
        }
        status
    }

    private fun currentStatus(): JsonObject = statusFrom(bridge)

    private fun statusFrom(bridge: KopiaWebBridge): JsonObject {
        val sources = json.parseToJsonElement(bridge.listAllSources()).jsonObject["data"]!!
        return sources.jsonArray.single().jsonObject
    }

    private companion object {
        const val SOURCE_ID = "local@android-test-a1b2c3:/sdcard/DCIM"
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L

        /** A worker that publishes one set of counters and then waits to be let go. */
        object ProgressWorkerFactory : androidx.work.WorkerFactory() {
            var release = CompletableDeferred<Unit>()

            fun reset() {
                release = CompletableDeferred()
            }

            override fun createWorker(
                appContext: android.content.Context,
                workerClassName: String,
                workerParameters: androidx.work.WorkerParameters,
            ) = object : androidx.work.CoroutineWorker(appContext, workerParameters) {
                override suspend fun doWork(): Result {
                    setProgress(
                        UploadCounters(
                            totalHashedBytes = 30,
                            totalCachedBytes = 20,
                            estimatedBytes = 200,
                        ).toProgressData(),
                    )
                    release.await()
                    return Result.success()
                }
            }
        }
    }
}
