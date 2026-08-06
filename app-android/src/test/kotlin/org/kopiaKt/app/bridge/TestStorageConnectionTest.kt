package org.kopiaKt.app.bridge

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.TaskManager
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.IOException
import java.nio.file.Files

/**
 * Test Connection must actually test the connection.
 *
 * It used to probe a local filesystem path for real, but for S3/WebDAV/SFTP it only validated the
 * config and returned "OK" -- so the wizard showed a green "Connection OK" and unlocked its Next
 * button for a dead host or a wrong secret key, and the user only found out at repository creation.
 * These tests pin that a remote config is handed to the connect layer and that its verdict is what
 * the user is told.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class TestStorageConnectionTest {

    private companion object {
        const val TEN_MINUTES_MS = 600_000L
    }

    private lateinit var repositoryManager: KopiaRepositoryManager
    private lateinit var bridge: KopiaWebBridge

    @BeforeEach
    fun setUp() {
        repositoryManager = mockk(relaxed = true)
        bridge = KopiaWebBridge(
            taskManager = mockk<TaskManager>(relaxed = true),
            sourceManager = mockk<BackupSourceManager>(relaxed = true),
            repositoryManager = repositoryManager,
            context = RuntimeEnvironment.getApplication(),
        )
    }

    private fun succeeded(result: WebResult<String>): Boolean = result.success

    private fun errorOf(result: WebResult<String>): String = result.error.orEmpty()

    private val s3Config = """
        {"storageType":"S3","s3":{"bucket":"b","endpoint":"https://s3.example.invalid",
        "region":"us-east-1","accessKeyId":"k","secretAccessKey":"s"}}
    """.trimIndent().replace("\n", "")

    private val webdavConfig = """
        {"storageType":"WEBDAV","webdav":{"url":"https://dav.example.invalid/","username":"u","password":"p"}}
    """.trimIndent()

    private val sftpConfig = """
        {"storageType":"SFTP","sftp":{"host":"sftp.example.invalid","port":22,"username":"u",
        "path":"/upload","password":"p","knownHostsData":"x"}}
    """.trimIndent().replace("\n", "")

    @Test
    @DisplayName("an S3 config is handed to the connect layer, not merely validated")
    fun `s3 test connection reaches the connect layer`(): Unit = runTest {
        coEvery { repositoryManager.testConnection(any()) } returns Result.success(Unit)

        val result = bridge.probeStorageConnection(s3Config)

        assertTrue(succeeded(result), "a reachable S3 endpoint should report success: $result")
        coVerify(exactly = 1) {
            repositoryManager.testConnection(match { it is ConnectionConfig.S3 })
        }
    }

    @Test
    @DisplayName("a failing S3 connection is reported as a failure, not as OK")
    fun `s3 test connection surfaces the connect layer failure`(): Unit = runTest {
        coEvery { repositoryManager.testConnection(any()) } returns
            Result.failure(IOException("The specified bucket does not exist"))

        val result = bridge.probeStorageConnection(s3Config)

        assertFalse(succeeded(result), "a failing S3 connection must not report OK: $result")
        assertTrue(
            errorOf(result).contains("bucket does not exist"),
            "the connect layer's message must reach the user, got: ${errorOf(result)}",
        )
    }

    @Test
    @DisplayName("a failing WebDAV connection is reported as a failure")
    fun `webdav test connection surfaces the connect layer failure`(): Unit = runTest {
        coEvery { repositoryManager.testConnection(any()) } returns
            Result.failure(IOException("401 Unauthorized"))

        val result = bridge.probeStorageConnection(webdavConfig)

        assertFalse(succeeded(result), "a failing WebDAV connection must not report OK: $result")
        coVerify(exactly = 1) {
            repositoryManager.testConnection(match { it is ConnectionConfig.WebDAV })
        }
    }

    @Test
    @DisplayName("a failing SFTP connection is reported as a failure")
    fun `sftp test connection surfaces the connect layer failure`(): Unit = runTest {
        coEvery { repositoryManager.testConnection(any()) } returns
            Result.failure(IOException("host key not trusted"))

        val result = bridge.probeStorageConnection(sftpConfig)

        assertFalse(succeeded(result), "a failing SFTP connection must not report OK: $result")
        coVerify(exactly = 1) {
            repositoryManager.testConnection(match { it is ConnectionConfig.SFTP })
        }
    }

    @Test
    @DisplayName("an unacknowledged cleartext endpoint is refused before anything is contacted")
    fun `cleartext policy is enforced before the probe`(): Unit = runTest {
        coEvery { repositoryManager.testConnection(any()) } returns Result.success(Unit)

        val result = bridge.probeStorageConnection(
            """{"storageType":"S3","s3":{"bucket":"b","endpoint":"http://minio.local:9000",""" +
                """"region":"us-east-1","accessKeyId":"k","secretAccessKey":"s"}}""",
        )

        assertFalse(succeeded(result), "an unacknowledged cleartext endpoint must not pass: $result")
        assertTrue(
            errorOf(result).contains("cleartext", ignoreCase = true),
            "the refusal must name the policy, got: ${errorOf(result)}",
        )
        // The point of running the gate first: we must not ship credentials to a plaintext endpoint
        // just to find out whether it answers.
        coVerify(exactly = 0) { repositoryManager.testConnection(any()) }
    }

    @Test
    @DisplayName("the bridge method returns immediately instead of blocking the JS thread")
    fun `test storage connection does not block on the network`() {
        // A @JavascriptInterface call blocks its JS caller until it returns, so the verdict must
        // travel by callback. Guards against anyone reintroducing runBlocking here.
        coEvery { repositoryManager.testConnection(any()) } coAnswers {
            delay(TEN_MINUTES_MS)
            Result.success(Unit)
        }

        val acknowledgement = bridge.testStorageConnection(s3Config)

        assertTrue(
            acknowledgement.contains("testing"),
            "expected an immediate acknowledgement, got: $acknowledgement",
        )
    }

    @Test
    @DisplayName("a local filesystem path is still probed locally, without the connect layer")
    fun `local filesystem path is probed directly`(): Unit = runTest {
        val dir = Files.createTempDirectory("kopia-test-conn").toFile()
        try {
            val result = bridge.probeStorageConnection(
                """{"storageType":"LOCAL_FILESYSTEM","local":{"path":"${dir.absolutePath}"}}""",
            )

            assertTrue(succeeded(result), "a writable directory should pass: $result")
            coVerify(exactly = 0) { repositoryManager.testConnection(any()) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
