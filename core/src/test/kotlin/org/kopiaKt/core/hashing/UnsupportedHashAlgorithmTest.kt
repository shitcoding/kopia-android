package org.kopiaKt.core.hashing

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Which of Go kopia's hash algorithms this app implements, and what happens on meeting one it does
 * not.
 *
 * Go offers eleven; KopiaKt implements four. That is a deliberate decision (task-74) rather than an
 * oversight: Go's *default* is `BLAKE2B-256-128`, which is what desktop Kopia creates unless the
 * user goes looking, so the gap is reachable only for someone who chose a non-default algorithm and
 * then wants to open that repository on a phone. Implementing the other seven means shipping and
 * maintaining seven more byte-exact digests for a case nobody has reported.
 *
 * What the decision does oblige is that such a repository be refused with a sentence that names the
 * algorithm and says what can be done, rather than a bare failure — which is what this pins.
 */
@DisplayName("Unsupported hash algorithms")
class UnsupportedHashAlgorithmTest {

    /**
     * Every hash algorithm Go kopia 0.23.1 offers (`repo/hashing/hashing.go` plus the BLAKE2/BLAKE3
     * and HMAC registrations). Kept as one list so the split below is a statement about Go's set,
     * not a restatement of ours.
     */
    private val goHashAlgorithms = listOf(
        "BLAKE2B-256",
        "BLAKE2B-256-128",
        "BLAKE2S-128",
        "BLAKE2S-256",
        "BLAKE3-256",
        "BLAKE3-256-128",
        "HMAC-SHA224",
        "HMAC-SHA256",
        "HMAC-SHA256-128",
        "HMAC-SHA3-224",
        "HMAC-SHA3-256",
    )

    @Test
    fun `the four Go algorithms this app implements resolve`() {
        val implemented = goHashAlgorithms.filter { HashAlgorithm.fromId(it) != null }

        assertThat(implemented).containsExactly(
            "BLAKE2B-256",
            "BLAKE2B-256-128",
            "BLAKE3-256",
            "HMAC-SHA256-128",
        )
    }

    @Test
    fun `the seven it does not are refused by name, not by a bare failure`() {
        val unimplemented = goHashAlgorithms.filter { HashAlgorithm.fromId(it) == null }
        assertThat(unimplemented).hasSize(7)

        unimplemented.forEach { id ->
            val message = HashAlgorithm.unsupportedMessage(id)
            assertWithMessage("the message for %s must name it, or the user cannot act on it", id)
                .that(message).contains(id)
            // Naming the problem is only half of it: the message has to say what IS available, or
            // "not supported" leaves the user with nowhere to go.
            assertThat(message).contains(HashAlgorithm.DEFAULT.id)
        }
    }
}
