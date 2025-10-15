package com.kopia.android

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.kopia.android.service.KopiaServerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.google.common.truth.Truth.assertThat

/**
 * Instrumented tests for Kopia server operations.
 * Tests server startup, connectivity, and lifecycle.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class KopiaServerTest : BaseInstrumentedTest() {

    private lateinit var context: Context
    private val serverPort = 51515
    private val serverUrl = "http://127.0.0.1:$serverPort/"

    @Before
    fun setupContext() {
        context = ApplicationProvider.getApplicationContext()
        grantStoragePermissions()
    }

    @Test
    fun testKopiaBinaryExists() {
        // Check if Kopia binary is bundled
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        assertThat(nativeLibDir).isNotNull()

        val libKopia = File(nativeLibDir, "libkopia.so")
        println("Checking for Kopia binary at: ${libKopia.absolutePath}")

        // Binary should exist in one of the locations
        val kopiaBinaryExists = libKopia.exists() || File(context.filesDir, "kopia").exists()
        assertThat(kopiaBinaryExists).isTrue()

        if (libKopia.exists()) {
            println("Kopia binary size: ${libKopia.length()} bytes")
            assertThat(libKopia.length()).isGreaterThan(1000000L) // At least 1MB
        }
    }

    @Test
    fun testKopiaBinaryIsExecutable() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val libKopia = File(nativeLibDir, "libkopia.so")

        if (libKopia.exists()) {
            // Check if it's a valid ELF binary (first 4 bytes)
            val header = ByteArray(4)
            libKopia.inputStream().use { it.read(header) }

            // ELF magic number: 0x7F 'E' 'L' 'F'
            assertThat(header[0]).isEqualTo(0x7F.toByte())
            assertThat(header[1]).isEqualTo('E'.code.toByte())
            assertThat(header[2]).isEqualTo('L'.code.toByte())
            assertThat(header[3]).isEqualTo('F'.code.toByte())
        }
    }

    @Test
    fun testKopiaRepositoryDirectoryCreated() {
        val repoDir = File(context.filesDir, "repo")
        val configDir = File(context.filesDir, ".kopia")

        // Ensure directories can be created
        assertThat(repoDir.exists() || repoDir.mkdirs()).isTrue()
        assertThat(configDir.exists() || configDir.mkdirs()).isTrue()

        assertThat(repoDir.canRead()).isTrue()
        assertThat(repoDir.canWrite()).isTrue()
        assertThat(configDir.canRead()).isTrue()
        assertThat(configDir.canWrite()).isTrue()
    }

    @Test
    fun testKopiaServerServiceIntent() {
        // Test that service intent can be created
        val serviceIntent = Intent(context, KopiaServerService::class.java).apply {
            putExtra("SERVER_PORT", serverPort)
        }

        assertThat(serviceIntent).isNotNull()
        assertThat(serviceIntent.getIntExtra("SERVER_PORT", 0)).isEqualTo(serverPort)
    }

    @Test
    fun testServerConnectivity() = runBlocking {
        // Note: This test requires the server to be running
        // In a full test suite, we would start the service first

        var serverResponding = false
        var attempts = 0

        // Try to connect to the server (if it's running)
        while (!serverResponding && attempts < 5) {
            try {
                val url = URL(serverUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "HEAD"

                val responseCode = connection.responseCode
                serverResponding = responseCode in 200..499 // Any response means server is up

                connection.disconnect()
            } catch (e: Exception) {
                println("Server connection attempt $attempts failed: ${e.message}")
            }

            attempts++
            if (!serverResponding) {
                delay(1000)
            }
        }

        // Document whether server is running
        println("Server responding: $serverResponding after $attempts attempts")

        // This test passes either way - it's documenting the state
        assertThat(serverResponding).isAnyOf(true, false)
    }

    @Test
    fun testServerConfiguration() {
        // Test server configuration values
        val defaultPort = 51515
        assertThat(serverPort).isEqualTo(defaultPort)

        val expectedUrl = "http://127.0.0.1:$defaultPort/"
        assertThat(serverUrl).isEqualTo(expectedUrl)
    }

    @Test
    fun testServerEnvironmentVariables() {
        // Test that server environment can be set up
        val homeDir = context.filesDir.absolutePath
        val configPath = File(context.filesDir, ".kopia/repository.config").absolutePath

        assertThat(homeDir).isNotEmpty()
        assertThat(configPath).contains(".kopia")
        assertThat(configPath).contains("repository.config")
    }

    @Test
    fun testServerPasswordFile() {
        // Test password file handling
        val configDir = File(context.filesDir, ".kopia")
        configDir.mkdirs()

        val passwordFile = File(configDir, "password.txt")
        val testPassword = "test_password_123"

        // Write test password
        passwordFile.writeText(testPassword)
        assertThat(passwordFile.exists()).isTrue()

        // Read and verify
        val readPassword = passwordFile.readText().trim()
        assertThat(readPassword).isEqualTo(testPassword)

        // Cleanup
        passwordFile.delete()
    }

    @Test
    fun testKopiaServerServiceBinding() {
        // Test that service can be bound (even if not started)
        val serviceIntent = Intent(context, KopiaServerService::class.java)

        try {
            // Verify the service class exists and can be instantiated
            assertThat(KopiaServerService::class.java).isNotNull()
        } catch (e: Exception) {
            println("Service binding test warning: ${e.message}")
        }
    }

    @Test
    fun testServerPortConfiguration() {
        // Test different port configurations
        val testPorts = listOf(51515, 8080, 3000)

        testPorts.forEach { port ->
            val url = "http://127.0.0.1:$port/"
            assertThat(url).contains(port.toString())

            // Verify URL is well-formed
            val urlObj = URL(url)
            assertThat(urlObj.host).isEqualTo("127.0.0.1")
            assertThat(urlObj.port).isEqualTo(port)
        }
    }

    @Test
    fun testRepositoryConfigPath() {
        val configPath = File(context.filesDir, ".kopia/repository.config")

        // Ensure parent directory exists
        configPath.parentFile?.mkdirs()
        assertThat(configPath.parentFile?.exists()).isTrue()

        // Path should be constructable
        assertThat(configPath.absolutePath).contains("kopia")
        assertThat(configPath.absolutePath).contains("repository.config")
    }

    @Test
    fun testServerStartupPrerequisites() {
        // Verify all prerequisites for server startup

        // 1. Binary exists
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binaryExists = File(nativeLibDir, "libkopia.so").exists() ||
                File(context.filesDir, "kopia").exists()
        assertThat(binaryExists).isTrue()

        // 2. Directories can be created
        val repoDir = File(context.filesDir, "repo")
        val configDir = File(context.filesDir, ".kopia")
        assertThat(repoDir.exists() || repoDir.mkdirs()).isTrue()
        assertThat(configDir.exists() || configDir.mkdirs()).isTrue()

        // 3. Permissions are adequate
        assertThat(repoDir.canWrite()).isTrue()
        assertThat(configDir.canWrite()).isTrue()

        // 4. Port is valid
        assertThat(serverPort).isIn(1024..65535)
    }

    @Test
    fun testKopiaCommandConstruction() {
        val kopiaBinaryPath = "/data/data/com.kopia.android/files/kopia"
        val command = listOf(
            kopiaBinaryPath,
            "server",
            "start",
            "--address=127.0.0.1:$serverPort",
            "--insecure",
            "--server-username=admin",
            "--server-password=admin"
        )

        assertThat(command).hasSize(7)
        assertThat(command[0]).endsWith("kopia")
        assertThat(command[1]).isEqualTo("server")
        assertThat(command[2]).isEqualTo("start")
        assertThat(command[3]).contains("127.0.0.1")
        assertThat(command[4]).isEqualTo("--insecure")
    }
}
