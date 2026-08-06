package org.kopiaKt.app.data.repository

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.app.domain.model.ConnectionConfig
import org.kopiaKt.app.domain.repository.ConnectionState
import java.nio.file.Files

/**
 * Exercises the real body of [KopiaRepositoryManagerImpl.testConnection] -- the storage
 * construction, the listing probe and the close -- which the bridge-level tests cannot reach
 * because they mock the manager away.
 *
 * A local filesystem destination is the one backend that needs no network, so it is what these use;
 * the code path above the backend (probe, close, error mapping, leaving the connection alone) is
 * shared with S3/WebDAV/SFTP.
 */
class TestConnectionProbeTest {

    private val context = mockk<Context>(relaxed = true)
    private val manager = KopiaRepositoryManagerImpl(context)

    @Test
    @DisplayName("an empty but reachable destination is a success, not a failure")
    fun `empty destination succeeds`(): Unit = runTest {
        val dir = Files.createTempDirectory("kopia-probe-ok")
        try {
            val result = manager.testConnection(ConnectionConfig.LocalFilesystem(dir.toString()))

            // The create wizard points at an empty destination, so "no blobs" must not read as
            // "cannot reach it".
            assertTrue(result.isSuccess, "an empty destination should pass: ${result.exceptionOrNull()}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("an unreachable destination is reported as a failure")
    fun `unreachable destination fails`(): Unit = runTest {
        val missing = Files.createTempDirectory("kopia-probe-missing").resolve("no-such-dir")

        val result = manager.testConnection(ConnectionConfig.LocalFilesystem(missing.toString()))

        assertTrue(result.isFailure, "a destination that does not exist must not report success")
    }

    @Test
    @DisplayName("testing a candidate config does not disturb the current connection")
    fun `probe leaves the connection state alone`(): Unit = runTest {
        val dir = Files.createTempDirectory("kopia-probe-state")
        try {
            val before = manager.connectionState.value

            manager.testConnection(ConnectionConfig.LocalFilesystem(dir.toString()))
            manager.testConnection(ConnectionConfig.LocalFilesystem("/definitely/not/here"))

            // connect()/create() move this to Connecting and then Connected/Error. Testing a
            // candidate must not: the user may already have a repository open, and a failed test of
            // some other config must not make their live session look broken.
            assertEquals(before, manager.connectionState.value)
            assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
