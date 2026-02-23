package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.kopiaKt.core.format.EpochParameters
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.writeSession
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.restore.CountingRestoreProgress
import org.kopiaKt.snapshot.restore.FilesystemOutput
import org.kopiaKt.snapshot.restore.FilesystemOutputOptions
import org.kopiaKt.snapshot.restore.RestoreOptions
import org.kopiaKt.snapshot.restore.SnapshotRestorer
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions
import java.security.SecureRandom
import java.util.stream.Stream
import kotlin.time.Duration.Companion.minutes

/**
 * Parameterized cross-compatibility tests that verify Kotlin<->Go interop
 * across different hash algorithm and index version combinations.
 *
 * Matrix:
 *   Hash algorithms: BLAKE2B-256-128, BLAKE3-256, BLAKE2B-256-256
 *   Encryption: AES256-GCM-HMAC-SHA256 (only implemented algorithm)
 *   Index versions: V1, V2
 *
 * This produces 6 combinations (3 hash x 2 index versions), each tested
 * for both Kotlin->Go and Go->Kotlin directions (12 total test executions).
 */
@Tag("cross-compat")
class AlgorithmIndexMatrixCompatibilityTest : CrossCompatibilityTestBase() {

    /**
     * Describes a single combination of hash algorithm, encryption, and index version.
     */
    data class AlgorithmConfig(
        val hashAlgorithm: String,
        val encryptionAlgorithm: String = "AES256-GCM-HMAC-SHA256",
        val indexVersion: Int
    ) {
        override fun toString() = "$hashAlgorithm-idx$indexVersion"

        /** Go kopia format version that corresponds to this index version. */
        val goFormatVersion: Int
            get() = if (indexVersion == 1) 1 else 3
    }

    @AfterEach
    fun tearDown() = runTest {
        cleanup()
    }

    // =====================================================================
    // Kotlin -> Go: create repo and snapshot with Kotlin, verify with Go CLI
    // =====================================================================

    @ParameterizedTest(name = "Kotlin->Go snapshot interop [{0}]")
    @MethodSource("algorithmMatrix")
    @DisplayName("Kotlin-created snapshot readable by Go CLI")
    fun kotlinToGo_snapshotInterop(config: AlgorithmConfig) = runTest(timeout = 5.minutes) {
        requireGoKopia()

        // Create small test data to keep execution fast across 6 matrix entries
        testDataGenerator.createSimpleDirectory(sourceDir)

        // Create repository with Kotlin using the given algorithm config
        val repo = createKotlinRepoWithConfig(config)
        repo.use {
            uploadSnapshotWithKotlin(repo)
        }

        // Connect Go CLI and verify it can read the repository
        cliRunner.repositoryConnect(repoDir, testPassword)

        val status = cliRunner.repositoryStatus()
        assertThat(status.success).isTrue()

        // List snapshots - Go should see the Kotlin-created snapshot
        val snapshots = cliRunner.snapshotList(all = true)
        assertThat(snapshots).isNotEmpty()

        val paths = snapshots.map { it.source?.path }
        assertThat(paths).contains(sourceDir.toString())

        // Verify snapshot integrity with Go CLI
        val verifyResult = cliRunner.run("snapshot", "verify")
        assertThat(verifyResult.success).isTrue()

        // Restore and compare byte-for-byte
        val snapshotId = snapshots.first().rootEntry?.obj ?: snapshots.first().id
        assertThat(snapshotId).isNotNull()

        cliRunner.snapshotRestore(snapshotId!!, restoreDir)

        val comparison = compareDirectories(sourceDir, restoreDir)
        if (!comparison.identical) {
            throw AssertionError(
                "Restored content does not match original for config $config: $comparison"
            )
        }
    }

    // =====================================================================
    // Go -> Kotlin: create repo and snapshot with Go CLI, read with Kotlin
    // =====================================================================

