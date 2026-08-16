package org.kopiaKt.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirectorySummary
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SnapshotStats
import org.kopiaKt.snapshot.model.SourceInfo
import java.time.Instant

/**
 * Go's `stats.fileCount` counts files HASHED during a run, not files in the snapshot. Every
 * incremental snapshot serves most files from cache, so displaying that field showed a tree of 11
 * files as "1 files" on both the sources dashboard and the snapshot list.
 */
@DisplayName("Snapshot display counts")
class SnapshotDisplayCountsTest {

    private fun manifest(
        summary: DirectorySummary?,
        stats: SnapshotStats?,
    ) = SnapshotManifest(
        id = "0123456789abcdef0123456789abcdef",
        source = SourceInfo(host = "laptop", userName = "user", path = "/tmp/user-files"),
        startTime = Instant.parse("2026-07-27T17:26:00Z"),
        stats = stats,
        rootEntry = DirEntry(
            name = "",
            type = EntryType.DIRECTORY,
            permissions = 493,
            modTime = Instant.parse("2026-07-27T17:26:00Z"),
            objectId = "kdeadbeef",
            dirSummary = summary,
        ),
    )

    @Test
    fun `prefers the recursive root summary over the files hashed in that run`() {
        val m = manifest(
            summary = DirectorySummary(totalFileCount = 11, totalDirCount = 5),
            // What Go records for an incremental run: 1 hashed, 10 cached.
            stats = SnapshotStats(totalFileCount = 1, totalDirectoryCount = 5),
        )

        assertThat(m.displayFileCount()).isEqualTo(11)
        assertThat(m.displayDirectoryCount()).isEqualTo(5)
    }

    @Test
    fun `falls back to run stats when the snapshot has no root summary`() {
        val m = manifest(
            summary = null,
            stats = SnapshotStats(totalFileCount = 7, totalDirectoryCount = 3),
        )

        assertThat(m.displayFileCount()).isEqualTo(7)
        assertThat(m.displayDirectoryCount()).isEqualTo(3)
    }

    @Test
    fun `reports zero when neither is present`() {
        val m = manifest(summary = null, stats = null)

        assertThat(m.displayFileCount()).isEqualTo(0)
        assertThat(m.displayDirectoryCount()).isEqualTo(0)
    }

    /**
     * task-63, measured on a phone: a source that became unreadable part way through a walk produced
     * a snapshot marked COMPLETE holding 945 of 2004 files, with `numFailed: 1059` recorded in the
     * manifest — and nothing in the app ever said so, because that number stopped at the repository.
     * It was the `latest` snapshot, so it is what "restore the latest backup" hands back.
     */
    @Test
    fun `reports how many entries the run could not read`() {
        val m = manifest(
            summary = DirectorySummary(totalFileCount = 945, totalDirCount = 13, fatalErrorCount = 1059),
            stats = SnapshotStats(totalFileCount = 945, totalDirectoryCount = 13, errorCount = 1059),
        )

        assertThat(m.displayFailedEntryCount()).isEqualTo(1059)
    }

    @Test
    fun `falls back to run stats for the failure count, and reports zero for a clean snapshot`() {
        assertThat(
            manifest(summary = null, stats = SnapshotStats(errorCount = 4)).displayFailedEntryCount(),
        ).isEqualTo(4)

        assertThat(
            manifest(
                summary = DirectorySummary(totalFileCount = 2004),
                stats = SnapshotStats(totalFileCount = 2004),
            ).displayFailedEntryCount(),
        ).isEqualTo(0)

        assertThat(manifest(summary = null, stats = null).displayFailedEntryCount()).isEqualTo(0)
    }
}
