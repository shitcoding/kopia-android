package org.kopiaKt.app.bridge

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.SourceStatus
import org.kopiaKt.android.worker.TaskManager
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.model.SourceWithStats
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.app.domain.repository.SnapshotRepository
import org.kopiaKt.core.repository.DirectRepository
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.time.Instant

/**
 * The dashboard's per-source counts (task-53).
 *
 * Every row read "0 snapshots · 0 B" because `toWebStatus` hardcoded both numbers, next to a "Last
 * backup" time that was real. These tests fail if either number goes back to being a constant.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class SourceStatsJoinTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var sourceManager: BackupSourceManager
    private lateinit var snapshotRepository: SnapshotRepository
    private lateinit var bridge: KopiaWebBridge

    @BeforeEach
    fun setUp() {
        sourceManager = BackupSourceManager()
        snapshotRepository = mockk(relaxed = true)
        val repositoryManager = mockk<KopiaRepositoryManager>(relaxed = true)
        every { repositoryManager.getRepository() } returns mockk<DirectRepository>(relaxed = true)

        bridge = KopiaWebBridge(
            taskManager = TaskManager(),
            sourceManager = sourceManager,
            repositoryManager = repositoryManager,
            snapshotRepository = snapshotRepository,
            context = RuntimeEnvironment.getApplication(),
        )
        sourceManager.createSource(SOURCE_ID, "/sdcard/DCIM", "DCIM")
    }

    @Test
    fun `a source reports the snapshots the repository holds for it`() {
        coEvery { snapshotRepository.listSourcesWithStats() } returns listOf(
            SourceWithStats(
                source = SourceInfo(host = "device", userName = "local", path = "/sdcard/DCIM"),
                snapshotCount = 7,
                latestSnapshotTime = Instant.EPOCH,
                totalFileCount = 118,
                totalFileSize = 4_294_967_296,
                latestFailedEntryCount = 0,
            ),
        )

        val row = firstSource()

        assertEquals(7, row["snapshotCount"]?.jsonPrimitive?.intOrNull)
        assertEquals(4_294_967_296, row["totalFileSize"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `a source the repository knows nothing about reports no numbers, not zeroes`() {
        // "0 snapshots · 0 B" beside a real "Last backup" time is a statement, and a false one.
        // Absent leaves the row saying nothing about a count it does not know.
        coEvery { snapshotRepository.listSourcesWithStats() } returns emptyList()

        val row = firstSource()

        assertNull(row["snapshotCount"]?.jsonPrimitive?.intOrNull)
        assertNull(row["totalFileSize"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `an unreadable repository costs the counts, not the source list`() {
        // The dashboard's other job is showing that the source exists at all; a repository that
        // cannot be read must not empty it.
        coEvery { snapshotRepository.listSourcesWithStats() } throws IllegalStateException("Not connected")

        val row = firstSource()

        assertEquals(SOURCE_ID, row["id"]?.jsonPrimitive?.content)
        assertNull(row["snapshotCount"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `a running backup is answered from the last read, not by reading the repository again`() {
        coEvery { snapshotRepository.listSourcesWithStats() } returns listOf(statsFor(count = 4))

        // One idle read fills the cache -- as the dashboard does every time it is opened.
        assertEquals(4, firstSource()["snapshotCount"]?.jsonPrimitive?.intOrNull)

        sourceManager.setSourceStatus(SOURCE_ID, SourceStatus.UPLOADING, "task-1")

        val duringBackup = firstSource()

        // Still the numbers, so the row does not go blank the moment a backup starts...
        assertEquals(4, duringBackup["snapshotCount"]?.jsonPrimitive?.intOrNull)
        // ...but read once, not again. Every manifest read waits on the mutex that
        // applyRetention's repository.refresh() holds while it reads blobs from storage at the end
        // of the run, and this call blocks the WebView's one bridge thread.
        coVerify(exactly = 1) { snapshotRepository.listSourcesWithStats() }
    }

    private fun statsFor(count: Int) = SourceWithStats(
        source = SourceInfo(host = "device", userName = "local", path = "/sdcard/DCIM"),
        snapshotCount = count,
        latestSnapshotTime = Instant.EPOCH,
        totalFileCount = 1,
        totalFileSize = 1,
        latestFailedEntryCount = 0,
    )

    private fun firstSource(): JsonObject {
        val data = json.parseToJsonElement(bridge.listAllSources()).jsonObject["data"]!!
        return data.jsonArray.single().jsonObject
    }

    private val kotlinx.serialization.json.JsonElement.jsonPrimitive: JsonPrimitive?
        get() = this as? JsonPrimitive

    private companion object {
        const val SOURCE_ID = "local@device:/sdcard/DCIM"
    }
}
