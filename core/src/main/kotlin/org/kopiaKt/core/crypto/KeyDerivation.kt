package org.kopiaKt.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * PBKDF2-HMAC-SHA256 key derivation function.
 *
 * Used for deriving encryption keys from passwords.
 * Kopia default: 600,000 iterations.
 *
 * Uses BouncyCastle's PKCS5S2 generator directly for byte-exact compatibility
 * with Go's PBKDF2 implementation (which operates on raw bytes, not chars).
 */
class Pbkdf2KeyDerivation {

    init {
        ensureBouncyCastleProvider()
    }

    /**
     * Derive a key from a password using PBKDF2-HMAC-SHA256.
     *
     * @param password The password bytes (typically UTF-8 encoded)
     * @param salt Random salt bytes
     * @param iterations Number of iterations (Kopia default: 600,000)
     * @param keyLength Desired key length in bytes
     * @return Derived key bytes
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int,
    ): ByteArray {
        // Use BouncyCastle's PKCS5S2 generator directly with byte array password
        // This matches Go's behavior which uses raw bytes for PBKDF2
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(password, salt, iterations)

        val keyParam = generator.generateDerivedMacParameters(keyLength * 8) as KeyParameter
        return keyParam.key
    }

    companion object {
        /** Kopia's default iteration count for PBKDF2 */
        const val DEFAULT_ITERATIONS = 600_000
    }
}

/**
 * Scrypt key derivation function.
 *
 * Memory-hard password-based key derivation.
 * Kopia defaults: N=65536, r=8, p=1.
 */
class ScryptKeyDerivation {

    init {
        ensureBouncyCastleProvider()
    }

    /**
     * Derive a key from a password using Scrypt.
     *
     * @param password The password bytes (typically UTF-8 encoded)
     * @param salt Random salt bytes
     * @param n CPU/memory cost parameter (must be power of 2)
     * @param r Block size parameter
     * @param p Parallelization parameter
     * @param keyLength Desired key length in bytes
     * @return Derived key bytes
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        keyLength: Int,
    ): ByteArray = SCrypt.generate(password, salt, n, r, p, keyLength)

    companion object {
        /** Kopia's default N parameter for Scrypt */
        const val DEFAULT_N = 65536

        /** Kopia's default r parameter for Scrypt */
        const val DEFAULT_R = 8

        /** Kopia's default p parameter for Scrypt */
        const val DEFAULT_P = 1
    }
}

/**
 * HKDF-SHA256 key derivation function.
 *
 * Used for deriving content encryption keys from master key.
 * HKDF consists of extract and expand phases.
 */
class HkdfSha256KeyDerivation {

    init {
        ensureBouncyCastleProvider()
    }

    /**
     * Derive a key using HKDF-SHA256.
     *
     * @param masterKey Input keying material
     * @param salt Optional salt (if empty, defaults to hash-length zeros)
     * @param info Context and application specific information
     * @param length Desired output key length in bytes
     * @return Derived key bytes
     */
    fun derive(
        masterKey: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(org.bouncycastle.crypto.digests.SHA256Digest())

        // Use null salt if empty to match Go's behavior (uses default of zeros)
        val effectiveSalt = if (salt.isEmpty()) null else salt

        val params = HKDFParameters(masterKey, effectiveSalt, info)
        hkdf.init(params)

        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)

        return output
    }
}

/**
 * Factory for creating key derivation instances.
 */
interface KeyDerivationFactory {
    fun createPbkdf2(): Pbkdf2KeyDerivation
    fun createScrypt(): ScryptKeyDerivation
    fun createHkdf(): HkdfSha256KeyDerivation
}

/**
 * Default implementation of KeyDerivationFactory.
 */
class DefaultKeyDerivationFactory : KeyDerivationFactory {
    override fun createPbkdf2(): Pbkdf2KeyDerivation = Pbkdf2KeyDerivation()
    override fun createScrypt(): ScryptKeyDerivation = ScryptKeyDerivation()
    override fun createHkdf(): HkdfSha256KeyDerivation = HkdfSha256KeyDerivation()
}

/**
 * Ensure BouncyCastle provider is registered.
 */
private fun ensureBouncyCastleProvider() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(BouncyCastleProvider())
    }
}

/**
 * Derives a key from a password using the specified algorithm.
 *
 * Supported algorithms:
 * - "scrypt-65536-8-1" (default, most secure)
 * - "pbkdf2-sha256-600000"
 *
 * @param password The password string
 * @param salt Salt bytes (typically 32 bytes)
 * @param keyLength Desired key length in bytes
 * @param algorithm Algorithm identifier string
 * @return Derived key bytes
 */
fun deriveKeyFromPassword(
    password: String,
    salt: ByteArray,
    keyLength: Int,
    algorithm: String,
): ByteArray {
    val passwordBytes = password.toByteArray(Charsets.UTF_8)

    return when {
        algorithm.startsWith("scrypt-") -> {
            // Parse scrypt parameters from algorithm string: "scrypt-N-r-p"
            val parts = algorithm.removePrefix("scrypt-").split("-")
            require(parts.size == 3) { "Invalid scrypt algorithm format: $algorithm" }

            val n = parts[0].toInt()
            val r = parts[1].toInt()
            val p = parts[2].toInt()

            ScryptKeyDerivation().derive(passwordBytes, salt, n, r, p, keyLength)
        }
        algorithm.startsWith("pbkdf2-sha256-") -> {
            // Parse PBKDF2 iterations from algorithm string: "pbkdf2-sha256-iterations"
            val iterations = algorithm.removePrefix("pbkdf2-sha256-").toInt()
            Pbkdf2KeyDerivation().derive(passwordBytes, salt, iterations, keyLength)
        }
        else -> throw IllegalArgumentException("Unknown key derivation algorithm: $algorithm")
    }
}
