package org.kopiaKt.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.hashing.HashAlgorithm

/**
 * The two format parameters a user can name at repository creation, and why both are refused before
 * anything is written.
 *
 * `DirectRepositoryImpl.create` writes the format blob to storage **first** and builds the object
 * manager afterwards. So a parameter this build cannot supply does not fail cleanly — it leaves a
 * real repository on disk that the app can never open again. task-74 found exactly that for the hash
 * (`BLAKE2B-256-128` was fine, but the advertised list in the bridge is advisory and a raw string
 * reached creation unchecked), and task-78 gave the splitter the same power by making the declared
 * value actually decide the writer.
 *
 * Neither guard had a test until now.
 */
@DisplayName("Repository-creation guards")
class CreateRepositoryGuardsTest {

    @Test
    fun `an unsupported hash is refused, naming what is available`() {
        val failure = assertThrows<IllegalArgumentException> {
            KopiaRepositoryManagerImpl.requireSupportedHash("BLAKE2S-256")
        }
        assertThat(failure).hasMessageThat().contains("BLAKE2S-256")
        assertThat(failure).hasMessageThat().contains(HashAlgorithm.DEFAULT.id)
    }

    @Test
    fun `an unsupported splitter is refused, naming what is available`() {
        val failure = assertThrows<IllegalArgumentException> {
            KopiaRepositoryManagerImpl.requireSupportedSplitter("DYNAMIC-3M-BUZHASH")
        }
        assertThat(failure).hasMessageThat().contains("DYNAMIC-3M-BUZHASH")
        assertThat(failure).hasMessageThat().contains("FIXED-1M")
    }

    @Test
    fun `naming nothing yields the defaults this build creates`() {
        assertThat(KopiaRepositoryManagerImpl.requireSupportedHash(null))
            .isEqualTo(HashAlgorithm.DEFAULT.id)
        assertThat(KopiaRepositoryManagerImpl.requireSupportedSplitter(null))
            .isEqualTo("DYNAMIC-4M-BUZHASH")
    }

    @Test
    fun `a supported value passes through unchanged`() {
        assertThat(KopiaRepositoryManagerImpl.requireSupportedHash("BLAKE3-256"))
            .isEqualTo("BLAKE3-256")
        assertThat(KopiaRepositoryManagerImpl.requireSupportedSplitter("FIXED-1M"))
            .isEqualTo("FIXED-1M")
    }
}
