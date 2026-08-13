package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.DirectRepository
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.maintenance.MaintenanceRunner
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.testutil.MockDirectory
import org.kopiaKt.snapshot.testutil.SlowMockFile
import java.time.Duration

/**
 * Phase 3.2 — a long upload must leave a real partial tree in the repository, not just at the end.
 *
 * Kotlin used to flush exactly once, at the very end of `upload()`. On Android the abrupt stop is
 * the common case — process death, swipe-away, the 6h foreground-service cap — and every one of
 * them lost the whole run and left the uploaded pack blobs unreferenced.
 */
class RepositoryCheckpointTest {

    private val source = SourceInfo(host = "phone", userName = "local", path = "/sdcard/DCIM")

    private fun slowTree(fileCount: Int = 6, delayMs: Long = 120) = MockDirectory(
        name = "DCIM",
        entries = (1..fileCount).map { i ->
            SlowMockFile("f$i.bin", ByteArray(2048) { (i + it).toByte() }, delayMs = delayMs)
        },
    )

    private val checkpointing = UploadOptions(
        parallelUploads = 1,
        checkpointInterval = Duration.ofMillis(100),
    )

    @Test
    fun `a long upload writes checkpoint snapshots into the repository`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()

        val result = upload(repository, slowTree(), checkpointing)

