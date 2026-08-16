package org.kopiaKt.app.bridge

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.TaskManager
import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SnapshotStats
import org.kopiaKt.app.domain.model.SnapshotWithRetention
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.repository.SnapshotRepository
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.time.Instant

/**
 * task-63: the count of entries a run could not read has to reach the UI, on **both** snapshot DTOs.
 *
 * Measured on a phone: a source that became unreadable part way through a walk produced a snapshot
 * marked COMPLETE holding 945 of 2004 files, `numFailed: 1059` in the manifest, sitting at the top of
 * the list as `latest` with nothing saying so — because the number stopped at the repository.
 *
 * Two DTOs carry a snapshot to the web layer and they are **separate data classes**, not subtypes:
 * [WebSnapshotInfo] for a single snapshot, and [WebSnapshotWithRetention] for the per-source list —
 * which `KopiaWebBridge.listSnapshotsWithRetention` assembles field by field. Adding the field to one
 * does nothing for the other, which is what happened while writing this fix and would have left the
 * snapshot list — the very screen the defect was measured on — still silent.
 *
 * These go through the **bridge**, on purpose. A first version built the DTOs by hand and asserted on
 * them; deleting the field from the bridge's assembly left it green, because the hand-built object
 * never touched the code under test.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
@DisplayName("Snapshot failure count crosses the bridge")
class SnapshotFailureCountCrossesTheBridgeTest {

    private val source = SourceInfo(host = "phone", userName = "local", path = "/sdcard/Download/photos")

    private fun snapshot(failedEntryCount: Int) = SnapshotInfo(
        id = "0123456789abcdef",
        source = source,
        startTime = Instant.parse("2026-08-16T13:16:17Z"),
        endTime = Instant.parse("2026-08-16T13:16:33Z"),
        description = "",
        stats = SnapshotStats(totalFileSize = 60_100, totalFileCount = 945, totalDirectoryCount = 13),
        // COMPLETE — which is the whole problem: every isIncomplete warning stays silent for it.
        isIncomplete = false,
        failedEntryCount = failedEntryCount,
        tags = emptyMap(),
    )

    private fun bridgeReturning(failedEntryCount: Int): KopiaWebBridge {
        val repo = mockk<SnapshotRepository>(relaxed = true)
        coEvery { repo.listSnapshotsWithRetention(any()) } returns listOf(
            SnapshotWithRetention(snapshot(failedEntryCount), listOf("latest-1")),
        )
        coEvery { repo.getSnapshot(any()) } returns snapshot(failedEntryCount)
        return KopiaWebBridge(
            taskManager = TaskManager(),
            sourceManager = BackupSourceManager(),
            repositoryManager = mockk(relaxed = true),
            context = RuntimeEnvironment.getApplication(),
            snapshotRepository = repo,
        )
    }

    private val request =
        """{"source":{"host":"phone","userName":"local","path":"/sdcard/Download/photos"}}"""

    @Test
    fun `the per-source list carries it — the screen the defect was measured on`() {
        val listed = bridgeJson
            .parseToJsonElement(bridgeReturning(1059).listSnapshotsWithRetention(request))
            .jsonObject["data"]!!.jsonArray.single().jsonObject

        assertThat(listed["failedEntryCount"]!!.jsonPrimitive.int).isEqualTo(1059)
    }

    @Test
    fun `a single snapshot carries it — what the restore and browse warnings read`() {
        val snap = bridgeJson
            .parseToJsonElement(bridgeReturning(1059).getSnapshot("0123456789abcdef"))
            .jsonObject["data"]!!.jsonObject

        assertThat(snap["failedEntryCount"]!!.jsonPrimitive.int).isEqualTo(1059)
    }

    @Test
    fun `a healthy snapshot reports zero rather than omitting the field`() {
        val listed = bridgeJson
            .parseToJsonElement(bridgeReturning(0).listSnapshotsWithRetention(request))
            .jsonObject["data"]!!.jsonArray.single().jsonObject

        assertThat(listed["failedEntryCount"]!!.jsonPrimitive.int).isEqualTo(0)
    }
}
