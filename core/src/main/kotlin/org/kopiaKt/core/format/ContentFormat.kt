package org.kopiaKt.core.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes the rules for formatting contents in a repository.
 *
 * This includes hash algorithm, encryption settings, and mutable parameters.
 * The sensitive fields (hmacSecret, masterKey) are stored encrypted in the
 * repository format blob.
 */
@Serializable
data class ContentFormat(
    /** Identifier of the hash algorithm used (e.g., "BLAKE2B-256-128"). */
    val hash: String = "",

    /** Identifier of the encryption algorithm used (e.g., "AES256-GCM-HMAC-SHA256"). */
    val encryption: String = "",

    /** Identifier of the ECC algorithm used (optional). */
    val ecc: String = "",

    /** Space overhead percentage for ECC (optional). */
    val eccOverheadPercent: Int = 0,

    /** HMAC secret used to generate encryption keys. */
    @SerialName("secret")
    val hmacSecret: ByteArray = ByteArray(0),

    /** Master encryption key (for SIV-mode encryption only). */
    val masterKey: ByteArray = ByteArray(0),

    /** Format version number. */
    val version: Int = FormatVersion.CURRENT.value,

    /** Maximum size of a pack blob in bytes. */
    val maxPackSize: Int = MutableParameters.DEFAULT_MAX_PACK_SIZE,

    /** Index format version. */
    val indexVersion: Int = MutableParameters.DEFAULT_INDEX_VERSION,

    /** Epoch manager parameters. */
    val epochParameters: EpochParameters = EpochParameters.DEFAULT,

    /** Disables replication of kopia.repository blob in packs. */
    val enablePasswordChange: Boolean = true
) {
    /**
     * Returns the mutable parameters from this content format.
     */
    fun getMutableParameters(): MutableParameters = MutableParameters(
        version = version,
        maxPackSize = maxPackSize,
        indexVersion = indexVersion,
        epochParameters = epochParameters
    )

    /**
     * Applies format options based on the format version.
     *
     * @return A new ContentFormat with version-specific defaults applied
     */
    fun resolveFormatVersion(): ContentFormat {
        return when (version) {
            FormatVersion.V2.value, FormatVersion.V3.value -> copy(
                enablePasswordChange = true,
                indexVersion = 2,
                epochParameters = EpochParameters.DEFAULT
            )
            FormatVersion.V1.value -> copy(
                enablePasswordChange = false,
                indexVersion = 1,
                epochParameters = EpochParameters.DISABLED
            )
            else -> throw IllegalArgumentException("Unsupported format version: $version")
        }
    }

    /**
     * Returns whether password change is supported.
     */
    fun supportsPasswordChange(): Boolean = enablePasswordChange

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContentFormat) return false

        if (hash != other.hash) return false
        if (encryption != other.encryption) return false
        if (ecc != other.ecc) return false
        if (eccOverheadPercent != other.eccOverheadPercent) return false
        if (!hmacSecret.contentEquals(other.hmacSecret)) return false
        if (!masterKey.contentEquals(other.masterKey)) return false
        if (version != other.version) return false
        if (maxPackSize != other.maxPackSize) return false
        if (indexVersion != other.indexVersion) return false
        if (epochParameters != other.epochParameters) return false
        if (enablePasswordChange != other.enablePasswordChange) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.hashCode()
        result = 31 * result + encryption.hashCode()
        result = 31 * result + ecc.hashCode()
        result = 31 * result + eccOverheadPercent
        result = 31 * result + hmacSecret.contentHashCode()
        result = 31 * result + masterKey.contentHashCode()
        result = 31 * result + version
        result = 31 * result + maxPackSize
        result = 31 * result + indexVersion
        result = 31 * result + epochParameters.hashCode()
        result = 31 * result + enablePasswordChange.hashCode()
        return result
    }
}