        val checkpoints = snapshots(repository).filter { it.incompleteReason == CHECKPOINT_REASON }
        assertThat(checkpoints).isNotEmpty()
        assertThat(checkpoints.first().rootEntry).isNotNull()
        assertThat(result.incomplete).isFalse()
        repository.close()
    }

    @Test
    fun `a checkpoint is dated one nanosecond before the snapshot it belongs to`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()

        val result = upload(repository, slowTree(), checkpointing)

        // Go dates checkpoints StartTime-1ns precisely so the final snapshot's retention pass reaps
        // them: they sort older than the run that produced them.
        val checkpoints = snapshots(repository).filter { it.incompleteReason == CHECKPOINT_REASON }
        assertThat(checkpoints).isNotEmpty()
        checkpoints.forEach {
            assertThat(it.startTime).isEqualTo(result.manifest.startTime.minusNanos(1))
        }
        repository.close()
    }

    @Test
    fun `a checkpointed root is a readable partial tree`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()

        upload(repository, slowTree(), checkpointing)

        val trees = snapshots(repository)
            .filter { it.incompleteReason == CHECKPOINT_REASON }
            .mapNotNull { it.rootEntry?.objectId }
            .map { readDirManifest(repository, it) }

        assertThat(trees).isNotEmpty()
        // Partial by construction: written while the walk is still running, and saying so, so
        // nothing downstream can mistake it for a finished directory.
        trees.forEach { assertThat(it.summary?.incompleteReason).isEqualTo(CHECKPOINT_REASON) }
        // The earliest checkpoint can legitimately be empty — nothing had finished yet — but by the
        // time the walk is a few files in, the tree must actually name the work already uploaded.
        assertThat(trees.any { it.entries.isNotEmpty() }).isTrue()
        repository.close()
    }

    @Test
    fun `a finished run's retention pass reaps its own checkpoints`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()

        upload(repository, slowTree(), checkpointing)
        assertThat(snapshots(repository).filter { it.incompleteReason == CHECKPOINT_REASON }).isNotEmpty()

        // This is what the startTime-1ns dating is FOR, end to end: Go's incomplete rule walks
        // newest-first and stops at the first complete snapshot, so the checkpoints only get reaped
        // if they sort behind the run that produced them. Get the dating wrong and every completed
        // backup leaves its checkpoints in the user's snapshot list forever.
        MaintenanceRunner(repository).applyRetention(source)

        val remaining = snapshots(repository)
        assertThat(remaining.filter { it.incompleteReason == CHECKPOINT_REASON }).isEmpty()
        assertThat(remaining.filter { it.incompleteReason == null }).hasSize(1)
        repository.close()
    }

    @Test
    fun `a checkpoint mid-file references the bytes already uploaded`(): Unit = runBlocking(Dispatchers.Default) {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        // Bigger than the default splitter's 8 MiB forced-split ceiling, so chunks are certain to be
        // flushed while the write is still going, and read slowly enough to span a checkpoint.
        val content = ByteArray(12 * 1024 * 1024) { (it * 31).toByte() }
        val tree = MockDirectory("DCIM", listOf(SlowStreamFile("video.bin", content, chunkDelayMs = 100)))

        val slowly = UploadOptions(parallelUploads = 1, checkpointInterval = Duration.ofMillis(300))
        val result = upload(repository, tree, slowly)

        // Go renames these to ".checkpointed.<name>.<uuid>" so nothing can read a truncated file
        // back as the whole file. Their entire job is to keep the bytes REFERENCED, so that a
        // checkpoint's flush does not commit gigabytes of a video into pack blobs the tree never
        // points at — orphaned until a desktop runs maintenance, and re-uploaded by the retry.
        val partials = snapshots(repository)
            .filter { it.incompleteReason == CHECKPOINT_REASON }
            .mapNotNull { it.rootEntry?.objectId }
            .flatMap { readDirManifest(repository, it).entries }
            .filter { it.name.startsWith(".checkpointed.video.bin.") }

        assertThat(partials).isNotEmpty()
        val partialBytes = repository.readObject(ObjectId.parse(partials.first().objectId!!))
        assertThat(partialBytes.size).isLessThan(content.size)
        assertThat(partialBytes).isEqualTo(content.copyOf(partialBytes.size))
        // And the finished file is whole, not the checkpointed prefix.
        val whole = readDirManifest(repository, result.manifest.rootEntry!!.objectId!!).entries.single()
        assertThat(whole.name).isEqualTo("video.bin")
        assertThat(repository.readObject(ObjectId.parse(whole.objectId!!)).size).isEqualTo(content.size)
        repository.close()
    }

    @Test
    fun `dedup survives the flushes a checkpoint performs mid-upload`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()
        val identical = ByteArray(4096) { 42 }
        val tree = MockDirectory(
            name = "DCIM",
            entries = (1..6).map { i -> SlowMockFile("copy$i.bin", identical.copyOf(), delayMs = 120) },
        )

        // Every checkpoint flushes the pending pack and its index while the walk is still writing
        // into that same session. If a flush lost or duplicated content, the files either side of it
        // would stop sharing an object id — the first thing that breaks, and silently.
        val parallel = UploadOptions(parallelUploads = 4, checkpointInterval = Duration.ofMillis(100))
        val result = upload(repository, tree, parallel)

        assertThat(snapshots(repository).filter { it.incompleteReason == CHECKPOINT_REASON }).isNotEmpty()
        val root = readDirManifest(repository, result.manifest.rootEntry!!.objectId!!)
        assertThat(root.entries).hasSize(6)
        assertThat(root.entries.map { it.objectId }.toSet()).hasSize(1)
        // And the deduped object still reads back as the bytes that went in.
        assertThat(repository.readObject(ObjectId.parse(root.entries.first().objectId!!)))
            .isEqualTo(identical)
        repository.close()
    }

    @Test
    fun `a short upload writes no checkpoint at all`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory()

        val neverFires = UploadOptions(checkpointInterval = Duration.ofHours(1))
        upload(repository, slowTree(fileCount = 1, delayMs = 0), neverFires)

        assertThat(snapshots(repository).filter { it.incompleteReason == CHECKPOINT_REASON }).isEmpty()
        repository.close()
    }

    private suspend fun upload(
        repository: DirectRepository,
        root: MockDirectory,
        options: UploadOptions,
    ): UploadResult {
        val writer = repository.newWriter(WriteSessionOptions())
        try {
            return SnapshotUploader(writer = writer, source = source).upload(root, options)
        } finally {
            writer.close()
        }
    }

    private suspend fun snapshots(repository: DirectRepository): List<SnapshotManifest> {
        repository.refresh()
        return repository.findManifests(ManifestLabels.forSnapshot(source)).map {
            repository.getManifest(it.id, SnapshotManifest.serializer()).first
        }
    }

    private suspend fun readDirManifest(repository: DirectRepository, objectId: String): DirManifest {
        val bytes = repository.readObject(ObjectId.parse(objectId))
        return kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(DirManifest.serializer(), bytes.decodeToString())
    }

    /**
     * A file whose CONTENT arrives slowly, unlike [SlowMockFile] which only stalls on `open()`.
     * A mid-file checkpoint needs the write itself to still be in progress. The sleep is blocking,
     * which is why the test that uses it runs on a multi-threaded dispatcher — on `runBlocking`'s
     * single thread it would starve the checkpointer and the test would silently prove nothing.
     */
    private class SlowStreamFile(
        override val name: String,
        private val content: ByteArray,
        private val chunkDelayMs: Long,
    ) : org.kopiaKt.snapshot.fs.File {
        override val type = org.kopiaKt.snapshot.fs.EntryType.FILE
        override val size = content.size.toLong()
        override val modTime: java.time.Instant = java.time.Instant.parse("2026-01-01T00:00:00Z")
        override val mode = 420
        override val owner = org.kopiaKt.snapshot.fs.OwnerInfo.EMPTY
        override val device = org.kopiaKt.snapshot.fs.DeviceInfo.EMPTY
        override val localFilesystemPath = ""

        override suspend fun open(): java.io.InputStream = object : java.io.InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < content.size) content[pos++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= content.size) return -1
                Thread.sleep(chunkDelayMs)
                val n = minOf(len, CHUNK, content.size - pos)
                content.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }

        private companion object {
            const val CHUNK = 512 * 1024
        }
    }

    private companion object {
        const val CHECKPOINT_REASON = "checkpoint"
    }

    @Test
    fun `the checkpoint interval is re-read, so a run can tighten it mid-flight`(): Unit = runBlocking {
        // What a kill throws away is everything since the last REPOSITORY checkpoint, and on Android
        // a run that loses its foreground service becomes killable in minutes rather than hours. The
        // interval used to be captured once, which is why it had to become a supplier: nothing about
        // a Duration read at session start can respond to that (task-60).
        var unprotected = false
        val options = UploadOptions(
            checkpointInterval = Duration.ofMinutes(5),
            checkpointIntervalNow = {
                if (unprotected) Duration.ofSeconds(75) else Duration.ofMinutes(5)
            },
        )

        assertThat(options.effectiveCheckpointInterval()).isEqualTo(Duration.ofMinutes(5))

        unprotected = true

        assertThat(options.effectiveCheckpointInterval()).isEqualTo(Duration.ofSeconds(75))
    }

    @Test
    fun `without a supplier the fixed interval is used, and either way it is clamped`(): Unit = runBlocking {
        assertThat(UploadOptions(checkpointInterval = Duration.ofMinutes(5)).effectiveCheckpointInterval())
            .isEqualTo(Duration.ofMinutes(5))
        // A zero from either source would make the checkpoint loop's delay return immediately.
        assertThat(UploadOptions(checkpointIntervalNow = { Duration.ZERO }).effectiveCheckpointInterval())
            .isEqualTo(UploadOptions.MIN_CHECKPOINT_INTERVAL)
    }
}
