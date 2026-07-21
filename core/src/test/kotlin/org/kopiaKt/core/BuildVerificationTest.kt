package org.kopiaKt.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentIdPrefix
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.splitter.DefaultSplitterFactory
import org.kopiaKt.core.splitter.SplitterAlgorithms

/**
 * Build verification tests to ensure the project compiles and basic types work.
 */
class BuildVerificationTest {

    @Test
    fun `BlobId can be created and converted to string`() {
        val blobId = BlobId("test-blob-123")
        assertThat(blobId.value).isEqualTo("test-blob-123")
        assertThat(blobId.toString()).isEqualTo("test-blob-123")
    }

    @Test
    fun `BlobId factory methods work`() {
        val packBlob = BlobId.packBlob("abc123")
        assertThat(packBlob.value).isEqualTo("pabc123")

        val indexBlob = BlobId.indexBlob("def456")
        assertThat(indexBlob.value).isEqualTo("ndef456")
    }

    @Test
    fun `ContentId can be created and parsed`() {
        val contentId = ContentId.parse("kabc12345") // 9 chars = odd = prefix 'k'
        assertThat(contentId.toString()).isEqualTo("kabc12345")
        assertThat(contentId.prefix).isEqualTo('k')
        assertThat(contentId.hasPrefix).isTrue()
    }

    @Test
    fun `ContentId prefix constants are defined`() {
        assertThat(ContentIdPrefix.MANIFEST).isEqualTo('m')
        assertThat(ContentIdPrefix.PACK_REGULAR).isEqualTo('p')
        assertThat(ContentIdPrefix.PACK_SPECIAL).isEqualTo('q')
    }

    @Test
    fun `HashAlgorithm enum has expected values`() {
        assertThat(HashAlgorithm.DEFAULT).isEqualTo(HashAlgorithm.BLAKE2B_256_128)
        assertThat(HashAlgorithm.BLAKE2B_256_128.outputSize).isEqualTo(16)
        assertThat(HashAlgorithm.BLAKE2B_256_256.outputSize).isEqualTo(32)
        assertThat(HashAlgorithm.BLAKE3_256.outputSize).isEqualTo(32)

        assertThat(HashAlgorithm.fromId("BLAKE2B-256-128"))
            .isEqualTo(HashAlgorithm.BLAKE2B_256_128)
        assertThat(HashAlgorithm.fromId("unknown")).isNull()
    }

    @Test
    fun `EncryptionAlgorithm enum has expected values`() {
        assertThat(EncryptionAlgorithm.DEFAULT)
            .isEqualTo(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256)

        assertThat(EncryptionAlgorithm.fromId("AES256-GCM-HMAC-SHA256"))
            .isEqualTo(EncryptionAlgorithm.AES256_GCM_HMAC_SHA256)
        assertThat(EncryptionAlgorithm.fromId("unknown")).isNull()
    }

    @Test
    fun `CompressionAlgorithm enum has expected values`() {
        assertThat(CompressionAlgorithm.DEFAULT).isEqualTo(CompressionAlgorithm.ZSTD_DEFAULT)
        assertThat(CompressionAlgorithm.NONE.headerId).isEqualTo(0)
        assertThat(CompressionAlgorithm.GZIP_DEFAULT.headerId).isEqualTo(0x1000)
        assertThat(CompressionAlgorithm.ZSTD_DEFAULT.headerId).isEqualTo(0x1100)
        assertThat(CompressionAlgorithm.LZ4_DEFAULT.headerId).isEqualTo(0x1400)

        assertThat(CompressionAlgorithm.fromId("zstd"))
            .isEqualTo(CompressionAlgorithm.ZSTD_DEFAULT)
        assertThat(CompressionAlgorithm.fromHeaderId(0x1100))
            .isEqualTo(CompressionAlgorithm.ZSTD_DEFAULT)
    }

    @Test
    fun `SplitterAlgorithms has expected values`() {
        assertThat(SplitterAlgorithms.DEFAULT_ALGORITHM)
            .isEqualTo("DYNAMIC-4M-BUZHASH")

        // Test size constants
        assertThat(SplitterAlgorithms.SIZE_1M).isEqualTo(1024 * 1024)
        assertThat(SplitterAlgorithms.SIZE_4M).isEqualTo(4 * 1024 * 1024)

        // Test algorithm names
        assertThat(SplitterAlgorithms.FIXED_1M).isEqualTo("FIXED-1M")
        assertThat(SplitterAlgorithms.DYNAMIC_4M_BUZHASH).isEqualTo("DYNAMIC-4M-BUZHASH")
        assertThat(SplitterAlgorithms.DYNAMIC_4M_RABINKARP).isEqualTo("DYNAMIC-4M-RABINKARP")

        // Test factory
        val factory = DefaultSplitterFactory.getFactory(SplitterAlgorithms.DYNAMIC_4M_BUZHASH)
        assertThat(factory).isNotNull()
        val splitter = factory!!.create()
        assertThat(splitter.maxSegmentSize()).isEqualTo(SplitterAlgorithms.SIZE_4M * 2)

        // Test supported algorithms list
        val supported = SplitterAlgorithms.supportedAlgorithms()
        assertThat(supported).contains("FIXED-1M")
        assertThat(supported).contains("DYNAMIC-4M-BUZHASH")
        assertThat(supported).contains("DYNAMIC-4M-RABINKARP")
    }

    @Test
    fun `all core interfaces can be referenced`() {
        // This test ensures all interfaces compile correctly
        assertDoesNotThrow {
            // Blob
            val blobStorageClass = org.kopiaKt.core.blob.BlobStorage::class
            val blobReaderClass = org.kopiaKt.core.blob.BlobReader::class

            // Hashing
            val hasherClass = org.kopiaKt.core.hashing.ContentHasher::class
            val hasherFactoryClass = org.kopiaKt.core.hashing.ContentHasherFactory::class

            // Encryption
            val encryptorClass = org.kopiaKt.core.encryption.Encryptor::class
            val encryptorFactoryClass = org.kopiaKt.core.encryption.EncryptorFactory::class

            // Compression
            val compressorClass = org.kopiaKt.core.compression.Compressor::class
            val compressorFactoryClass = org.kopiaKt.core.compression.CompressorFactory::class

            // Splitter
            val splitterClass = org.kopiaKt.core.splitter.Splitter::class
            val splitterFactoryClass = org.kopiaKt.core.splitter.SplitterFactory::class

            assertThat(blobStorageClass).isNotNull()
            assertThat(blobReaderClass).isNotNull()
            assertThat(hasherClass).isNotNull()
            assertThat(hasherFactoryClass).isNotNull()
            assertThat(encryptorClass).isNotNull()
            assertThat(encryptorFactoryClass).isNotNull()
            assertThat(compressorClass).isNotNull()
            assertThat(compressorFactoryClass).isNotNull()
            assertThat(splitterClass).isNotNull()
            assertThat(splitterFactoryClass).isNotNull()
        }
    }
}
