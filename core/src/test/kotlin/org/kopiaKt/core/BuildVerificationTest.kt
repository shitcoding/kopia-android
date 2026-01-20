package org.kopiaKt.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentIdPrefix
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.splitter.SplitterAlgorithm
import com.google.common.truth.Truth.assertThat

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
    fun `ContentId can be created`() {
        val contentId = ContentId("kabc123")
        assertThat(contentId.value).isEqualTo("kabc123")
        assertThat(contentId.prefix).isEqualTo('k')
        assertThat(contentId.isPacked).isTrue()
    }

    @Test
    fun `ContentId prefix constants are defined`() {
        assertThat(ContentIdPrefix.MANIFEST).isEqualTo('m')
        assertThat(ContentIdPrefix.PACKED).isEqualTo('p')
        assertThat(ContentIdPrefix.INDEX).isEqualTo('i')
        assertThat(ContentIdPrefix.DIRECTORY).isEqualTo('d')
        assertThat(ContentIdPrefix.REGULAR).isEqualTo('k')
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
        assertThat(CompressionAlgorithm.DEFAULT).isEqualTo(CompressionAlgorithm.ZSTD)
        assertThat(CompressionAlgorithm.NONE.headerId).isEqualTo(0)
        assertThat(CompressionAlgorithm.GZIP.headerId).isEqualTo(1)
        assertThat(CompressionAlgorithm.ZSTD.headerId).isEqualTo(3)
        assertThat(CompressionAlgorithm.LZ4.headerId).isEqualTo(4)

        assertThat(CompressionAlgorithm.fromId("zstd"))
            .isEqualTo(CompressionAlgorithm.ZSTD)
        assertThat(CompressionAlgorithm.fromHeaderId(3))
            .isEqualTo(CompressionAlgorithm.ZSTD)
    }

    @Test
    fun `SplitterAlgorithm enum has expected values`() {
        assertThat(SplitterAlgorithm.DEFAULT).isEqualTo(SplitterAlgorithm.DYNAMIC_4M_BUZHASH)

        val fixed = SplitterAlgorithm.FIXED_1M
        assertThat(fixed.isFixed).isTrue()
        assertThat(fixed.isDynamic).isFalse()
        assertThat(fixed.minSize).isEqualTo(fixed.avgSize)
        assertThat(fixed.avgSize).isEqualTo(fixed.maxSize)

        val dynamic = SplitterAlgorithm.DYNAMIC_4M_BUZHASH
        assertThat(dynamic.isFixed).isFalse()
        assertThat(dynamic.isDynamic).isTrue()
        assertThat(dynamic.usesBuilzhash).isTrue()
        assertThat(dynamic.usesRabinKarp).isFalse()
        assertThat(dynamic.minSize).isLessThan(dynamic.avgSize)
        assertThat(dynamic.avgSize).isLessThan(dynamic.maxSize)

        assertThat(SplitterAlgorithm.fromId("DYNAMIC-4M-BUZHASH"))
            .isEqualTo(SplitterAlgorithm.DYNAMIC_4M_BUZHASH)
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
