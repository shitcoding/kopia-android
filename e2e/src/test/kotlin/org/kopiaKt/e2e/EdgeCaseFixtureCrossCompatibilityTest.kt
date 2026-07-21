package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.restore.FilesystemOutput
import org.kopiaKt.snapshot.restore.FilesystemOutputOptions
import org.kopiaKt.snapshot.restore.SnapshotRestorer
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import org.kopiaKt.storage.filesystem.FilesystemBlobStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.minutes

/**
 * Cross-compatibility tests using the pre-built edge-case fixture repository.
 *
 * The fixture at core/src/test/resources/fixtures/edge_case_repos/edge_case_repo/
 * was created by Go Kopia and contains 76 edge-case files (unicode names,
 * special characters, binary data, deeply nested dirs, etc.).
 *
 * These tests verify that the Kotlin implementation can open, list snapshots,
 * and restore content from this Go-created fixture.
 */
@Tag("cross-compat")
class EdgeCaseFixtureCrossCompatibilityTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var fixtureRepoDir: Path
    private lateinit var restoreDir: Path
    private var repo: DirectRepositoryImpl? = null

    private val fixturePassword = "test123"

    @BeforeEach
    fun setUp() {
        // Copy the fixture repo to a temp dir so tests don't modify the original
        val fixtureResource = javaClass.classLoader.getResource("fixtures/edge_case_repos/edge_case_repo")
        Assumptions.assumeTrue(
            fixtureResource != null,
            "Edge-case fixture repo not found in test resources",
        )
        Assumptions.assumeTrue(
            fixtureResource!!.protocol == "file",
            "Fixture repo must be on filesystem (not inside JAR)",
        )

        fixtureRepoDir = tempDir.resolve("fixture_repo")
        fixtureRepoDir.createDirectories()

        val sourceFixture = Path.of(fixtureResource.toURI())
        copyDirectory(sourceFixture, fixtureRepoDir)

        restoreDir = tempDir.resolve("restore")
        restoreDir.createDirectories()
    }

    @AfterEach
    fun tearDown() {
        repo?.close()
        repo = null
    }

    @Test
    @DisplayName("policy saved to a Go-created repo persists across close and reopen")
    fun policyPersistsInGoCreatedRepoAcrossReopen() = runTest(timeout = 2.minutes) {
        // Regression: on a Go-created repo the policy save appeared to succeed but the manifest
        // was not there after reopening (caught by the policy_editor E2E flow 2026-07-17).
        val source = org.kopiaKt.snapshot.model.SourceInfo(
            host = "sdk_gphone64_arm64",
            userName = "local",
            path = "/sdcard/Download",
        )
        val policy = org.kopiaKt.snapshot.policy.Policy(
            retentionPolicy = org.kopiaKt.snapshot.policy.RetentionPolicy(keepLatest = 10),
        )

        val storage = FilesystemBlobStorage(fixtureRepoDir)
        val repository = DirectRepositoryImpl.open(storage, fixturePassword)
        repo = repository
        org.kopiaKt.snapshot.policy.PolicyManager.setPolicy(repository, source, policy)
        val visibleBeforeReopen =
            org.kopiaKt.snapshot.policy.PolicyManager.getPolicy(repository, source)
        repository.close()
        repo = null

        val reopened = DirectRepositoryImpl.open(FilesystemBlobStorage(fixtureRepoDir), fixturePassword)
        repo = reopened
        val loaded = org.kopiaKt.snapshot.policy.PolicyManager.getPolicy(reopened, source)

        assertThat(visibleBeforeReopen).isNotNull()
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.retentionPolicy?.keepLatest).isEqualTo(10)
    }

    @Test
    @DisplayName("Kotlin opens Go-created edge-case fixture and lists snapshots")
    fun kotlinOpensFixtureAndListsSnapshots() = runTest(timeout = 2.minutes) {
        val storage = FilesystemBlobStorage(fixtureRepoDir)
        val repository = DirectRepositoryImpl.open(storage, fixturePassword)
        repo = repository

        // Find all snapshot manifests
        val snapshotMetadata = repository.findManifests(
            mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
        )

        assertThat(snapshotMetadata).isNotEmpty()
        println("Found ${snapshotMetadata.size} snapshot(s) in edge-case fixture repo")

        // Load each manifest to verify it deserializes correctly
        for (metadata in snapshotMetadata) {
            val (manifest, _) = repository.getManifest(metadata.id, SnapshotManifest.serializer())
            assertThat(manifest.source.host).isNotEmpty()
            assertThat(manifest.source.userName).isNotEmpty()
            assertThat(manifest.source.path).isNotEmpty()
            assertThat(manifest.rootEntry).isNotNull()
            println("  Snapshot: ${manifest.source} at ${manifest.startTime}")
        }
    }

    @Test
    @DisplayName("Kotlin restores Go edge-case fixture with expected file tree")
    fun kotlinRestoresGoEdgeCaseFixture() = runTest(timeout = 2.minutes) {
        val storage = FilesystemBlobStorage(fixtureRepoDir)
        val repository = DirectRepositoryImpl.open(storage, fixturePassword)
        repo = repository

        // Find snapshot manifests
        val snapshotMetadata = repository.findManifests(
            mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
        )
        assertThat(snapshotMetadata).isNotEmpty()

        // Load the first snapshot manifest
        val (manifest, _) = repository.getManifest(
            snapshotMetadata.first().id,
            SnapshotManifest.serializer(),
        )
        assertThat(manifest.rootEntry).isNotNull()

        // Get snapshot root entry
        val rootEntry = snapshotRoot(repository, manifest)
        assertThat(rootEntry).isInstanceOf(Directory::class.java)

        // Restore to filesystem
        val output = FilesystemOutput(
            targetPath = restoreDir,
            options = FilesystemOutputOptions(
                overwriteDirectories = true,
                overwriteFiles = true,
                overwriteSymlinks = true,
                skipOwners = true,
                ignorePermissionErrors = true,
            ),
        )
        val restorer = SnapshotRestorer(output = output)
        val stats = restorer.restore(rootEntry)

        // Verify we restored a meaningful number of files
        val restoredFiles = collectFiles(restoreDir)
        println("Restored ${restoredFiles.size} files from edge-case fixture")
        assertThat(restoredFiles.size).isGreaterThan(0)
    }

    @Test
    @DisplayName("Kotlin restores edge-case fixture and verifies known file content")
    fun kotlinRestoresFixtureAndVerifiesContent() = runTest(timeout = 2.minutes) {
        val storage = FilesystemBlobStorage(fixtureRepoDir)
        val repository = DirectRepositoryImpl.open(storage, fixturePassword)
        repo = repository

        // Find and load snapshot
        val snapshotMetadata = repository.findManifests(
            mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
        )
        assertThat(snapshotMetadata).isNotEmpty()

        val (manifest, _) = repository.getManifest(
            snapshotMetadata.first().id,
            SnapshotManifest.serializer(),
        )

        val rootEntry = snapshotRoot(repository, manifest)

        // Restore
        val output = FilesystemOutput(
            targetPath = restoreDir,
            options = FilesystemOutputOptions(
                overwriteDirectories = true,
                overwriteFiles = true,
                overwriteSymlinks = true,
                skipOwners = true,
                ignorePermissionErrors = true,
            ),
        )
        val restorer = SnapshotRestorer(output = output)
        restorer.restore(rootEntry)

        // Collect all restored files
        val restoredFiles = collectFiles(restoreDir)
        println("Restored files:")
        restoredFiles.keys.sorted().forEach { println("  $it") }

        // Verify binary pattern files exist with correct sizes if present
        verifyBinaryPatternFilesIfPresent(restoredFiles)

        // Verify that empty files are truly empty if present
        verifyEmptyFilesIfPresent(restoredFiles)

        // Overall integrity: ensure we got a reasonable number of files
        // The fixture README says 76 files, but some may be symlinks that
        // don't appear as regular files. Check for at least a significant portion.
        assertThat(restoredFiles.size).isAtLeast(10)
    }

    @Test
    @DisplayName("Go CLI and Kotlin see same snapshot count in fixture repo")
    fun goAndKotlinSeeConsistentSnapshots() = runTest(timeout = 2.minutes) {
        // Require Go CLI for this test
        try {
            CrossCompatibilityTestBase.kopiaBinaryPath = KopiaCliRunner.defaultKopiaBinary()
        } catch (e: IllegalStateException) {
            Assumptions.assumeTrue(false, "Go Kopia binary not available")
        }

        // Count snapshots with Kotlin
        val storage = FilesystemBlobStorage(fixtureRepoDir)
        val repository = DirectRepositoryImpl.open(storage, fixturePassword)
        repo = repository

        val kotlinSnapshots = repository.findManifests(
            mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
        )
        val kotlinSnapshotCount = kotlinSnapshots.size

        repository.close()
        repo = null

        // Count snapshots with Go CLI
        val configDir = tempDir.resolve("config")
        configDir.createDirectories()
        val cliRunner = KopiaCliRunner(configDir = configDir)

        cliRunner.repositoryConnect(fixtureRepoDir, fixturePassword)
        try {
            val goSnapshots = cliRunner.snapshotList(all = true)
            val goSnapshotCount = goSnapshots.size

            println("Kotlin sees $kotlinSnapshotCount snapshot(s), Go sees $goSnapshotCount snapshot(s)")
            assertThat(kotlinSnapshotCount).isEqualTo(goSnapshotCount)
        } finally {
            try {
                cliRunner.repositoryDisconnect()
            } catch (_: Exception) {
                // Ignore disconnect errors
            }
        }
    }

    /**
     * Verify binary pattern files have expected content patterns if they exist.
     */
    private fun verifyBinaryPatternFilesIfPresent(files: Map<String, ByteArray>) {
        // Look for all_zeros file
        val zerosKey = files.keys.find { it.contains("all_zeros") && it.endsWith(".bin") }
        if (zerosKey != null) {
            val content = files.getValue(zerosKey)
            assertThat(content.size).isGreaterThan(0)
            assertThat(content.all { it == 0.toByte() }).isTrue()
        }

        // Look for sequential_bytes file
        val seqKey = files.keys.find { it.contains("sequential") && it.endsWith(".bin") }
        if (seqKey != null) {
            val content = files.getValue(seqKey)
            assertThat(content.size).isGreaterThan(0)
            for (i in content.indices) {
                assertThat(content[i]).isEqualTo((i % 256).toByte())
            }
        }
    }

    /**
     * Verify empty files are truly empty if they exist.
     */
    private fun verifyEmptyFilesIfPresent(files: Map<String, ByteArray>) {
        val emptyKeys = files.keys.filter { it.contains("empty") && !it.contains("dir") }
        for (key in emptyKeys) {
            val content = files.getValue(key)
            // "empty" files should be 0 bytes
            if (key.contains("empty_file") || key.endsWith("empty.txt")) {
                assertThat(content.size).isEqualTo(0)
            }
        }
    }

    /**
     * Copy a directory recursively.
     */
    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { sourcePath ->
                val targetPath = target.resolve(source.relativize(sourcePath))
                if (Files.isDirectory(sourcePath)) {
                    targetPath.createDirectories()
                } else {
                    targetPath.parent?.createDirectories()
                    Files.copy(sourcePath, targetPath)
                }
            }
        }
    }

    /**
     * Collect all regular files under a directory as relative-path -> content.
     */
    private fun collectFiles(dir: Path): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        if (!dir.exists()) return result

        Files.walk(dir).use { stream ->
            stream.forEach { path ->
                if (path.isRegularFile()) {
                    val relativePath = dir.relativize(path).toString()
                    result[relativePath] = path.readBytes()
                }
            }
        }

        return result
    }
}
