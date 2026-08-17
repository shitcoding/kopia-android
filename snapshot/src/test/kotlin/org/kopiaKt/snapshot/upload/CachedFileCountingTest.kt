package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * A file is either hashed or reused, never both (task-62).
 *
 * Measured on a phone: backing up the same four-file source twice, the second run's notification said
 * "Backed up 8 files", and the snapshot's `stats` carried the same doubled count into the repository
 * where desktop Kopia sees it. The byte count stayed right, which is what hid it.
 *
 * `TreeWalker.processFileEntry` reported `finishedHashingFile` for **every** file it walked, while
 * `FileUploader.processFile` had already reported `cachedFile` and returned early — so a reused file
 * incremented both counters. Go cannot make this mistake: its cached branch increments `CachedFiles`,
 * emits `CachedFile` and **returns** (`upload.go`'s `processSingle`), and `FinishedHashingFile` is
 * emitted only from the path that actually hashes.
 *
 * So the report belongs to the uploader, which knows which of the two happened — not to the walker,
 * which does not.
 */
@DisplayName("Cached files are counted once (task-62)")
class CachedFileCountingTest {

    /** A real in-memory repository rather than a mock: the counters must survive real hashing. */
    private suspend fun uploaderAndProgress(): Pair<FileUploader, CountingUploadProgress> {
        val (repo, _) = TestRepositoryFactory.createInMemory()
        val writer = repo.newDirectWriter(WriteSessionOptions(purpose = "task-62"))
        val progress = CountingUploadProgress()
        return FileUploader(writer, progress) to progress
    }

    /** The files on disk, as the walker will see them — so the "previous" entries really match. */
    private suspend fun filesIn(dir: Path): List<File> {
        val entries = mutableListOf<File>()
        LocalFilesystem.directory(dir).iterate().use { iterator ->
            while (true) {
                val next = iterator.next() ?: break
                if (next is File) entries.add(next)
            }
        }
        return entries
    }

    private suspend fun previousManifestFor(dir: Path): DirManifest = DirManifest(
        entries = filesIn(dir).map { file ->
            DirEntry(
                name = file.name,
                type = EntryType.FILE,
                permissions = file.mode,
                fileSize = file.size,
                modTime = file.modTime,
                objectId = "previously-uploaded-${file.name}",
            )
        },
    )

    @Test
    fun `a second walk of an unchanged source hashes nothing and counts each file once`(
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        tempDir.resolve("a.txt").writeText("alpha")
        tempDir.resolve("b.txt").writeText("beta")
        val dir = LocalFilesystem.directory(tempDir)

        val (_, coldProgress) = uploaderAndProgress().also { (uploader, progress) ->
            TreeWalker(uploader, progress).walk(dir)
        }
        val cold = coldProgress.snapshot()
        assertThat(cold.totalHashedFiles).isEqualTo(2)
        assertThat(cold.totalCachedFiles).isEqualTo(0)

        // Walk again against entries whose metadata still matches — every file is a cache hit.
        val (warmUploader, warmProgress) = uploaderAndProgress()
        TreeWalker(warmUploader, warmProgress).walk(dir, listOf(previousManifestFor(tempDir)))
        val warm = warmProgress.snapshot()

        assertThat(warm.totalCachedFiles).isEqualTo(2)
        // This was 2 as well, so a two-file source reported four files backed up.
        assertThat(warm.totalHashedFiles).isEqualTo(0)
        assertThat(warm.totalCachedFiles + warm.totalHashedFiles).isEqualTo(2)
    }

    /**
     * The other direction, and the reason the fix is a MOVE rather than a deletion: once the walker
     * stops reporting for everything, the uploader has to report the hashing it actually does, or a
     * double count becomes a zero.
     */
    @Test
    fun `a freshly hashed file is reported by the uploader itself`(@TempDir tempDir: Path): Unit = runBlocking {
        tempDir.resolve("only.txt").writeText("content")
        val (uploader, progress) = uploaderAndProgress()

        uploader.processFile(filesIn(tempDir).single(), "only.txt", emptyList())

        assertThat(progress.snapshot().totalHashedFiles).isEqualTo(1)
        assertThat(progress.snapshot().totalCachedFiles).isEqualTo(0)
    }
}
