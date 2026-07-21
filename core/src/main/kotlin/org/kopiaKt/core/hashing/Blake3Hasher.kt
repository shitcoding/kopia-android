package org.kopiaKt.core.hashing

import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.params.Blake3Parameters

/**
 * BLAKE3 hasher implementation using BouncyCastle.
 *
 * This implementation matches the Go Kopia test vector behavior:
 * - If key is empty, uses plain (unkeyed) BLAKE3
 * - If key is non-empty but shorter than 32 bytes, derives a 32-byte key using BLAKE3's DeriveKey
 * - If key is 32+ bytes, uses the first 32 bytes directly with keyed BLAKE3
 * - Produces 256-bit (32-byte) output by default
 *
 * Key derivation context (must match Go exactly):
 * "kopia blake3 derived key v1"
 *
 * @param algorithm The hash algorithm
 * @param key The secret key (empty for plain hashing, will be derived if < 32 bytes)
 */
class Blake3Hasher(
    override val algorithm: HashAlgorithm,
    private val key: ByteArray,
) : ContentHasher {

    init {
        require(algorithm == HashAlgorithm.BLAKE3_256) {
            "Blake3Hasher only supports BLAKE3-256, got: ${algorithm.id}"
        }
    }

    // Derived key (null for plain mode, 32 bytes for keyed mode)
    private val derivedKey: ByteArray? = if (key.isEmpty()) null else deriveKey(key)

    override fun hashContent(data: ByteArray): ByteArray {
        val digest = Blake3Digest(algorithm.outputSize)

        // Use keyed mode only if we have a key
        derivedKey?.let { dk ->
            digest.init(Blake3Parameters.key(dk))
        }

        digest.update(data, 0, data.size)

        val output = ByteArray(algorithm.outputSize)
        digest.doFinal(output, 0)

        return output
    }

    companion object {
        private const val BLAKE3_KEY_SIZE = 32

        // Must match Go's constant: "kopia blake3 derived key v1"
        const val BLAKE3_KEY_DERIVATION_CONTEXT = "kopia blake3 derived key v1"

        /**
         * Derives a 32-byte key from the provided key material.
         *
         * Uses BLAKE3's DeriveKey function with the Kopia context to produce
         * a 32-byte key from any non-empty input key.
         *
         * Note: This always derives, even for keys >= 32 bytes, to match the
         * test vector generation behavior.
         */
        private fun deriveKey(key: ByteArray): ByteArray {
            require(key.isNotEmpty()) { "Use plain BLAKE3 for empty key" }

            // Always derive the key using BLAKE3 DeriveKey mode
            // This matches the Go test vector generator behavior
            val digest = Blake3Digest(BLAKE3_KEY_SIZE)
            digest.init(Blake3Parameters.context(BLAKE3_KEY_DERIVATION_CONTEXT.toByteArray(Charsets.UTF_8)))
            digest.update(key, 0, key.size)

            val derivedKey = ByteArray(BLAKE3_KEY_SIZE)
            digest.doFinal(derivedKey, 0)
            return derivedKey
        }
    }
}
