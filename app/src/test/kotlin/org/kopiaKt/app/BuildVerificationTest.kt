package org.kopiaKt.app

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.Policy

/**
 * Build verification tests for the app module.
 *
 * These tests verify that all modules are properly linked and accessible.
 */
class BuildVerificationTest {

    @Test
    fun `core module is accessible`() {
        val blobId = BlobId("test")
        assertThat(blobId.value).isEqualTo("test")

        assertThat(HashAlgorithm.DEFAULT).isEqualTo(HashAlgorithm.BLAKE2B_256_128)
        assertThat(EncryptionAlgorithm.DEFAULT).isEqualTo(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256)
        assertThat(CompressionAlgorithm.DEFAULT).isEqualTo(CompressionAlgorithm.ZSTD)
    }

    @Test
    fun `snapshot module is accessible`() {
        val source = SourceInfo("host", "user", "/path")
        assertThat(source.toString()).isEqualTo("user@host:/path")

        val policy = Policy()
        assertThat(policy.compression.compressorName).isEqualTo("zstd")
    }

    @Test
    fun `modules can be composed together`() {
        // This test verifies that all modules work together
        // by using types from different modules in combination

        val source = SourceInfo(
            host = "android-device",
            userName = "user",
            path = "/storage/emulated/0/Documents"
        )

        val policy = Policy(
            compression = org.kopiaKt.snapshot.policy.CompressionPolicy(
                compressorName = CompressionAlgorithm.ZSTD.id
            ),
            splitter = org.kopiaKt.snapshot.policy.SplitterPolicy(
                algorithm = org.kopiaKt.core.splitter.SplitterAlgorithm.DYNAMIC_4M_BUZHASH.id
            )
        )

        assertThat(source.host).isEqualTo("android-device")
        assertThat(policy.compression.compressorName).isEqualTo("zstd")
        assertThat(policy.splitter.algorithm).isEqualTo("DYNAMIC-4M-BUZHASH")
    }
}
