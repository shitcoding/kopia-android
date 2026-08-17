package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kopiaKt.core.hashing.HashAlgorithm

/**
 * Every hash id this build declares must be one the Go binary accepts (task-74).
 *
 * `HashAlgorithm`'s own KDoc says these "must match the Go implementation exactly for
 * cross-compatibility", and nothing checked it — so `BLAKE2B-256-256` sat in the enum, an algorithm
 * Go kopia does not have. Go refused to create with the name, and refused to open a repository this
 * codebase had created with it: *"unable to create hash: unknown hash function BLAKE2B-256-256"*.
 * A repository the phone can write and desktop Kopia can never read is the one thing this project
 * promises does not happen.
 *
 * The algorithm matrix could not be this guard. It exercises the ids someone remembered to list;
 * this asks the binary about every id the enum actually declares, so adding a new one without
 * checking it against Go fails here instead of in a user's repository.
 *
 * Deliberately asks the CLI rather than comparing digests: whether the bytes agree is what the
 * matrix proves for the algorithms it covers, while what went wrong here was a NAME Go does not
 * know. `repository create` validates the flag against Go's own enum, which is exactly the
 * question.
 */
@Tag("cross-compat")
@DisplayName("Every declared hash algorithm exists in Go kopia (task-74)")
class HashAlgorithmGoParityTest : CrossCompatibilityTestBase() {

    @AfterEach
    fun tearDown() = runBlocking {
        cleanup()
    }

    @Test
    fun `the Go binary accepts every id HashAlgorithm declares`(): Unit = runBlocking {
        requireGoKopia()

        val rejected = mutableListOf<String>()
        for (algorithm in HashAlgorithm.entries) {
            val repo = repoDir.resolve(algorithm.name)
            val result = cliRunner.run(
                "repository",
                "create",
                "filesystem",
                "--path=$repo",
                "--password=$testPassword",
                "--block-hash=${algorithm.id}",
                "--encryption=AES256-GCM-HMAC-SHA256",
            )
            if (!result.success) {
                rejected.add("${algorithm.name} (\"${algorithm.id}\"): ${result.stderr.trim().takeLast(200)}")
            }
            cliRunner.repositoryDisconnect()
        }

        assertThat(rejected).isEmpty()
    }
}
