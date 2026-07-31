package org.kopiaKt.android.worker

import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.storage.SafPermissionManager
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.time.Instant

/**
 * Sources used to live only in a map, so every configured folder vanished when the process died —
 * and once they are durable, deleting one has to mean more than removing a map entry.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class BackupSourcePersistenceTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun manager() = BackupSourceManager(context)

    @Test
    fun `sources survive the process`() {
        manager().createSource(CAMERA_ID, CAMERA_PATH, "Camera")

        // A fresh manager is what the next process gets.
        val reloaded = manager().getSource(CAMERA_ID)

        assertThat(reloaded).isNotNull()
        assertThat(reloaded!!.path).isEqualTo(CAMERA_PATH)
        assertThat(reloaded.displayName).isEqualTo("Camera")
    }

    @Test
    fun `the last snapshot time survives too`() {
        val first = manager()
        first.createSource(CAMERA_ID, CAMERA_PATH, "Camera")
        first.updateLastSnapshotTime(CAMERA_ID, Instant.ofEpochMilli(1_700_000_000_000))

        assertThat(manager().getSource(CAMERA_ID)?.lastSnapshotTime)
            .isEqualTo(Instant.ofEpochMilli(1_700_000_000_000))
    }

    @Test
    fun `a restored source is idle, not mid-upload`() {
        val first = manager()
        first.createSource(CAMERA_ID, CAMERA_PATH, "Camera")
        first.setSourceStatus(CAMERA_ID, SourceStatus.UPLOADING)

        // Nothing is uploading in a fresh process; showing UPLOADING would be a lie.
        assertThat(manager().getSource(CAMERA_ID)?.status).isEqualTo(SourceStatus.IDLE)
    }

    @Test
    fun `a deleted source stays deleted`() {
        val first = manager()
        first.createSource(CAMERA_ID, CAMERA_PATH, "Camera")
        first.deleteSource(CAMERA_ID)

        assertThat(manager().listSources()).isEmpty()
    }

    @Test
    fun `deleting a source clears its checkpoint and releases its SAF grant`(): Unit = runBlocking {
        val sources = manager()
        sources.createSource(SAF_ID, SAF_PATH, "Documents")
        val checkpoints = mockk<CheckpointStore>(relaxed = true)
        val permissions = mockk<SafPermissionManager>(relaxed = true)

        BackupSourceDeleter(context, sources, checkpoints, permissions).delete(SAF_ID)

        assertThat(sources.getSource(SAF_ID)).isNull()
        coVerify(exactly = 1) { checkpoints.clearCheckpoint(SAF_ID) }
        coVerify(exactly = 1) { permissions.releasePermission(any()) }
    }

    @Test
    fun `a SAF grant another source still needs is kept`(): Unit = runBlocking {
        val sources = manager()
        sources.createSource(SAF_ID, SAF_PATH, "Documents")
        sources.createSource("local@phone:$SAF_PATH", SAF_PATH, "Documents again")
        val permissions = mockk<SafPermissionManager>(relaxed = true)

        BackupSourceDeleter(context, sources, mockk(relaxed = true), permissions).delete(SAF_ID)

        // Grants are a capped per-app resource, but two sources may legitimately share a tree.
        coVerify(exactly = 0) { permissions.releasePermission(any()) }
    }

    @Test
    fun `deleting an unknown source is a no-op`(): Unit = runBlocking {
        val permissions = mockk<SafPermissionManager>(relaxed = true)

        BackupSourceDeleter(context, manager(), mockk(relaxed = true), permissions).delete("nope")

        coVerify(exactly = 0) { permissions.releasePermission(any()) }
    }

    private companion object {
        const val CAMERA_PATH = "/sdcard/DCIM"
        const val CAMERA_ID = "local@android-test-a1b2c3:/sdcard/DCIM"
        const val SAF_PATH = "content://com.android.externalstorage.documents/tree/primary%3ADocs"
        const val SAF_ID = "local@android-test-a1b2c3:$SAF_PATH"
    }
}
