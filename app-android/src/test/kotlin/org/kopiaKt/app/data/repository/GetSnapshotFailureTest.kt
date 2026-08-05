package org.kopiaKt.app.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.manifest.ManifestNotFoundException
import org.kopiaKt.core.repository.DirectRepository
import java.io.IOException

/**
 * `getSnapshot` used to catch everything and answer null, and the bridge wraps null as a SUCCESSFUL
 * result — so react-query cached it as fresh data, never retried, and never showed an error.
 *
 * That was harmless while nothing depended on the answer. It stopped being harmless when the
 * incomplete-snapshot warnings started keying on `snapshot.isIncomplete` (task-36): a transient
 * repository failure would make all three warnings silently not render while browsing and restoring
 * carried on — reproducing the exact hazard those warnings exist to close, invisibly.
 */
@DisplayName("getSnapshot failure handling")
class GetSnapshotFailureTest {

    private val repository = mockk<DirectRepository>()
    private val manager = mockk<KopiaRepositoryManagerImpl>().also {
        every { it.getRepository() } returns repository
    }
    private val snapshots = SnapshotRepositoryImpl(mockk(relaxed = true), manager)
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun `a snapshot that is not there is absence, not failure`(): Unit = runBlocking {
        // Deleted by retention between the list and the tap, say. Null is the honest answer.
        coEvery { repository.getManifest(any<ManifestId>(), any<kotlinx.serialization.KSerializer<Any>>()) } throws
            ManifestNotFoundException(ManifestId(id))

        assertNull(snapshots.getSnapshot(id))
    }

    @Test
    fun `a failure to read propagates instead of reading as a complete snapshot`() {
        // The caller can retry or say so. Answering null here says "this snapshot is fine", which is
        // the one thing it must not say.
        coEvery { repository.getManifest(any<ManifestId>(), any<kotlinx.serialization.KSerializer<Any>>()) } throws
            IOException("the network went away")

        assertThrows<IOException> {
            runBlocking { snapshots.getSnapshot(id) }
        }
    }
}
