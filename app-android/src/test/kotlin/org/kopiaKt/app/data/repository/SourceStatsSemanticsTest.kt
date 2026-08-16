package org.kopiaKt.app.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.manifest.EntryMetadata
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.manifest.ManifestNotFoundException
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirectorySummary
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SnapshotStats
import org.kopiaKt.snapshot.model.SourceInfo
import java.io.IOException
import java.time.Instant
import org.kopiaKt.app.domain.model.SourceInfo as DomainSourceInfo

/**
 * What "N snapshots" means for a source, and what happens to a manifest that cannot be read
 * (task-56).
 *
 * The same numbers feed three screens — the sources dashboard, the snapshots screen's source cards,
 * and the per-source snapshot list — so the rule has to be one rule. It is **complete snapshots
 * only**, which is what Go's `kopia snapshot list` shows unless `--incomplete` is passed: a
 * cancelled run leaves a manifest behind (retention deliberately keeps up to three of them), and
 * counting those makes cancelling a backup look like taking one.
 */
@DisplayName("listSourcesWithStats counting semantics")
class SourceStatsSemanticsTest {

    private val repository = mockk<DirectRepository>()
    private val manager = mockk<KopiaRepositoryManagerImpl>().also {
        every { it.getRepository() } returns repository
    }
    private val snapshots = SnapshotRepositoryImpl(mockk(relaxed = true), manager)

    @Test
    fun `a cancelled run does not raise the count`(): Unit = runBlocking {
        givenManifests(
            complete("a", at = 100, size = 500),
            complete("b", at = 200, size = 600),
            incomplete("c", at = 300, size = 7),
        )

        assertEquals(2, snapshots.listSourcesWithStats().single().snapshotCount)
    }

    @Test
    fun `the size is the latest complete run's, never a cancelled run's partial`(): Unit = runBlocking {
        // The newest manifest is a checkpoint of a run that never finished; its size is whatever had
        // been uploaded when the user hit cancel.
        givenManifests(
            complete("a", at = 100, size = 500),
            incomplete("b", at = 200, size = 7),
        )

        val stats = snapshots.listSourcesWithStats().single()

        assertEquals(500, stats.totalFileSize)
        assertEquals(Instant.ofEpochSecond(100), stats.latestSnapshotTime)
    }

    @Test
    fun `a source whose only run was cancelled is still listed, with nothing complete in it`(): Unit = runBlocking {
        // Dropping the source would hide the one screen that can show what the cancelled run did
        // manage to store.
        givenManifests(incomplete("a", at = 100, size = 7))

        val stats = snapshots.listSourcesWithStats().single()

        assertEquals(0, stats.snapshotCount)
        assertEquals(0, stats.totalFileSize)
        assertEquals(Instant.ofEpochSecond(100), stats.latestSnapshotTime)
    }

    @Test
    fun `a manifest that cannot be read fails the call instead of shrinking the count`() {
        // Silently skipping it reports "99 snapshots" for a source that has 100, with nothing
        // anywhere saying a manifest could not be read. In a backup tool that is the wrong
        // direction to fail: the caller can retry or say so, and both callers do.
        givenManifests(complete("a", at = 100, size = 500), complete("b", at = 200, size = 600))
        coEvery {
            repository.getManifest(ManifestId(hexId("b")), any<kotlinx.serialization.KSerializer<Any>>())
        } throws IOException("the network went away")

        assertThrows<IOException> { runBlocking { snapshots.listSourcesWithStats() } }
    }

    @Test
    fun `a manifest deleted while the list is read is absence, not failure`(): Unit = runBlocking {
        // Retention runs after every backup and can delete a manifest between findManifests and the
        // read. It is genuinely gone, so leaving it out of the count is the honest answer.
        givenManifests(complete("a", at = 100, size = 500), complete("b", at = 200, size = 600))
        coEvery {
            repository.getManifest(ManifestId(hexId("b")), any<kotlinx.serialization.KSerializer<Any>>())
        } throws ManifestNotFoundException(ManifestId(hexId("b")))

        assertEquals(1, snapshots.listSourcesWithStats().single().snapshotCount)
    }

    @Test
    fun `a manifest view known to be partial answers no count at all`() {
        // The other way a snapshot disappears, and the likelier one: ManifestManager skips a whole
        // manifest content BLOCK it cannot parse and marks the load incomplete, so those manifests
        // never reach findManifests and there is no read left to fail. One bad block can hide many
        // snapshots at once.
        givenManifests(complete("a", at = 100, size = 500))
        every { repository.lastLoadWasComplete() } returns false

        assertThrows<IllegalStateException> { runBlocking { snapshots.listSourcesWithStats() } }
        assertThrows<IllegalStateException> {
            runBlocking { snapshots.listSnapshotsWithRetention(DomainSourceInfo(HOST, USER, PATH)) }
        }
        assertThrows<IllegalStateException> { runBlocking { snapshots.listSnapshots(null) } }
    }

