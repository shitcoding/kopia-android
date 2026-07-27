package org.kopiaKt.app.bridge

import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.TaskManager
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * A backup source picked through the plain restore picker keeps its SAF grant only for the current
 * process, so it would back up once and then throw `SecurityException` forever after — and the grant
 * cannot be taken retroactively. `pickBackupSource` is the pick-time path that takes it.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class BackupSourcePickerTest {

    private fun newBridge(): KopiaWebBridge {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return KopiaWebBridge(RuntimeEnvironment.getApplication(), activity, View(activity))
    }

    private fun succeeded(response: String): Boolean {
        val parsed = bridgeJson.parseToJsonElement(response).jsonObject
        return parsed["success"]!!.jsonPrimitive.boolean
    }

    @Test
    fun `a second picker request is refused while one is open`() {
        val bridge = newBridge()

        assertThat(succeeded(bridge.pickBackupSource())).isTrue()
        // The system picker is a single modal activity: a second launch would leak the first
        // registration and race two results onto one event.
        assertThat(succeeded(bridge.pickBackupSource())).isFalse()
    }

    @Test
    fun `the restore picker does not launch while a source pick is open`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val bridge = KopiaWebBridge(RuntimeEnvironment.getApplication(), activity, View(activity))
        bridge.pickBackupSource()
        assertThat(shadowOf(activity).nextStartedActivityForResult).isNotNull()

        bridge.pickRestoreDestination()

        // Nothing new was started: the pick in flight keeps the slot.
        assertThat(shadowOf(activity).nextStartedActivityForResult).isNull()
    }

    /**
     * The direction that would actually brick the app: a guard that is taken but never released
     * leaves both pickers dead for the rest of the activity's life.
     */
    @Test
    fun `the guard is released when the user cancels the pick`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val bridge = KopiaWebBridge(RuntimeEnvironment.getApplication(), activity, View(activity))
        bridge.pickBackupSource()

        val launched = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(launched.intent, Activity.RESULT_CANCELED, null)

        assertThat(succeeded(bridge.pickBackupSource())).isTrue()
    }

    @Test
    fun `an unavailable activity is reported, not thrown across the bridge`() {
        // An exception escaping a @JavascriptInterface method kills the process.
        val bridge = KopiaWebBridge(TaskManager(), BackupSourceManager(), mockk(relaxed = true))

        assertThat(succeeded(bridge.pickBackupSource())).isFalse()
    }
}
