package org.kopiaKt.app.bridge

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.SourceInfo
import org.kopiaKt.android.worker.SourceStatus
import org.kopiaKt.android.worker.TaskInfo
import org.kopiaKt.android.worker.TaskKind
import org.kopiaKt.android.worker.TaskManager
import org.kopiaKt.android.worker.TaskStatus
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.policy.PolicyManager
import org.kopiaKt.snapshot.policy.RetentionPolicy
import java.time.Instant

/**
 * Unit tests for the bridge backup, source management, task management,
 * and policy CRUD methods added to KopiaWebBridge.
 *
 * Uses MockK to isolate the bridge from real managers.
 * Tests that:
 *   - Each method returns valid JSON with the WebResult structure
 *   - Success cases produce {success: true, data: ...}
 *   - Error cases produce {success: false, error: ..., errorCode: ...}
 *   - Methods properly delegate to underlying managers
 */
class BridgeBackupMethodsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private lateinit var taskManager: TaskManager
    private lateinit var sourceManager: BackupSourceManager
    private lateinit var repositoryManager: KopiaRepositoryManager
    private lateinit var repository: DirectRepository
    private lateinit var bridge: KopiaWebBridge

    @BeforeEach
    fun setUp() {
        taskManager = mockk(relaxed = true)
        sourceManager = mockk(relaxed = true)
        repositoryManager = mockk(relaxed = true)
        repository = mockk(relaxed = true)

        every { repositoryManager.getRepository() } returns repository

        mockkObject(PolicyManager)

        bridge = KopiaWebBridge(
            taskManager = taskManager,
            sourceManager = sourceManager,
            repositoryManager = repositoryManager
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(PolicyManager)
    }

    // ================================================================
    // Helper to parse JSON result and assert top-level structure
    // ================================================================

    private fun parseResult(jsonStr: String): JsonObject {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        assertTrue(obj.containsKey("success"), "Result must contain 'success' field")
        return obj
    }

    private fun assertSuccess(jsonStr: String): JsonObject {
        val obj = parseResult(jsonStr)
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success=true, got: $jsonStr")
        return obj
    }

    private fun assertError(jsonStr: String): JsonObject {
        val obj = parseResult(jsonStr)
        assertFalse(obj["success"]!!.jsonPrimitive.boolean, "Expected success=false, got: $jsonStr")
        assertNotNull(obj["error"], "Error result must contain 'error' field")
        return obj
    }

    // ================================================================
    // Source Management Tests
    // ================================================================

    @Nested
    @DisplayName("createSource")
    inner class CreateSourceTests {

        @Test
        fun `returns success with SourceInfo on valid input`() {
            val fakeSource = SourceInfo(
                id = "src-123",
                path = "/storage/documents",
                displayName = "My Documents",
                status = SourceStatus.IDLE,
                createdAt = Instant.parse("2026-01-01T00:00:00Z")
            )
            every { sourceManager.createSource("/storage/documents", "My Documents") } returns fakeSource

            val result = bridge.createSource("""{"uri":"/storage/documents","displayName":"My Documents"}""")
            val obj = assertSuccess(result)

            val data = obj["data"]!!.jsonObject
            assertEquals("src-123", data["id"]!!.jsonPrimitive.content)
            assertEquals("/storage/documents", data["path"]!!.jsonPrimitive.content)
            assertEquals("My Documents", data["displayName"]!!.jsonPrimitive.content)
            assertEquals("IDLE", data["status"]!!.jsonPrimitive.content)
        }

        @Test
        fun `delegates to BackupSourceManager`() {
            val fakeSource = SourceInfo(
                id = "src-1",
                path = "/data",
                displayName = "Data",
                createdAt = Instant.now()
            )
            every { sourceManager.createSource("/data", "Data") } returns fakeSource

            bridge.createSource("""{"uri":"/data","displayName":"Data"}""")

            verify(exactly = 1) { sourceManager.createSource("/data", "Data") }
        }

        @Test
        fun `returns error on invalid JSON`() {
            val result = bridge.createSource("not valid json")
            assertError(result)
        }
    }

    @Nested
    @DisplayName("deleteSource")
    inner class DeleteSourceTests {

        @Test
        fun `returns success true when source exists`() {
            every { sourceManager.getSource("src-1") } returns SourceInfo(
                id = "src-1",
                path = "/data",
                displayName = "Data",
                createdAt = Instant.now()
            )

            val result = bridge.deleteSource("src-1")
            val obj = assertSuccess(result)
            assertTrue(obj["data"]!!.jsonPrimitive.boolean)

            verify(exactly = 1) { sourceManager.deleteSource("src-1") }
        }

        @Test
        fun `returns error when source not found`() {
            every { sourceManager.getSource("nonexistent") } returns null

            val result = bridge.deleteSource("nonexistent")
            assertError(result)
        }
    }

    @Nested
    @DisplayName("getSourceStatus")
    inner class GetSourceStatusTests {

        @Test
        fun `returns source info when found`() {
            val fakeSource = SourceInfo(
                id = "src-42",
                path = "/photos",
                displayName = "Photos",
                status = SourceStatus.UPLOADING,
                createdAt = Instant.parse("2026-01-15T10:00:00Z")
            )
            every { sourceManager.getSource("src-42") } returns fakeSource

            val result = bridge.getSourceStatus("src-42")
            val obj = assertSuccess(result)

            val data = obj["data"]!!.jsonObject
            val source = data["source"]!!.jsonObject
            assertEquals("/photos", source["path"]!!.jsonPrimitive.content)
            assertEquals("UPLOADING", data["status"]!!.jsonPrimitive.content)
        }

        @Test
        fun `returns error when source not found`() {
            every { sourceManager.getSource("missing") } returns null

            val result = bridge.getSourceStatus("missing")
            assertError(result)
        }
    }

    @Nested
    @DisplayName("pauseSource")
    inner class PauseSourceTests {

        @Test
        fun `returns success when source exists`() {
            every { sourceManager.getSource("src-1") } returns SourceInfo(
                id = "src-1",
                path = "/data",
                displayName = "Data",
                status = SourceStatus.IDLE,
                createdAt = Instant.now()
            )

            val result = bridge.pauseSource("src-1")
            val obj = assertSuccess(result)
            assertTrue(obj["data"]!!.jsonPrimitive.boolean)

            verify(exactly = 1) { sourceManager.pauseSource("src-1") }
        }

        @Test
        fun `returns error when source not found`() {
            every { sourceManager.getSource("missing") } returns null

            val result = bridge.pauseSource("missing")
            assertError(result)
        }
    }

    @Nested
    @DisplayName("resumeSource")
    inner class ResumeSourceTests {

        @Test
        fun `returns success when source exists`() {
            every { sourceManager.getSource("src-1") } returns SourceInfo(
                id = "src-1",
                path = "/data",
                displayName = "Data",
                status = SourceStatus.PAUSED,
                createdAt = Instant.now()
            )

            val result = bridge.resumeSource("src-1")
            val obj = assertSuccess(result)
            assertTrue(obj["data"]!!.jsonPrimitive.boolean)

            verify(exactly = 1) { sourceManager.resumeSource("src-1") }
        }

        @Test
        fun `returns error when source not found`() {
            every { sourceManager.getSource("missing") } returns null

            val result = bridge.resumeSource("missing")
            assertError(result)
        }
    }

    // ================================================================
    // Backup Operations Tests
    // ================================================================

    @Nested
    @DisplayName("startBackup")
    inner class StartBackupTests {

        @Test
        @Disabled("startBackup is not yet implemented")
        fun `returns task ID on success`() {
            val fakeSource = SourceInfo(
                id = "src-1",
                path = "/data",
                displayName = "Data",
                createdAt = Instant.now()
            )
            every { sourceManager.getSource("src-1") } returns fakeSource
            every {
                taskManager.startTask(TaskKind.BACKUP, any(), any())
            } returns "task-42"

            val result = bridge.startBackup("src-1")
            val obj = assertSuccess(result)
            assertEquals("task-42", obj["data"]!!.jsonPrimitive.content)
        }

        @Test
        fun `returns not-implemented error`() {
            val result = bridge.startBackup("any-source")
            val obj = assertError(result)
            assertTrue(
                obj["error"]!!.jsonPrimitive.content.contains("not yet implemented"),
                "Expected a not-implemented error, got: $result"
            )
        }

        @Test
        @Disabled("startBackup is not yet implemented")
        fun `delegates to TaskManager with BACKUP kind`() {
            val fakeSource = SourceInfo(
                id = "src-1",
                path = "/data",
                displayName = "Data",
                createdAt = Instant.now()
            )
            every { sourceManager.getSource("src-1") } returns fakeSource
            every {
                taskManager.startTask(TaskKind.BACKUP, any(), any())
            } returns "task-1"

            bridge.startBackup("src-1")

            verify(exactly = 1) {
                taskManager.startTask(TaskKind.BACKUP, any(), any())
            }
        }
    }

    @Nested
    @DisplayName("cancelBackup")
    inner class CancelBackupTests {

        @Test
        fun `returns success when task exists`() {
            every { taskManager.getTask("task-1") } returns TaskInfo(
                id = "task-1",
                kind = TaskKind.BACKUP,
                description = "Backup /data",
                status = TaskStatus.RUNNING,
                startTime = Instant.now()
            )

            val result = bridge.cancelBackup("task-1")
            val obj = assertSuccess(result)
            assertTrue(obj["data"]!!.jsonPrimitive.boolean)

            verify(exactly = 1) { taskManager.cancelTask("task-1") }
        }

        @Test
        fun `returns error when task not found`() {
            every { taskManager.getTask("missing") } returns null

            val result = bridge.cancelBackup("missing")
            assertError(result)
        }
    }

    // ================================================================
    // Task Management Tests
    // ================================================================

    @Nested
    @DisplayName("listTasks")
    inner class ListTasksTests {

        @Test
        fun `returns empty list when no tasks`() {
            every { taskManager.listTasks() } returns emptyList()

            val result = bridge.listTasks()
            val obj = assertSuccess(result)
            val data = obj["data"]!!.jsonArray
            assertEquals(0, data.size)
        }

        @Test
        fun `returns task list with correct fields`() {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            every { taskManager.listTasks() } returns listOf(
                TaskInfo(
                    id = "task-1",
                    kind = TaskKind.BACKUP,
                    description = "Backup /data",
                    status = TaskStatus.RUNNING,
                    progressInfo = "50%",
                    startTime = now
                ),
                TaskInfo(
                    id = "task-2",
                    kind = TaskKind.RESTORE,
                    description = "Restore /photos",
                    status = TaskStatus.SUCCESS,
                    startTime = now,
                    endTime = now.plusSeconds(60)
                )
            )

            val result = bridge.listTasks()
            val obj = assertSuccess(result)
            val data = obj["data"]!!.jsonArray
            assertEquals(2, data.size)

            val task1 = data[0].jsonObject
            assertEquals("task-1", task1["id"]!!.jsonPrimitive.content)
            // BACKUP maps to the Go-style wire name the UI expects (see WebModels.TaskInfo.toWeb).
            assertEquals("Snapshot", task1["kind"]!!.jsonPrimitive.content)
            assertEquals("RUNNING", task1["status"]!!.jsonPrimitive.content)
            assertEquals("50%", task1["progressInfo"]!!.jsonPrimitive.content)

            val task2 = data[1].jsonObject
            assertEquals("task-2", task2["id"]!!.jsonPrimitive.content)
            assertEquals("SUCCESS", task2["status"]!!.jsonPrimitive.content)
        }
    }

    @Nested
    @DisplayName("getTask")
    inner class GetTaskTests {

        @Test
        fun `returns task info when found`() {
            val now = Instant.parse("2026-02-01T12:00:00Z")
            every { taskManager.getTask("task-5") } returns TaskInfo(
                id = "task-5",
                kind = TaskKind.MAINTENANCE,
                description = "GC run",
                status = TaskStatus.SUCCESS,
                startTime = now,
                endTime = now.plusSeconds(30)
            )

            val result = bridge.getTask("task-5")
            val obj = assertSuccess(result)
            val data = obj["data"]!!.jsonObject
            assertEquals("task-5", data["id"]!!.jsonPrimitive.content)
            assertEquals("Maintenance", data["kind"]!!.jsonPrimitive.content)
        }

        @Test
        fun `returns null data when task not found`() {
            every { taskManager.getTask("missing") } returns null

            val result = bridge.getTask("missing")
            val obj = assertSuccess(result)
            // data should be null for missing task (not an error - just not found)
            val dataElement = obj["data"]
            assertTrue(
                dataElement == null || dataElement.toString() == "null",
                "Expected null data for missing task"
            )
        }
    }

    @Nested
    @DisplayName("cancelTask")
    inner class CancelTaskTests {

        @Test
        fun `returns success when task exists`() {
            every { taskManager.getTask("task-1") } returns TaskInfo(
                id = "task-1",
                kind = TaskKind.BACKUP,
                description = "Backup",
                status = TaskStatus.RUNNING,
                startTime = Instant.now()
            )

            val result = bridge.cancelTask("task-1")
            val obj = assertSuccess(result)
            assertTrue(obj["data"]!!.jsonPrimitive.boolean)

            verify(exactly = 1) { taskManager.cancelTask("task-1") }
        }

        @Test
        fun `returns error when task not found`() {
            every { taskManager.getTask("missing") } returns null

            val result = bridge.cancelTask("missing")
            assertError(result)
        }
    }

    // ================================================================
    // Policy Management Tests
    // ================================================================

    @Nested
    @DisplayName("getPolicy")
    inner class GetPolicyTests {

        @Test
        fun `returns policy when found`() {
            val policy = Policy(
                retentionPolicy = RetentionPolicy(keepLatest = 10)
            )
            coEvery {
                PolicyManager.getPolicy(repository, any())
            } returns policy

            val requestJson = """{"host":"myhost","userName":"user","path":"/data"}"""
            val result = bridge.getPolicy(requestJson)
            val obj = assertSuccess(result)
            assertNotNull(obj["data"])
        }

        @Test
        fun `returns null data when no policy exists`() {
            coEvery {
                PolicyManager.getPolicy(repository, any())
            } returns null

            val requestJson = """{"host":"myhost","userName":"user","path":"/data"}"""
            val result = bridge.getPolicy(requestJson)
            val obj = assertSuccess(result)
            val dataElement = obj["data"]
            assertTrue(
                dataElement == null || dataElement.toString() == "null",
                "Expected null data when no policy exists"
            )
        }

        @Test
        fun `returns error when repository not connected`() {
            every { repositoryManager.getRepository() } returns null

            val requestJson = """{"host":"myhost","userName":"user","path":"/data"}"""
            val result = bridge.getPolicy(requestJson)
            assertError(result)
        }

        @Test
        fun `returns error on invalid JSON`() {
            val result = bridge.getPolicy("bad json")
            assertError(result)
        }
    }

    @Nested
    @DisplayName("setPolicy")
    inner class SetPolicyTests {

        @Test
        fun `returns success on valid input`() {
            coEvery {
                PolicyManager.setPolicy(repository, any(), any())
            } returns Unit

            val requestJson = """{
                "source": {"host":"myhost","userName":"user","path":"/data"},
                "policy": {"retention":{"keepLatest":5},"noParent":false}
            }"""
            val result = bridge.setPolicy(requestJson)
            assertSuccess(result)
        }

        @Test
        fun `returns error when repository not connected`() {
            every { repositoryManager.getRepository() } returns null

            val requestJson = """{
                "source": {"host":"h","userName":"u","path":"/p"},
                "policy": {}
            }"""
            val result = bridge.setPolicy(requestJson)
            assertError(result)
        }

        @Test
        fun `returns error on invalid JSON`() {
            val result = bridge.setPolicy("invalid")
            assertError(result)
        }
    }
}
