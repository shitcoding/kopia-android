package org.kopiaKt.app.bridge

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.TaskManager
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * The UI can only address a source by the id the bridge hands it. That contract was broken:
 * `WebSourceStatus` carried no id, so the dashboard rebuilt `user@host:path` itself while
 * `BackupSourceManager` keyed on a `UUID` -- every `pauseSource`/`resumeSource`/`getSourceStatus`
 * from the dashboard came back "Source not found".
 *
 * These run against REAL managers, not mocks: mocked delegation tests cannot see a key mismatch.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class SourceIdContractTest {

    private val bridge = KopiaWebBridge(
        taskManager = TaskManager(),
        sourceManager = BackupSourceManager(),
        repositoryManager = mockk(relaxed = true),
        context = RuntimeEnvironment.getApplication(),
    )

    private fun listedId(): String {
        val listed = bridgeJson.parseToJsonElement(bridge.listAllSources())
            .jsonObject["data"]!!.jsonArray
        return listed.single().jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun succeeded(response: String): Boolean {
        val parsed = bridgeJson.parseToJsonElement(response).jsonObject
        return parsed["success"]!!.jsonPrimitive.boolean
    }

    @Test
    fun `the id the UI is given addresses the source`() {
        bridge.createSource("""{"uri":"$SOURCE_PATH","displayName":"Camera"}""")
        val id = listedId()

        assertThat(succeeded(bridge.getSourceStatus(id))).isTrue()
        assertThat(succeeded(bridge.pauseSource(id))).isTrue()
        assertThat(succeeded(bridge.resumeSource(id))).isTrue()
    }

    /**
     * The same string has to key the source manager AND the source's policy, or the effective policy
     * resolved at backup time is not the one the wizard saved (task-30.9 depends on this).
     */
    @Test
    fun `the source id is the snapshot identity the policy is stored under`() {
        bridge.createSource("""{"uri":"$SOURCE_PATH","displayName":"Camera"}""")

        val identity = localSnapshotSourceInfo(RuntimeEnvironment.getApplication(), SOURCE_PATH)
        assertThat(listedId()).isEqualTo(identity.toString())
    }

    @Test
    fun `a trailing slash or stray whitespace is the same source`() {
        bridge.createSource("""{"uri":"$SOURCE_PATH","displayName":"Camera"}""")
        bridge.createSource("""{"uri":" $SOURCE_PATH/ ","displayName":"Camera"}""")

        val listed = bridgeJson.parseToJsonElement(bridge.listAllSources())
            .jsonObject["data"]!!.jsonArray
        assertThat(listed).hasSize(1)
        assertThat(listed.single().jsonObject["id"]!!.jsonPrimitive.content)
            .isEqualTo(localSnapshotSourceInfo(RuntimeEnvironment.getApplication(), SOURCE_PATH).toString())
    }

    /**
     * A blank path yields the colon-less `user@host` identity, which the UI's `parseSourceId` then
     * rejects — an unusable row. Refuse it at the boundary instead.
     */
    @Test
    fun `a blank path is refused`() {
        assertThat(succeeded(bridge.createSource("""{"uri":"   "}"""))).isFalse()

        val listed = bridgeJson.parseToJsonElement(bridge.listAllSources())
            .jsonObject["data"]!!.jsonArray
        assertThat(listed).isEmpty()
    }

    @Test
    fun `a content URI is left intact`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
        bridge.createSource("""{"uri":"$uri","displayName":"Camera"}""")

        assertThat(listedId()).isEqualTo(localSnapshotSourceInfo(RuntimeEnvironment.getApplication(), uri).toString())
    }

    @Test
    fun `re-adding the same path does not create a second source`() {
        bridge.createSource("""{"uri":"$SOURCE_PATH","displayName":"Camera"}""")
        bridge.createSource("""{"uri":"$SOURCE_PATH","displayName":"Camera roll"}""")

        val listed = bridgeJson.parseToJsonElement(bridge.listAllSources())
            .jsonObject["data"]!!.jsonArray
        assertThat(listed).hasSize(1)
    }

    private companion object {
        const val SOURCE_PATH = "/sdcard/DCIM"
    }
}
