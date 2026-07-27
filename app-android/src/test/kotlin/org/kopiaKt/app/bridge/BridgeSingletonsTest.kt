package org.kopiaKt.app.bridge

import android.view.View
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * `KopiaWebBridge` is constructed per `MainActivity`, so anything it owned privately died on
 * activity recreation -- configured sources vanished on a rotation, and `BackupWorker` could never
 * reach the same `TaskManager` the Tasks screen reads. Both now come from the singleton component.
 *
 * The assertions go through two independently built bridges rather than through the Hilt scope
 * directly, so reintroducing a per-bridge `by lazy` turns them red.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class BridgeSingletonsTest {

    private fun newBridge(): KopiaWebBridge {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).get()
        return KopiaWebBridge(app, activity, View(activity))
    }

    @Test
    fun `every bridge sees the same source manager`() {
        assertThat(newBridge().sourceManager).isSameInstanceAs(newBridge().sourceManager)
    }

    @Test
    fun `every bridge sees the same task manager`() {
        assertThat(newBridge().taskManager).isSameInstanceAs(newBridge().taskManager)
    }

    @Test
    fun `a source created through one bridge is visible through the next`() {
        val created = newBridge().sourceManager.createSource("local@test:/sdcard/DCIM", "/sdcard/DCIM", "Camera")

        assertThat(newBridge().sourceManager.getSource(created.id)).isEqualTo(created)
    }
}
