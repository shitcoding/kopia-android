package org.kopiaKt.core.manifest

import java.security.SecureRandom

/**
 * Represents a unique identifier for a manifest.
 *
 * ManifestId format (matching Go implementation):
 * - 16 bytes of cryptographically secure random data
 * - Hex-encoded to a 32-character lowercase string
 *
 * @property value The 32-character hex string identifier
 */
@JvmInline
value class ManifestId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        /**
         * Length of manifest ID in bytes.
         */
        const val LENGTH_BYTES = 16

        /**
         * Length of manifest ID as hex string (16 bytes * 2 = 32 chars).
         */
        const val LENGTH_HEX = LENGTH_BYTES * 2

        /**
         * Secure random for ID generation.
         */
        private val secureRandom = SecureRandom()

        /**
         * Generates a new random ManifestId.
         *
         * @return A new unique ManifestId
         */
        fun generate(): ManifestId {
            val bytes = ByteArray(LENGTH_BYTES)
            secureRandom.nextBytes(bytes)
            return ManifestId(bytes.toHexString())
        }

        /**
         * Creates a ManifestId from a string value.
         *
         * @param value The 32-character hex string
         * @return The ManifestId
         * @throws IllegalArgumentException if the value is invalid
         */
        operator fun invoke(value: String): ManifestId {
            val normalized = value.lowercase()
            require(normalized.length == LENGTH_HEX) {
                "ManifestId must be $LENGTH_HEX characters, got ${value.length}"
            }
            require(normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
                "ManifestId must contain only hex characters"
            }
            return ManifestId(normalized)
        }
    }
}

/**
 * Extension function to convert ByteArray to lowercase hex string.
 */
private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
