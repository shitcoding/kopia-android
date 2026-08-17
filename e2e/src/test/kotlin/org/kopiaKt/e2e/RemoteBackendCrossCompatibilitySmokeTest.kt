package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.format.EpochParameters
import org.kopiaKt.core.format.MutableParameters
import org.kopiaKt.core.format.RepositoryConfig
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.repository.writeSession
import org.kopiaKt.snapshot.fs.LocalFilesystem
import org.kopiaKt.snapshot.model.ManifestLabels
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy
import org.kopiaKt.snapshot.restore.FilesystemOutput
import org.kopiaKt.snapshot.restore.FilesystemOutputOptions
import org.kopiaKt.snapshot.restore.RestoreOptions
import org.kopiaKt.snapshot.restore.SnapshotRestorer
import org.kopiaKt.snapshot.snapshotfs.snapshotRoot
import org.kopiaKt.snapshot.upload.CountingUploadProgress
import org.kopiaKt.snapshot.upload.SnapshotUploader
import org.kopiaKt.snapshot.upload.UploadOptions
import org.kopiaKt.storage.s3.S3BlobStorage
import org.kopiaKt.storage.s3.S3Options
import org.kopiaKt.storage.sftp.SftpBlobStorage
import org.kopiaKt.storage.sftp.SftpOptions
import org.kopiaKt.storage.webdav.WebDavBlobStorage
import org.kopiaKt.storage.webdav.WebDavOptions
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.absolutePathString
import kotlin.io.path.readBytes

/**
 * Cross-compatibility smoke tests for remote storage backends (S3, WebDAV, SFTP).
 *
 * Uses Testcontainers to spin up MinIO, a WebDAV server, and an SFTP server.
 * Verifies that:
 *   - Repositories created by Go CLI on a remote backend can be read by Kotlin
 *   - Repositories created by Kotlin on a remote backend can be read by Go CLI
 *
 * Tests are gated on Docker availability and Go Kopia binary presence. If either
 * is missing, all tests are skipped (not failed).
 */
@Tag("remote-backend")
class RemoteBackendCrossCompatibilitySmokeTest : CrossCompatibilityTestBase() {

    private val autoCloseables = mutableListOf<AutoCloseable>()
    private val blobStorages = mutableListOf<BlobStorage>()

    @AfterEach
    fun tearDown() = runBlocking {
        cleanup()
        blobStorages.asReversed().forEach { storage ->
            try {
                storage.close()
            } catch (_: Exception) {
                // Best-effort cleanup
            }
        }
        blobStorages.clear()
        autoCloseables.asReversed().forEach { resource ->
            try {
                resource.close()
            } catch (_: Exception) {
                // Best-effort cleanup
            }
        }
        autoCloseables.clear()
    }

    private fun requireDocker() {
        assumeTrue(isDockerAvailable(), "Docker is not available, skipping remote backend tests")
    }

    // -----------------------------------------------------------------------
    // S3 (MinIO) Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("S3 (MinIO)")
    inner class S3Tests {

        @Test
        @DisplayName("Go creates snapshot on S3, Kotlin reads and restores")
        fun s3_goCreates_kotlinReads_snapshotSmoke(): Unit = runBlocking {
            requireGoKopia()
            requireDocker()

            val minio = startMinioContainer()
            val bucket = "test-repo"
            createMinioBucket(minio, bucket)

            val endpoint = "localhost:${minio.getMappedPort(9000)}"

            // Create test data
            testDataGenerator.createSimpleDirectory(sourceDir)

            // Go CLI: create repo and snapshot on S3
            cliRunner.run(
                "repository", "create", "s3",
                "--bucket=$bucket",
                "--endpoint=$endpoint",
                "--access-key-id=minioadmin",
                "--secret-access-key=minioadmin",
                "--disable-tls",
                "--password=$testPassword",
            ).requireSuccess()

            cliRunner.snapshotCreate(sourceDir)
            cliRunner.repositoryDisconnect()

            // Kotlin: open same S3 repo
            val storage = createS3Storage(endpoint, bucket)
            blobStorages.add(storage)

            val repo = DirectRepositoryImpl.open(storage, testPassword)
            autoCloseables.add(repo)

            // Find and restore the snapshot
            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            assertThat(manifests).isNotEmpty()

            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            assertThat(manifest.rootEntry).isNotNull()

            val rootEntry = snapshotRoot(repo, manifest)
            val output = createRestoreOutput(restoreDir)
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
            )
            val stats = restorer.restore(rootEntry)
            assertThat(stats.ignoredErrorCount).isEqualTo(0)
            assertThat(stats.restoredFileCount).isGreaterThan(0)

            val comparison = compareDirectories(sourceDir, restoreDir)
            if (!comparison.identical) {
                throw AssertionError("S3 Go->Kotlin restore mismatch: $comparison")
            }
        }

