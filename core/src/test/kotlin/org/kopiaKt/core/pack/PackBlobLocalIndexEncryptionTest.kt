package org.kopiaKt.core.pack

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.encryption.Aes256GcmHmacSha256Encryptor
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import java.security.SecureRandom

/**
 * Byte-compatibility tests for the encrypted pack-blob local (recovery) index (task-13).
 *
 * Go kopia stores the pack's embedded recovery index ENCRYPTED: the IV/AAD is the repo's keyed hash
 * of the plaintext serialized index (default keyed-BLAKE2b-256 truncated to 16 bytes), and the bytes
 * are `nonce || AES-256-GCM(plaintext, AAD=IV)`. The nonce is random per write, so byte-exact output
 * is impossible (Go is non-deterministic too) — the contract is MUTUAL RECOVERABILITY, verified here
 * as a Kotlin round-trip plus the exact Go IV/length invariants.
 */
@DisplayName("Pack-blob local index encryption (Go compat)")
class PackBlobLocalIndexEncryptionTest {

    private val rnd = SecureRandom()
    private fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    private fun hasher() = DefaultContentHasherFactory().create(HashAlgorithm.BLAKE2B_256_128, randomBytes(32))

    private fun encryptor() = Aes256GcmHmacSha256Encryptor.create(randomBytes(32))

    private fun builder(overhead: Int) = PackBlobBuilder(
        packBlobId = BlobId.packBlob("test123456789012"),
        preambleLength = 32,
        encryptionOverhead = overhead,
    )

    @Test
    fun `encrypted local index round-trips through recoverIndex only with the decryptor`(): Unit = runTest {
        val hasher = hasher()
        val encryptor = encryptor()

        val builder = builder(encryptor.overhead)
        val contents = listOf(
            ContentId.parse("aaaa000011112222") to ByteArray(50) { 0xAA.toByte() },
            ContentId.parse("bbbb333344445555") to ByteArray(75) { 0xBB.toByte() },
            ContentId.parse("cccc666677778888") to ByteArray(100) { 0xCC.toByte() },
        )
        for ((cid, data) in contents) {
            builder.addContent(cid, data, originalLength = (data.size - encryptor.overhead).toUInt())
        }

        val (packData, originalInfos) = builder.buildEncrypted(hasher, encryptor)

        // The local index is encrypted, so a plaintext parse must NOT recover it.
        assertThat(PackBlobReader.recoverIndex(packData, encryptor.overhead.toUInt())).isNull()

        // With the matching decryptor, recovery succeeds and yields the same content ids.
        val recovered = PackBlobReader.recoverIndex(packData, encryptor.overhead.toUInt()) { ct, iv ->
            encryptor.decryptWithRawId(ct, iv)
        }
        assertThat(recovered).isNotNull()
        assertThat(recovered!!.map { it.contentId }.toSet())
            .isEqualTo(originalInfos.map { it.contentId }.toSet())
    }

    @Test
    fun `postamble IV is the repo hash of the decrypted index and length spans the ciphertext`(): Unit = runTest {
        val hasher = hasher()
        val encryptor = encryptor()

        val builder = builder(encryptor.overhead)
        builder.addContent(
            ContentId.parse("1234567890abcdef"),
            ByteArray(64) { it.toByte() },
            originalLength = 36u,
        )

        val (packData, _) = builder.buildEncrypted(hasher, encryptor)

        val postamble = PackBlobPostamble.findPostamble(packData)
        assertThat(postamble).isNotNull()

        // The stored span is the CIPHERTEXT (nonce + ciphertext + tag), not the plaintext index.
        val ciphertext = packData.copyOfRange(
            postamble!!.localIndexOffset.toInt(),
            postamble.localIndexOffset.toInt() + postamble.localIndexLength.toInt(),
        )
        val plaintextIndex = encryptor.decryptWithRawId(ciphertext, postamble.localIndexIV)
        assertThat(postamble.localIndexLength.toInt())
            .isEqualTo(plaintextIndex.size + encryptor.overhead)

        // Go invariant: localIndexIV == HashFunc(plaintext index) (repo keyed hash, full output).
        assertThat(postamble.localIndexIV).isEqualTo(hasher.hashContent(plaintextIndex))
    }

    @Test
    fun `encryptWithRawId and decryptWithRawId round-trip with an arbitrary IV`(): Unit = runTest {
        val encryptor = encryptor()
        val iv = randomBytes(16)
        val plaintext = randomBytes(200)

        val ciphertext = encryptor.encryptWithRawId(plaintext, iv)
        assertThat(ciphertext.size).isEqualTo(plaintext.size + encryptor.overhead)
        assertThat(encryptor.decryptWithRawId(ciphertext, iv)).isEqualTo(plaintext)
    }

    @Test
    fun `recoverIndex returns null instead of throwing on an overflowing postamble offset`() {
        // The postamble is untrusted recovery input. A CRC-valid but corrupt offset/length whose sum
        // overflows Int must be rejected (null), not slip past the bounds check into an out-of-range
        // copyOfRange. 2e9 + 2e9 overflows a signed Int but not the pack's actual size.
        val postamble = PackBlobPostamble(
            localIndexIV = ByteArray(16),
            localIndexOffset = 2_000_000_000u,
            localIndexLength = 2_000_000_000u,
        )
        val packData = ByteArray(64) + postamble.toBytes()

        assertThat(PackBlobReader.recoverIndex(packData)).isNull()
    }
}
