package org.kopiaKt.android.identity

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * The device's identity is half of every source key (`user@host:path`), so it has to be stable for
 * the life of the install and unique per device. Bare `Build.MODEL` is neither unique — two identical
 * phones collide, and Android's default paths are identical across devices, so their snapshots would
 * merge into one source with interleaved retention — nor safe as a key: both Go's `ParseSourceInfo`
 * and the app's `parseSourceId` split on `@` and `:`.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class SourceIdentityTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the identity survives losing the in-memory copy`() {
        // Robolectric hands each test method a fresh application (and a fresh prefs dir) while the
        // process-wide cache persists, so start from a known-empty state.
        SourceIdentityStore.resetForTest()
        val first = SourceIdentityStore.get(context)

        // Without the cache the value must come back from storage, not be regenerated: a new host
        // would orphan every source and policy already keyed by the old one.
        SourceIdentityStore.resetForTest()

        assertThat(SourceIdentityStore.get(context)).isEqualTo(first)
    }

    @Test
    fun `the legacy identity is the one the add-source wizard wrote under`() {
        // Policies stored under this are re-keyed on connect; getting it wrong makes them invisible.
        assertThat(SourceIdentityStore.legacyIdentity().userName).isEqualTo(SourceIdentityStore.USER_NAME)
        assertThat(SourceIdentityStore.legacyIdentity().host).isEqualTo(android.os.Build.MODEL)
    }

    @Test
    fun `the host names the device and carries a per-install suffix`() {
        val host = SourceIdentityStore.get(context).host

        assertThat(host).startsWith("android-")
        // Two phones of the same model must not share a source key.
        assertThat(host).matches("android-.*-[0-9a-f]{6}")
    }

    @Test
    fun `the identity never contains the characters source ids are split on`() {
        val identity = SourceIdentityStore.get(context)

        assertThat(identity.host).doesNotContain("@")
        assertThat(identity.host).doesNotContain(":")
        assertThat(identity.userName).doesNotContain("@")
        assertThat(identity.userName).doesNotContain(":")
    }

    @Test
    fun `model names are reduced to a safe key`() {
        assertThat(SourceIdentityStore.sanitizeModel("Pixel 7 Pro")).isEqualTo("pixel-7-pro")
        assertThat(SourceIdentityStore.sanitizeModel("SM-G991B")).isEqualTo("sm-g991b")
        assertThat(SourceIdentityStore.sanitizeModel("weird@host:name")).isEqualTo("weird-host-name")
        assertThat(SourceIdentityStore.sanitizeModel("  spaced  out  ")).isEqualTo("spaced-out")
        assertThat(SourceIdentityStore.sanitizeModel("!!!")).isEqualTo("unknown")
        assertThat(SourceIdentityStore.sanitizeModel("")).isEqualTo("unknown")
    }

    @Test
    fun `a long model name is bounded`() {
        val sanitized = SourceIdentityStore.sanitizeModel("a".repeat(200))

        assertThat(sanitized.length).isAtMost(SourceIdentityStore.MAX_MODEL_LENGTH)
    }
}
