package org.kopiaKt.snapshot.policy

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Go's `OSSnapshotMode` is a bare `byte` with no custom JSON marshalling, so it crosses the wire as
 * a number. Kotlin encoded it as the human-readable string, which meant every policy manifest Go had
 * written -- including the global policy `kopia repository create` writes -- failed to decode, and
 * with it every backup into a desktop-created repository.
 */
class OSSnapshotModeWireFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `Go's numeric form decodes`() {
        val policy = json.decodeFromString<VolumeShadowCopyPolicy>("""{"enable":0}""")

        assertThat(policy.enable).isEqualTo(OSSnapshotMode.NEVER)
    }

    @Test
    fun `every mode round-trips through Go's numbering`() {
        for ((ordinal, mode) in OSSnapshotMode.entries.withIndex()) {
            val encoded = json.encodeToString(VolumeShadowCopyPolicy(enable = mode))

            assertThat(encoded).isEqualTo("""{"enable":$ordinal}""")
            assertThat(json.decodeFromString<VolumeShadowCopyPolicy>(encoded).enable).isEqualTo(mode)
        }
    }

    @Test
    fun `an unknown mode reads as never rather than failing the whole policy`() {
        val policy = json.decodeFromString<VolumeShadowCopyPolicy>("""{"enable":99}""")

        assertThat(policy.enable).isEqualTo(OSSnapshotMode.NEVER)
    }
}
