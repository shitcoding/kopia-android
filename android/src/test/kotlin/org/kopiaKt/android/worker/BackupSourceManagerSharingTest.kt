package org.kopiaKt.android.worker

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.time.Instant

/**
 * The backup worker and the UI must hold the SAME manager.
 *
 * Every mutation rewrites the whole persisted source document from that object's in-memory map, so a
 * second instance does not merely duplicate work — it overwrites the first one's writes with its own
 * stale copy, and serves stale reads until the process restarts. That made a failure recorded by the
 * worker invisible to the dashboard and then silently erased by the next unrelated save, which is
 * exactly the signal the record exists to provide.
 *
 * The two cannot share through Hilt: the worker lives in this module and the injector in
 * `app-android`, so the sharing is by construction and this is what holds it in place.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class BackupSourceManagerSharingTest {

    @Test
    @DisplayName("getInstance hands out one manager per process")
    fun `getInstance is shared`() {
        val context = RuntimeEnvironment.getApplication()

        val fromUi = BackupSourceManager.getInstance(context)
        val fromWorker = BackupSourceManager.getInstance(context)

        assertThat(fromWorker).isSameInstanceAs(fromUi)
    }

    @Test
    @DisplayName("a failure recorded through one reference is visible through the other")
    fun `failure recorded by the worker is visible to the ui`() {
        val context = RuntimeEnvironment.getApplication()
        val fromUi = BackupSourceManager.getInstance(context)
        val source = fromUi.createSource("u@h:/sdcard/DCIM", "/sdcard/DCIM", "Camera")

        BackupSourceManager.getInstance(context)
            .recordFailure(source.id, "Connect to a repository before backing up")

        assertThat(fromUi.getSource(source.id)!!.lastError)
            .isEqualTo("Connect to a repository before backing up")
    }

    @Test
    @DisplayName("a background success clears a failure recorded earlier")
    fun `success through the shared manager clears the failure`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = BackupSourceManager.getInstance(context)
        val source = manager.createSource("u@h:/sdcard/Pictures", "/sdcard/Pictures", "Pictures")
        manager.recordFailure(source.id, "Storage permission was refused")

        // What BackupWorker's own success branch does, so a run that never reaches the interactive
        // bridge still takes the banner down.
        manager.updateLastSnapshotTime(source.id, Instant.now())

        assertThat(manager.getSource(source.id)!!.lastError).isNull()
    }

    @Test
    @DisplayName("the failure survives being written to and read back from storage")
    fun `failure round-trips through SharedPreferences`() {
        val context = RuntimeEnvironment.getApplication()
        // Constructed directly, not via getInstance: the shared instance is captured for the life of
        // the process, and Robolectric hands each test a new Application, so a cached one would be
        // writing to the previous test's preferences.
        val writer = BackupSourceManager(context)
        val source = writer.createSource("u@h:/sdcard/Docs", "/sdcard/Docs", "Docs")
        writer.recordFailure(source.id, "Android would not let this backup run in the background")

        // A fresh manager stands in for the next process: the whole point of persisting the failure
        // is that the run which produced it died.
        val reloaded = BackupSourceManager(context)

        val restored = reloaded.getSource(source.id)!!
        assertThat(restored.lastError)
            .isEqualTo("Android would not let this backup run in the background")
        assertThat(restored.lastErrorTime).isNotNull()
    }
}
