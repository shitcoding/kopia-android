package org.kopiaKt.android.e2e

import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.kopiaKt.core.repository.DirectRepositoryImpl
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

        // Check if the repo exists
        if (!repoPath.exists()) {
            Log.w(TAG, "Go-created repository not found at $GO_REPO_PATH. Skipping test.")
            Log.w(TAG, "Please create the repo and push it to the emulator first.")
            return@runBlocking
        }

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
        if (!repoPath.exists()) {
            Log.w(TAG, "Skipping: repository not found at $GO_REPO_PATH")
            return@runBlocking
        }

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
            val root = snapshotRoot(repository, snapshot) as org.kopiaKt.snapshot.fs.Directory
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
        if (!repoPath.exists()) {
            Log.w(TAG, "Skipping: repository not found at $GO_REPO_PATH")
            return@runBlocking
        }

        val storage = FilesystemBlobStorage.create(repoPath.toPath())
        val repository = DirectRepositoryImpl.open(storage, GO_REPO_PASSWORD)

        try {
            repository.refresh()

            val manifests = repository.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
            )
            assertThat(manifests).isNotEmpty()

            val (snapshot, _) = repository.getManifest(manifests.first().id, SnapshotManifest.serializer())

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

            assertThat(stats.restoredFileCount).isGreaterThan(0)

            // Verify restored files
            Log.i(TAG, "Restored files:")
            restoreTarget.walkTopDown().forEach { file ->
                if (file.isFile) {
                    Log.i(TAG, "  ${file.toRelativeString(restoreTarget)}: ${file.length()} bytes")
                }
            }

        } finally {
            repository.close()
        }
        Unit
    }
}
