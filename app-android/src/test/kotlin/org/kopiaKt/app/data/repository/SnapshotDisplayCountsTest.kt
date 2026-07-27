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
}
