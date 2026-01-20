package org.kopiaKt.core.kdf

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.params.HKDFParameters
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Key derivation functions matching Go Kopia's implementation.
 *
 * These functions are critical for repository password handling and
 * content encryption key derivation. All implementations must be
 * byte-exact compatible with Go.
 */
object KeyDerivation {

    /**
     * Derives a key using PBKDF2-HMAC-SHA256.
     *
     * This is used for deriving the master key from a user password.
     * Matches Go's `golang.org/x/crypto/pbkdf2.Key` function.
     *
     * @param password The password to derive from
     * @param salt The salt bytes
     * @param iterations Number of iterations (Go Kopia uses 600,000 by default)
     * @param keyLength Length of the output key in bytes
     * @return The derived key
     */
    fun pbkdf2Sha256(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLength * 8)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Derives a key using PBKDF2-HMAC-SHA256 with byte array password.
     *
     * @param password The password bytes
     * @param salt The salt bytes
     * @param iterations Number of iterations
     * @param keyLength Length of the output key in bytes
     * @return The derived key
     */
    fun pbkdf2Sha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray {
        // Convert bytes to chars (each byte becomes a char)
        val chars = CharArray(password.size) { password[it].toInt().toChar() }
        val spec = PBEKeySpec(chars, salt, iterations, keyLength * 8)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Derives a key using HKDF-SHA256.
     *
     * This is used for deriving per-content encryption keys from the master key.
     * Matches Go's `golang.org/x/crypto/hkdf.Key` function.
     *
     * Go signature:
     * ```go
     * func Key(h func() hash.Hash, secret, salt []byte, info string, length int) ([]byte, error)
     * ```
     *
     * @param secret The input key material (IKM)
     * @param salt The salt for the Extract step
     * @param info The info/context for the Expand step
     * @param length Length of the output key in bytes
     * @return The derived key
     */
    fun hkdfSha256(
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val params = HKDFParameters(secret, salt, info)
        hkdf.init(params)

        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)
        return output
    }

    /**
     * Derives a key using HKDF-SHA256 with string info.
     *
     * @param secret The input key material
     * @param salt The salt for the Extract step
     * @param info The info/context string (UTF-8 encoded)
     * @param length Length of the output key in bytes
     * @return The derived key
     */
    fun hkdfSha256(
        secret: ByteArray,
        salt: ByteArray,
        info: String,
        length: Int
    ): ByteArray = hkdfSha256(secret, salt, info.toByteArray(Charsets.UTF_8), length)

    /**
     * Derives a key using scrypt.
     *
     * This is an alternative to PBKDF2 for password-based key derivation.
     * Matches Go's `golang.org/x/crypto/scrypt.Key` function.
     *
     * Go signature:
     * ```go
     * func Key(password, salt []byte, N, r, p, keyLen int) ([]byte, error)
     * ```
     *
     * @param password The password to derive from
     * @param salt The salt bytes
     * @param n CPU/memory cost parameter (must be power of 2)
     * @param r Block size parameter
     * @param p Parallelization parameter
     * @param keyLength Length of the output key in bytes
     * @return The derived key
     */
    fun scrypt(
        password: String,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        keyLength: Int
    ): ByteArray = scrypt(password.toByteArray(Charsets.UTF_8), salt, n, r, p, keyLength)

    /**
     * Derives a key using scrypt with byte array password.
     *
     * @param password The password bytes
     * @param salt The salt bytes
     * @param n CPU/memory cost parameter (must be power of 2)
     * @param r Block size parameter
     * @param p Parallelization parameter
     * @param keyLength Length of the output key in bytes
     * @return The derived key
     */
    fun scrypt(
        password: ByteArray,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        keyLength: Int
    ): ByteArray = SCrypt.generate(password, salt, n, r, p, keyLength)

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
}