    @ParameterizedTest(name = "Go->Kotlin repository open [{0}]")
    @MethodSource("algorithmMatrix")
    @DisplayName("Go-created snapshot readable by Kotlin")
    fun goToKotlin_repositoryOpen(config: AlgorithmConfig) = runTest(timeout = 5.minutes) {
        requireGoKopia()

        // Create small test data
        testDataGenerator.createSimpleDirectory(sourceDir)

        // Create repository with Go CLI using the given algorithm config
        createGoRepoWithConfig(config)
        cliRunner.repositoryConnect(repoDir, testPassword)
        cliRunner.snapshotCreate(sourceDir)
        cliRunner.repositoryDisconnect()

        // Open Go-created repo with Kotlin and restore
        val repo = openRepositoryWithKotlin()
        repo.use {
            // Find snapshot manifest
            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT)
            )
            assertThat(manifests).isNotEmpty()

            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer()
            )
            assertThat(manifest.rootEntry).isNotNull()

            // Restore using Kotlin
            val rootEntry = snapshotRoot(repo, manifest)
            val progress = CountingRestoreProgress()
            val output = FilesystemOutput(
                targetPath = restoreDir,
                options = FilesystemOutputOptions(
                    overwriteDirectories = true,
                    overwriteFiles = true,
                    overwriteSymlinks = true
                )
            )
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
                progress = progress
            )
            val stats = restorer.restore(rootEntry)

            assertThat(stats.ignoredErrorCount).isEqualTo(0)
            assertThat(stats.restoredFileCount).isGreaterThan(0)

            // Compare byte-for-byte
            val comparison = compareDirectories(
                sourceDir,
                restoreDir,
                ComparisonOptions(
                    checkTypes = true,
                    checkSymlinkTargets = true,
                    checkEmptyDirectories = true
                )
            )
            if (!comparison.identical) {
                throw AssertionError(
                    "Restored content does not match original for config $config: $comparison"
                )
            }
        }
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    /**
     * Creates a Kotlin repository with the given algorithm configuration.
     *
     * For index V1: uses format version 1 with epochs disabled.
     * For index V2: uses format version 3 with epochs disabled (Kotlin does
     * not yet implement epoch management, but V2 index format is supported).
     */
    private suspend fun createKotlinRepoWithConfig(config: AlgorithmConfig): DirectRepositoryImpl {
        val storage = createBlobStorage()
        val random = SecureRandom()
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val masterKey = ByteArray(32).also { random.nextBytes(it) }

        val repoConfig = RepositoryConfig(
            hash = config.hashAlgorithm,
            encryption = config.encryptionAlgorithm,
            secret = secret,
            masterKey = masterKey,
            splitter = "FIXED-1M",
            version = if (config.indexVersion == 1) 1 else 3,
            indexVersion = config.indexVersion,
            epochParameters = EpochParameters.DISABLED
        )

        return DirectRepositoryImpl.create(storage, testPassword, repoConfig)
    }

    /**
     * Creates a Go repository with the given algorithm configuration.
     *
     * Uses `--format-version` to control the index version:
     *   format-version=1 -> index V1
     *   format-version=3 -> index V2
     */
    private suspend fun createGoRepoWithConfig(config: AlgorithmConfig) {
        cliRunner.run(
            "repository", "create", "filesystem",
            "--path=${repoDir}",
            "--password=${testPassword}",
            "--block-hash=${config.hashAlgorithm}",
            "--encryption=${config.encryptionAlgorithm}",
            "--format-version=${config.goFormatVersion}"
        ).requireSuccess()
    }

    /**
     * Uploads a snapshot of sourceDir using the Kotlin implementation.
     */
    private suspend fun uploadSnapshotWithKotlin(repo: DirectRepositoryImpl) {
        writeSession(repo) { writer ->
            val source = SourceInfo(
                host = "test-host",
                userName = "test-user",
                path = sourceDir.toString()
            )

            val progress = CountingUploadProgress()
            val uploader = SnapshotUploader(
                writer = writer,
                source = source,
                policy = Policy(),
                progress = progress
            )

            val rootDir = LocalFilesystem.directory(sourceDir)
            val uploadResult = uploader.upload(rootDir, UploadOptions())
            assertThat(uploadResult.incomplete).isFalse()
        }
    }

    companion object {
        /**
         * Generates the algorithm/index-version matrix for parameterized tests.
         *
         * 3 hash algorithms x 2 index versions = 6 combinations.
         */
        @JvmStatic
        fun algorithmMatrix(): Stream<AlgorithmConfig> = Stream.of(
            // BLAKE2B-256-128 (default hash)
            AlgorithmConfig(hashAlgorithm = "BLAKE2B-256-128", indexVersion = 1),
            AlgorithmConfig(hashAlgorithm = "BLAKE2B-256-128", indexVersion = 2),

            // BLAKE3-256
            AlgorithmConfig(hashAlgorithm = "BLAKE3-256", indexVersion = 1),
            AlgorithmConfig(hashAlgorithm = "BLAKE3-256", indexVersion = 2),

            // BLAKE2B-256-256 (full-length BLAKE2B)
            AlgorithmConfig(hashAlgorithm = "BLAKE2B-256-256", indexVersion = 1),
            AlgorithmConfig(hashAlgorithm = "BLAKE2B-256-256", indexVersion = 2)
        )
    }
}