    @Test
    fun `the per-source list does not silently drop a manifest it cannot read`() {
        // This list is what the count above has to agree with -- it renders one row per manifest and
        // prints its own length as "N snapshots".
        givenManifests(complete("a", at = 100, size = 500))
        coEvery {
            repository.getManifest(ManifestId(hexId("a")), any<kotlinx.serialization.KSerializer<Any>>())
        } throws IOException("the network went away")

        assertThrows<IOException> {
            runBlocking { snapshots.listSnapshotsWithRetention(DomainSourceInfo(HOST, USER, PATH)) }
        }
    }

    @Test
    fun `listSnapshots does not silently drop a manifest it cannot read`() {
        // The per-source list falls back to this call when retention reasons cannot be computed
        // (kopiaBridge.listSnapshotsWithRetention), so a swallow here reaches the same screen.
        givenManifests(complete("a", at = 100, size = 500))
        coEvery {
            repository.getManifest(ManifestId(hexId("a")), any<kotlinx.serialization.KSerializer<Any>>())
        } throws IOException("the network went away")

        assertThrows<IOException> { runBlocking { snapshots.listSnapshots(null) } }
    }

    /**
     * task-63: the failure count has to survive the manifest -> domain mapping, not just exist on the
     * manifest. Measured on a phone — a snapshot marked COMPLETE holding 945 of 2004 files, with
     * `numFailed: 1059` recorded and read by nothing.
     *
     * Asserted through the real `SnapshotRepositoryImpl` rather than on the extension function it
     * calls: a mutation that hard-codes the mapping to 0 leaves the extension's own tests green.
     */
    @Test
    fun `a snapshot carries how many entries the run could not read`(): Unit = runBlocking {
        givenManifests(withFailures("a", at = 100, failed = 1059))

        val listed = snapshots.listSnapshotsWithRetention(DomainSourceInfo(HOST, USER, PATH))

        assertEquals(1059, listed.single().snapshot.failedEntryCount)
        // Complete, which is why no isIncomplete warning speaks for it — that is the defect.
        assertEquals(false, listed.single().snapshot.isIncomplete)
    }

    /**
     * task-63: the dashboard and the source cards print the newest COMPLETE snapshot's file count
     * and size, so when that snapshot lost entries the headline numbers describe a lossy backup.
     * They must be labelled — and the count has to come from the SAME snapshot, not from the newest
     * clean one, or the row would describe something that is not what "restore latest" returns.
     */
    @Test
    fun `a source reports the failures of the very snapshot its headline numbers come from`(): Unit = runBlocking {
        givenManifests(
            complete("a", at = 100, size = 500),
            withFailures("b", at = 200, failed = 1059),
        )

        val stats = snapshots.listSourcesWithStats().single()

        assertEquals(1059, stats.latestFailedEntryCount)
        // ...and it is the newest complete one that is described, not the older clean one.
        assertEquals(945, stats.totalFileCount)
    }

    @Test
    fun `a clean snapshot reports no failures`(): Unit = runBlocking {
        givenManifests(complete("a", at = 100, size = 500))

        val listed = snapshots.listSnapshotsWithRetention(DomainSourceInfo(HOST, USER, PATH))

        assertEquals(0, listed.single().snapshot.failedEntryCount)
    }

    /** Stubs `findManifests` with [manifests] and makes each one readable by its own id. */
    private fun givenManifests(vararg manifests: SnapshotManifest) {
        every { repository.lastLoadWasComplete() } returns true
        coEvery { repository.findManifests(any()) } returns manifests.map { metadataFor(it.id) }
        for (manifest in manifests) {
            coEvery {
                repository.getManifest(ManifestId(manifest.id), any<kotlinx.serialization.KSerializer<Any>>())
            } returns (manifest to metadataFor(manifest.id))
        }
    }

    /** A manifest id is 32 hex characters; the tests only need them distinct. */
    private fun hexId(seed: String) = seed.repeat(32)

    private fun metadataFor(id: String) = EntryMetadata(
        id = ManifestId(id),
        length = 1,
        labels = mapOf("type" to "snapshot", "hostname" to HOST, "username" to USER, "path" to PATH),
        modTime = Instant.EPOCH,
    )

    private fun complete(id: String, at: Long, size: Long) = manifest(id, at, size, incomplete = null)

    /** A run that finished but could not read part of its source — complete, and lossy (task-63). */
    private fun withFailures(id: String, at: Long, failed: Int) = manifest(id, at, size = 500, incomplete = null)
        .copy(
            rootEntry = DirEntry(
                name = "",
                type = EntryType.DIRECTORY,
                permissions = 493,
                modTime = Instant.ofEpochSecond(at),
                objectId = "kdeadbeef",
                dirSummary = DirectorySummary(totalFileCount = 945, totalDirCount = 13, fatalErrorCount = failed),
            ),
        )

    private fun incomplete(id: String, at: Long, size: Long) = manifest(id, at, size, incomplete = "canceled")

    private fun manifest(id: String, at: Long, size: Long, incomplete: String?) = SnapshotManifest(
        id = hexId(id),
        source = SourceInfo(host = HOST, userName = USER, path = PATH),
        startTime = Instant.ofEpochSecond(at),
        endTime = Instant.ofEpochSecond(at + 1),
        stats = SnapshotStats(totalFileSize = size, totalFileCount = 1),
        incompleteReason = incomplete,
    )

    private companion object {
        const val HOST = "device"
        const val USER = "local"
        const val PATH = "/sdcard/DCIM"
    }
}
