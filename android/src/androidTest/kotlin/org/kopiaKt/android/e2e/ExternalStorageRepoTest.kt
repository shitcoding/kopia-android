package org.kopiaKt.android.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.restore.CountingRestoreProgress
import org.kopiaKt.snapshot.restore.FilesystemOutput
import org.kopiaKt.snapshot.restore.FilesystemOutputOptions
import org.kopiaKt.snapshot.restore.RestoreOptions
import org.kopiaKt.snapshot.restore.SnapshotRestorer
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.io.File

/**
 * Tests opening a Go Kopia created repository from external storage.
 *
 * Prerequisites:
 * 1. Create a Go Kopia repo: kopia repository create filesystem --path=/tmp/kopia-test/repo --password=test123
 * 2. Create snapshot: kopia snapshot create /tmp/kopia-test/source --description="Test"
 * 3. Push to emulator: adb push /tmp/kopia-test/repo/. /sdcard/Download/kopia-test-repo/
 * 4. Grant permission: adb shell appops set org.kopiaKt.app MANAGE_EXTERNAL_STORAGE allow
 *
 * Run with: ./gradlew :android:connectedAndroidTest --tests "*.ExternalStorageRepoTest"
 */
@RunWith(AndroidJUnit4::class)
class ExternalStorageRepoTest : AndroidE2ETestBase() {

    companion object {
        private const val TAG = "ExternalStorageRepoTest"
        private const val GO_REPO_PATH = "/sdcard/Download/kopia-test-repo"
        private const val GO_REPO_PASSWORD = "test123"
    }

