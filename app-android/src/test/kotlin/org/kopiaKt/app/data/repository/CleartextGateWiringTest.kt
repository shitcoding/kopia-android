package org.kopiaKt.app.data.repository

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kopiaKt.app.domain.model.ConnectionConfig

/**
 * Locks that the cleartext gate is actually WIRED into the connect path, not merely defined.
 *
 * [CleartextPolicyTest] covers the predicate itself, but deleting the `requireCleartextAllowed(...)`
 * call sites would leave it green — these tests fail if the connect path stops consulting the gate.
 * They never reach the network: the gate throws while the blob storage is being constructed.
 */
class CleartextGateWiringTest {

    private val context = mockk<Context>(relaxed = true)
    private val manager = KopiaRepositoryManagerImpl(context)

    @Test
    fun `connect refuses an unacknowledged cleartext S3 endpoint`() = runTest {
        val result = manager.connect(
            ConnectionConfig.S3(
                bucket = "b",
                endpoint = "http://minio.local:9000",
                region = "us-east-1",
                accessKeyId = "k",
                secretAccessKey = "s",
            ),
            repositoryPassword = "irrelevant",
        )

        assertTrue(result.isFailure, "an unacknowledged cleartext S3 endpoint must not connect")
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("cleartext", ignoreCase = true),
            "expected the cleartext gate to reject it, got: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun `connect refuses an unacknowledged cleartext WebDAV url`() = runTest {
        val result = manager.connect(
            ConnectionConfig.WebDAV(url = "http://nas.local/dav/", username = "u", password = "p"),
            repositoryPassword = "irrelevant",
        )

        assertTrue(result.isFailure, "an unacknowledged cleartext WebDAV url must not connect")
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("cleartext", ignoreCase = true),
            "expected the cleartext gate to reject it, got: ${result.exceptionOrNull()?.message}",
        )
    }
}