        @Test
        @DisplayName("Kotlin creates snapshot on S3, Go reads and restores")
        fun s3_kotlinCreates_goReads_snapshotSmoke(): Unit = runBlocking {
            requireGoKopia()
            requireDocker()

            val minio = startMinioContainer()
            val bucket = "test-repo"
            createMinioBucket(minio, bucket)

            val endpoint = "localhost:${minio.getMappedPort(9000)}"

            // Create test data
            val dirInfo = testDataGenerator.createSimpleDirectory(sourceDir)

            // Kotlin: create repo and upload snapshot on S3
            val storage = createS3Storage(endpoint, bucket)
            blobStorages.add(storage)

            val repo = createGoCompatibleRepo(storage)
            autoCloseables.add(repo)

            uploadSnapshotWithKotlin(repo)
            repo.close()
            autoCloseables.remove(repo)

            // Go CLI: connect and restore
            cliRunner.run(
                "repository", "connect", "s3",
                "--bucket=$bucket",
                "--endpoint=$endpoint",
                "--access-key-id=minioadmin",
                "--secret-access-key=minioadmin",
                "--disable-tls",
                "--password=$testPassword",
            ).requireSuccess()

            val snapshots = cliRunner.snapshotList(all = true)
            assertThat(snapshots).isNotEmpty()

            val snapshotId = snapshots.first().rootEntry?.obj
                ?: snapshots.first().id
            assertThat(snapshotId).isNotNull()

            cliRunner.snapshotRestore(snapshotId!!, restoreDir)

            val comparison = compareDirectories(sourceDir, restoreDir)
            if (!comparison.identical) {
                throw AssertionError("S3 Kotlin->Go restore mismatch: $comparison")
            }

            for (fileInfo in dirInfo.files) {
                val relativePath = sourceDir.relativize(fileInfo.path).toString()
                val restoredFile = restoreDir.resolve(relativePath)
                assertThat(restoredFile.readBytes()).isEqualTo(fileInfo.content)
            }
        }
    }

    // -----------------------------------------------------------------------
    // WebDAV Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("WebDAV")
    inner class WebDavTests {

        @Test
        @DisplayName("Go creates snapshot on WebDAV, Kotlin reads and restores")
        fun webdav_goCreates_kotlinReads_snapshotSmoke(): Unit = runBlocking {
            requireGoKopia()
            requireDocker()

            val webdav = startWebDavContainer()
            val port = webdav.getMappedPort(80)
            val webdavUrl = "http://localhost:$port"

            // Create test data
            testDataGenerator.createSimpleDirectory(sourceDir)

            // Go CLI: create repo on WebDAV
            cliRunner.run(
                "repository",
                "create",
                "webdav",
                "--url=$webdavUrl",
                "--password=$testPassword",
            ).requireSuccess()

            cliRunner.snapshotCreate(sourceDir)
            cliRunner.repositoryDisconnect()

            // Kotlin: open same WebDAV repo
            val storage = createWebDavStorage(webdavUrl)
            blobStorages.add(storage)

            val repo = DirectRepositoryImpl.open(storage, testPassword)
            autoCloseables.add(repo)

            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            assertThat(manifests).isNotEmpty()

            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            assertThat(manifest.rootEntry).isNotNull()

            val rootEntry = snapshotRoot(repo, manifest)
            val output = createRestoreOutput(restoreDir)
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
            )
            val stats = restorer.restore(rootEntry)
            assertThat(stats.ignoredErrorCount).isEqualTo(0)
            assertThat(stats.restoredFileCount).isGreaterThan(0)

            val comparison = compareDirectories(sourceDir, restoreDir)
            if (!comparison.identical) {
                throw AssertionError("WebDAV Go->Kotlin restore mismatch: $comparison")
            }
        }

        @Test
        @DisplayName("Kotlin creates snapshot on WebDAV, Go reads and restores")
        fun webdav_kotlinCreates_goReads_snapshotSmoke(): Unit = runBlocking {
            requireGoKopia()
            requireDocker()

            val webdav = startWebDavContainer()
            val port = webdav.getMappedPort(80)
            val webdavUrl = "http://localhost:$port"

            // Create test data
            val dirInfo = testDataGenerator.createSimpleDirectory(sourceDir)

            // Kotlin: create repo and upload on WebDAV
            val storage = createWebDavStorage(webdavUrl, isCreate = true)
            blobStorages.add(storage)

            val repo = createGoCompatibleRepo(storage)
            autoCloseables.add(repo)

            uploadSnapshotWithKotlin(repo)
            repo.close()
            autoCloseables.remove(repo)

            // Go CLI: connect and restore
            cliRunner.run(
                "repository",
                "connect",
                "webdav",
                "--url=$webdavUrl",
                "--password=$testPassword",
            ).requireSuccess()

            val snapshots = cliRunner.snapshotList(all = true)
            assertThat(snapshots).isNotEmpty()

            val snapshotId = snapshots.first().rootEntry?.obj
                ?: snapshots.first().id
            assertThat(snapshotId).isNotNull()

            cliRunner.snapshotRestore(snapshotId!!, restoreDir)

            val comparison = compareDirectories(sourceDir, restoreDir)
            if (!comparison.identical) {
                throw AssertionError("WebDAV Kotlin->Go restore mismatch: $comparison")
            }

            for (fileInfo in dirInfo.files) {
                val relativePath = sourceDir.relativize(fileInfo.path).toString()
                val restoredFile = restoreDir.resolve(relativePath)
                assertThat(restoredFile.readBytes()).isEqualTo(fileInfo.content)
            }
        }
    }

    // -----------------------------------------------------------------------
    // SFTP Tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("SFTP")
    inner class SftpTests {

        @Test
        @DisplayName("Go creates snapshot on SFTP, Kotlin reads and restores")
        fun sftp_goCreates_kotlinReads_snapshotSmoke(): Unit = runBlocking {
            requireGoKopia()
            requireDocker()

            val sftp = startSftpContainer()
            val port = sftp.getMappedPort(22)

            // Write a known_hosts file for Go CLI (accept any key)
            val knownHostsFile = testDir.resolve("known_hosts")
            writePermissiveKnownHosts(knownHostsFile, port)

            // Create test data
            testDataGenerator.createSimpleDirectory(sourceDir)

            // Go CLI: create repo on SFTP
            // --sftp-password is the SFTP user password; repo password via --password
            cliRunner.run(
                "repository", "create", "sftp",
                "--path=/upload/repo",
                "--host=localhost",
                "--port=$port",
                "--username=testuser",
                "--sftp-password=testpass",
                "--known-hosts=${knownHostsFile.absolutePathString()}",
                "--password=$testPassword",
            ).requireSuccess()

            cliRunner.snapshotCreate(sourceDir)
            cliRunner.repositoryDisconnect()

            // Kotlin: open same SFTP repo
            val storage = createSftpStorage("localhost", port, "/upload/repo")
            blobStorages.add(storage)

            val repo = DirectRepositoryImpl.open(storage, testPassword)
            autoCloseables.add(repo)

            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            assertThat(manifests).isNotEmpty()

            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            assertThat(manifest.rootEntry).isNotNull()

            val rootEntry = snapshotRoot(repo, manifest)
            val output = createRestoreOutput(restoreDir)
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
            )
            val stats = restorer.restore(rootEntry)
            assertThat(stats.ignoredErrorCount).isEqualTo(0)
            assertThat(stats.restoredFileCount).isGreaterThan(0)

            val comparison = compareDirectories(sourceDir, restoreDir)
            if (!comparison.identical) {
                throw AssertionError("SFTP Go->Kotlin restore mismatch: $comparison")
            }
        }

        @Test
        @DisplayName("Go creates FLAT (unsharded) snapshot on SFTP, Kotlin reads and restores")
        fun sftp_goCreatesFlat_kotlinReads_snapshotSmoke(): Unit = runBlocking {
            requireGoKopia()
            requireDocker()

            val sftp = startSftpContainer()
            val port = sftp.getMappedPort(22)

            val knownHostsFile = testDir.resolve("known_hosts")
            writePermissiveKnownHosts(knownHostsFile, port)

            testDataGenerator.createSimpleDirectory(sourceDir)

            // `--flat` makes Go write `.shards={"default":[]}` and store blobs UNSHARDED at the repo
            // root. Kotlin must read that `.shards` and honor the flat layout; otherwise it assumes
            // [1,3] sharding, computes x/n0_/… paths, finds nothing and opens an empty repo.
            // Regression guard for task-23.1.
            cliRunner.run(
                "repository", "create", "sftp",
                "--path=/upload/repo",
                "--host=localhost",
                "--port=$port",
                "--username=testuser",
                "--sftp-password=testpass",
                "--known-hosts=${knownHostsFile.absolutePathString()}",
                "--password=$testPassword",
                "--flat",
            ).requireSuccess()

            cliRunner.snapshotCreate(sourceDir)
            cliRunner.repositoryDisconnect()

            val storage = createSftpStorage("localhost", port, "/upload/repo")
            blobStorages.add(storage)

            val repo = DirectRepositoryImpl.open(storage, testPassword)
            autoCloseables.add(repo)

            val manifests = repo.findManifests(
                mapOf(ManifestLabels.TYPE to ManifestLabels.TYPE_SNAPSHOT),
            )
            assertThat(manifests).isNotEmpty()

            val (manifest, _) = repo.getManifest(
                manifests.first().id,
                SnapshotManifest.serializer(),
            )
            assertThat(manifest.rootEntry).isNotNull()

            val rootEntry = snapshotRoot(repo, manifest)
            val output = createRestoreOutput(restoreDir)
            val restorer = SnapshotRestorer(
                output = output,
                options = RestoreOptions(parallel = 1),
            )
            val stats = restorer.restore(rootEntry)
            assertThat(stats.ignoredErrorCount).isEqualTo(0)
            assertThat(stats.restoredFileCount).isGreaterThan(0)

            val comparison = compareDirectories(sourceDir, restoreDir)
            if (!comparison.identical) {
                throw AssertionError("SFTP Go(flat)->Kotlin restore mismatch: $comparison")
            }
        }

        @Test
        @DisplayName("Kotlin creates snapshot on SFTP, Go reads and restores")
        fun sftp_kotlinCreates_goReads_snapshotSmoke(): Unit = runBlocking {
            requireGoKopia()
            requireDocker()

            val sftp = startSftpContainer()
            val port = sftp.getMappedPort(22)

            val knownHostsFile = testDir.resolve("known_hosts")
            writePermissiveKnownHosts(knownHostsFile, port)

            // Create test data
            val dirInfo = testDataGenerator.createSimpleDirectory(sourceDir)

            // Kotlin: create repo and upload on SFTP
            val storage = createSftpStorage("localhost", port, "/upload/repo", isCreate = true)
            blobStorages.add(storage)

            val repo = createGoCompatibleRepo(storage)
            autoCloseables.add(repo)

            uploadSnapshotWithKotlin(repo)
            repo.close()
            autoCloseables.remove(repo)

            // Go CLI: connect and restore
            // --sftp-password is the SFTP user password; repo password via --password
            cliRunner.run(
                "repository", "connect", "sftp",
                "--path=/upload/repo",
                "--host=localhost",
                "--port=$port",
                "--username=testuser",
                "--sftp-password=testpass",
                "--known-hosts=${knownHostsFile.absolutePathString()}",
                "--password=$testPassword",
            ).requireSuccess()

            val snapshots = cliRunner.snapshotList(all = true)
            assertThat(snapshots).isNotEmpty()

            val snapshotId = snapshots.first().rootEntry?.obj
                ?: snapshots.first().id
            assertThat(snapshotId).isNotNull()

            cliRunner.snapshotRestore(snapshotId!!, restoreDir)

            val comparison = compareDirectories(sourceDir, restoreDir)
            if (!comparison.identical) {
                throw AssertionError("SFTP Kotlin->Go restore mismatch: $comparison")
            }

            for (fileInfo in dirInfo.files) {
                val relativePath = sourceDir.relativize(fileInfo.path).toString()
                val restoredFile = restoreDir.resolve(relativePath)
                assertThat(restoredFile.readBytes()).isEqualTo(fileInfo.content)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Container helpers
    // -----------------------------------------------------------------------

    /**
     * Starts a MinIO container for S3-compatible storage.
     */
    private fun startMinioContainer(): GenericContainer<*> {
        @Suppress("DEPRECATION")
        val container = GenericContainer("minio/minio:latest")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200))

        container.start()
        autoCloseables.add(container)
        return container
    }

    /**
     * Creates a bucket in the MinIO container via the S3 API.
     */
    private fun createMinioBucket(minio: GenericContainer<*>, bucket: String) {
        val port = minio.getMappedPort(9000)
        val url = URI("http://localhost:$port/$bucket").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "AWS minioadmin:minioadmin")
        conn.doOutput = true
        conn.connect()
        // MinIO returns 200 on bucket creation, 409 if it already exists
        val code = conn.responseCode
        conn.disconnect()
        if (code != 200 && code != 409) {
            throw RuntimeException("Failed to create MinIO bucket '$bucket': HTTP $code")
        }
    }

    /**
     * Starts a WebDAV server container.
     * Uses the rclone/rclone image serving a local directory over WebDAV,
     * which provides a standards-compliant WebDAV server.
     */
    private fun startWebDavContainer(): GenericContainer<*> {
        @Suppress("DEPRECATION")
        val container = GenericContainer("bytemark/webdav:latest")
            .withExposedPorts(80)
            .waitingFor(Wait.forListeningPort())

        container.start()
        autoCloseables.add(container)
        return container
    }

    /**
     * Starts an SFTP server container using atmoz/sftp.
     *
     * The command format is "user:pass:::chroot_dir" which creates:
     * - Username: testuser
     * - Password: testpass
     * - Home directory: /home/testuser
     * - Chroot: /home/testuser
     * - Writable subdirectory: /home/testuser/upload
     */
    private fun startSftpContainer(): GenericContainer<*> {
        @Suppress("DEPRECATION")
        val container = GenericContainer("atmoz/sftp:latest")
            .withExposedPorts(22)
            .withCommand("testuser:testpass:::upload")
            .waitingFor(Wait.forListeningPort())

        container.start()
        autoCloseables.add(container)
        return container
    }

    // -----------------------------------------------------------------------
    // Storage factory helpers
    // -----------------------------------------------------------------------

    private suspend fun createS3Storage(endpoint: String, bucket: String): BlobStorage {
        val options = S3Options(
            bucketName = bucket,
            endpoint = endpoint,
            accessKeyId = "minioadmin",
            secretAccessKey = "minioadmin",
            region = "us-east-1",
            doNotUseTls = true,
        )
        return S3BlobStorage.create(options)
    }

    private suspend fun createWebDavStorage(
        url: String,
        isCreate: Boolean = false,
    ): BlobStorage {
        val options = WebDavOptions(url = url)
        return WebDavBlobStorage.create(options, isCreate = isCreate)
    }

    private suspend fun createSftpStorage(
        host: String,
        port: Int,
        path: String,
        isCreate: Boolean = false,
    ): BlobStorage {
        val options = SftpOptions(
            path = path,
            host = host,
            port = port,
            username = "testuser",
            password = "testpass",
            // Ephemeral test SFTP server has a throwaway host key; opt into the insecure verifier
            // explicitly (the default now fails closed on unknown host keys).
            insecureSkipHostKeyVerification = true,
        )
        return SftpBlobStorage.create(options, isCreate = isCreate)
    }

    // -----------------------------------------------------------------------
    // Repository helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a Go CLI-compatible Kotlin repository on the given storage.
     * Uses V1 index format and disabled epochs for maximum Go CLI compatibility.
     */
    private suspend fun createGoCompatibleRepo(storage: BlobStorage): DirectRepositoryImpl {
        val random = SecureRandom()
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val masterKey = ByteArray(32).also { random.nextBytes(it) }

        val config = RepositoryConfig(
            hash = "BLAKE2B-256-128",
            encryption = "AES256-GCM-HMAC-SHA256",
            secret = secret,
            masterKey = masterKey,
            splitter = "FIXED-1M",
            version = 1,
            indexVersion = MutableParameters.LEGACY_INDEX_VERSION,
            epochParameters = EpochParameters.DISABLED,
        )

        return DirectRepositoryImpl.create(storage, testPassword, config)
    }

    /**
     * Uploads a snapshot of the sourceDir using Kotlin.
     */
    private suspend fun uploadSnapshotWithKotlin(repo: DirectRepositoryImpl) {
        writeSession(repo) { writer ->
            val source = SourceInfo(
                host = "test-host",
                userName = "test-user",
                path = sourceDir.toString(),
            )

            val progress = CountingUploadProgress()
            val uploader = SnapshotUploader(
                writer = writer,
                source = source,
                policy = Policy(),
                progress = progress,
            )

            val rootDir = LocalFilesystem.directory(sourceDir)
            val uploadResult = uploader.upload(rootDir, UploadOptions())
            assertThat(uploadResult.incomplete).isFalse()
        }
    }

    /**
     * Creates a FilesystemOutput for restore operations.
     */
    private fun createRestoreOutput(targetPath: Path): FilesystemOutput = FilesystemOutput(
        targetPath = targetPath,
        options = FilesystemOutputOptions(
            overwriteDirectories = true,
            overwriteFiles = true,
            overwriteSymlinks = true,
        ),
    )

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    /**
     * Writes a permissive known_hosts file that accepts any host key for localhost.
     * This is only used for testing; production code should verify host keys properly.
     *
     * Go Kopia's SFTP backend reads known_hosts and uses it for host key verification.
     * We scan the container's SSH host key and write a proper known_hosts entry.
     */
    private fun writePermissiveKnownHosts(path: Path, port: Int) {
        // ssh-keyscan to get the real host key from the container
        try {
            val process = ProcessBuilder(
                "ssh-keyscan",
                "-p",
                port.toString(),
                "-H",
                "localhost",
            ).start()
            val completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (completed && process.exitValue() == 0) {
                val hostKeys = process.inputStream.bufferedReader().readText()
                if (hostKeys.isNotBlank()) {
                    path.toFile().writeText(hostKeys)
                    return
                }
            }
        } catch (_: Exception) {
            // Fall through to fallback
        }

        // Fallback: empty file (Go CLI will warn but may still work in some versions)
        path.toFile().writeText("")
    }

    companion object {
        /**
         * Checks whether Docker is available on this machine.
         */
        private fun isDockerAvailable(): Boolean = try {
            val process = ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            completed && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