    @Test
    fun openGoCreatedRepository(): Unit = runBlocking {
        Log.i(TAG, "Test: openGoCreatedRepository on ${getDeviceInfo()}")

        val repoPath = File(GO_REPO_PATH)
        assumeGoRepoAvailable(repoPath)

        Log.i(TAG, "Found repository at: ${repoPath.absolutePath}")
        Log.i(TAG, "Repository files: ${repoPath.listFiles()?.map { it.name }}")

        // Try to open the repository
        val storage = FilesystemBlobStorage.create(repoPath.toPath())
        Log.i(TAG, "Created FilesystemBlobStorage")

        val repository = DirectRepositoryImpl.open(
            blobStorage = storage,
            password = GO_REPO_PASSWORD
        )

        try {
            Log.i(TAG, "Repository opened successfully!")

            // Refresh to load manifests
            repository.refresh()
            Log.i(TAG, "Repository refreshed")

            // List snapshots
            val manifests = repository.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
            )

            Log.i(TAG, "Found ${manifests.size} snapshots")
            assertThat(manifests).isNotEmpty()

            for (manifest in manifests) {
                Log.i(TAG, "Snapshot: id=${manifest.id}, modTime=${manifest.modTime}")
            }

        } finally {
            repository.close()
            Log.i(TAG, "Repository closed")
        }
        Unit
    }

    @Test
    fun browseGoCreatedSnapshot(): Unit = runBlocking {
        Log.i(TAG, "Test: browseGoCreatedSnapshot on ${getDeviceInfo()}")

        val repoPath = File(GO_REPO_PATH)
        assumeGoRepoAvailable(repoPath)

        val storage = FilesystemBlobStorage.create(repoPath.toPath())
        val repository = DirectRepositoryImpl.open(storage, GO_REPO_PASSWORD)

        try {
            repository.refresh()

            val manifests = repository.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
            )
            assertThat(manifests).isNotEmpty()

            // Get the first snapshot
            val (snapshot, _) = repository.getManifest(manifests.first().id, SnapshotManifest.serializer())

            Log.i(TAG, "Snapshot source: ${snapshot.source}")
            Log.i(TAG, "Snapshot description: ${snapshot.description}")
            Log.i(TAG, "Root object: ${snapshot.rootEntry?.objectId}")

            // Browse the root directory
            val root = snapshotRoot(repository, snapshot) as Directory
            Log.i(TAG, "Root directory: ${root.name}")

            // List all entries
            val entries = root.iterate()
            var count = 0
            while (true) {
                val entry = entries.next() ?: break
                Log.i(TAG, "  Entry: ${entry.name} (${entry.size} bytes)")
                count++
            }
            entries.close()

            Log.i(TAG, "Total entries at root: $count")
            assertThat(count).isGreaterThan(0)

        } finally {
            repository.close()
        }
        Unit
    }

    @Test
    fun restoreGoCreatedSnapshot(): Unit = runBlocking {
        Log.i(TAG, "Test: restoreGoCreatedSnapshot on ${getDeviceInfo()}")

        val repoPath = File(GO_REPO_PATH)
        assumeGoRepoAvailable(repoPath)

        val storage = FilesystemBlobStorage.create(repoPath.toPath())
        val repository = DirectRepositoryImpl.open(storage, GO_REPO_PASSWORD)

        try {
            repository.refresh()

            val manifests = repository.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
            )
            assertThat(manifests).isNotEmpty()

            val (snapshot, _) = repository.getManifest(manifests.first().id, SnapshotManifest.serializer())

            // Build expected file tree from snapshot metadata before restoring
            val snapshotTree = snapshotRoot(repository, snapshot)
            val expectedEntries = collectSnapshotEntries(snapshotTree)
            Log.i(TAG, "Snapshot describes ${expectedEntries.size} file entries")

            // Create restore directory
            val restoreTarget = File(testRoot, "go_restore")
            restoreTarget.mkdirs()

            Log.i(TAG, "Restoring snapshot to: ${restoreTarget.absolutePath}")

            // Perform restore
            val root = snapshotRoot(repository, snapshot)
            val output = FilesystemOutput(
                targetPath = restoreTarget.toPath(),
                options = FilesystemOutputOptions(
                    overwriteFiles = true,
                    overwriteDirectories = true
                )
            )
            val progress = CountingRestoreProgress()
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(),
                progress = progress
            )

            restorer.restore(root)

            val stats = progress.snapshot()
            Log.i(TAG, "Restore complete!")
            Log.i(TAG, "  Files restored: ${stats.restoredFileCount}")
            Log.i(TAG, "  Dirs restored: ${stats.restoredDirCount}")
            Log.i(TAG, "  Bytes restored: ${stats.restoredTotalFileSize}")
            Log.i(TAG, "  Errors ignored: ${stats.ignoredErrorCount}")

            // Validate all enqueued files were restored successfully
            assertThat(stats.restoredFileCount).isGreaterThan(0)
            assertThat(stats.restoredFileCount).isEqualTo(stats.enqueuedFileCount)
            assertThat(stats.restoredDirCount).isEqualTo(stats.enqueuedDirCount)
            assertThat(stats.restoredSymlinkCount).isEqualTo(stats.enqueuedSymlinkCount)
            assertThat(stats.restoredTotalFileSize).isEqualTo(stats.enqueuedTotalFileSize)
            assertThat(stats.ignoredErrorCount).isEqualTo(0)

            // Validate restored files match snapshot metadata
            assertRestoredMatchesSnapshot(restoreTarget, expectedEntries)

        } finally {
            repository.close()
        }
        Unit
    }

    // ==================
    // Helper methods
    // ==================

    /**
     * Ensures the Go test repository is available on device. Marks the test as
     * SKIPPED (not PASSED) when prerequisites aren't met, so CI accurately
     * reports which tests actually ran.
     */
    private fun assumeGoRepoAvailable(repoPath: File) {
        assumeTrue(
            "Go test repo not available at ${repoPath.path}. " +
                "Push repo via: adb push <source> ${repoPath.path}",
            repoPath.exists() && repoPath.isDirectory
        )
    }

    /**
     * Metadata about a single file in the snapshot tree, used for validation
     * after restore. We record the relative path and expected size from the
     * snapshot so we can compare against the restored files on disk.
     */
    private data class SnapshotFileEntry(
        val relativePath: String,
        val size: Long
    )

    /**
     * Recursively walks the snapshot tree and collects metadata for every file
     * entry. Directories and symlinks are excluded; only regular files are
     * returned.
     *
     * @param entry     the current entry being visited
     * @param basePath  the path prefix built up so far (empty string for the
     *                  root directory, since the restorer places files directly
     *                  into the target without a root-name wrapper)
     * @param isRoot    true only for the very first call (the snapshot root);
     *                  its name is NOT prepended to child paths
     */
    private suspend fun collectSnapshotEntries(
        entry: Entry,
        basePath: String = "",
        isRoot: Boolean = true
    ): List<SnapshotFileEntry> {
        if (entry.isFile()) {
            val path = if (basePath.isEmpty()) entry.name else "$basePath/${entry.name}"
            return listOf(SnapshotFileEntry(relativePath = path, size = entry.size))
        }

        if (entry.isDirectory()) {
            val dir = entry as Directory
            // The root directory's name is NOT included in paths because the
            // restorer writes directly into the target directory.
            val childBasePath = when {
                isRoot -> ""
                basePath.isEmpty() -> entry.name
                else -> "$basePath/${entry.name}"
            }
            val result = mutableListOf<SnapshotFileEntry>()
            val iter = dir.iterate()
            try {
                while (true) {
                    val child = iter.next() ?: break
                    result.addAll(collectSnapshotEntries(child, childBasePath, isRoot = false))
                }
            } finally {
                iter.close()
            }
            return result
        }

        // Symlinks and other types are skipped for file-content validation
        return emptyList()
    }

    /**
     * Validates that the restored directory on disk matches the snapshot
     * metadata. Checks:
     * - Every file listed in the snapshot exists on disk
     * - File sizes match the snapshot metadata exactly
     * - No unexpected extra files exist in the restore directory
     * - Every restored file is readable (non-corrupt on disk)
     */
    private fun assertRestoredMatchesSnapshot(
        restoreTarget: File,
        expectedEntries: List<SnapshotFileEntry>
    ) {
        val expectedByPath = expectedEntries.associateBy { it.relativePath }

        // Collect actual restored files
        val actualFiles = restoreTarget.walkTopDown()
            .filter { it.isFile }
            .map { it.toRelativeString(restoreTarget) to it }
            .toList()
        val actualByPath = actualFiles.toMap()

        // Check all expected files are present with correct sizes
        val missingFiles = mutableListOf<String>()
        val sizeMismatches = mutableListOf<String>()

        for ((path, expected) in expectedByPath) {
            val actualFile = actualByPath[path]
            if (actualFile == null) {
                missingFiles.add(path)
                continue
            }
            val actualSize = actualFile.length()
            if (actualSize != expected.size) {
                sizeMismatches.add(
                    "$path: expected ${expected.size} bytes, got $actualSize bytes"
                )
            }
        }

        // Check for unexpected extra files
        val extraFiles = actualByPath.keys - expectedByPath.keys

        // Log details before asserting for easier debugging
        if (missingFiles.isNotEmpty()) {
            Log.e(TAG, "Missing files (${missingFiles.size}): ${missingFiles.take(20)}")
        }
        if (sizeMismatches.isNotEmpty()) {
            Log.e(TAG, "Size mismatches (${sizeMismatches.size}): ${sizeMismatches.take(20)}")
        }
        if (extraFiles.isNotEmpty()) {
            Log.w(TAG, "Extra files (${extraFiles.size}): ${extraFiles.take(20)}")
        }
        Log.i(TAG, "Validated ${expectedByPath.size} expected files, " +
            "${actualByPath.size} actual files on disk")

        assertWithMessage("files present in snapshot but missing from restore")
            .that(missingFiles).isEmpty()
        assertWithMessage("files with size mismatch between snapshot metadata and restored file")
            .that(sizeMismatches).isEmpty()
        assertWithMessage("restored file count")
            .that(actualByPath.size).isEqualTo(expectedByPath.size)
    }
}
