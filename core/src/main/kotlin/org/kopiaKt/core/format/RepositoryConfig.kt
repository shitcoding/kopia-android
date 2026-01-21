package org.kopiaKt.core.format

import kotlinx.serialization.Serializable

/**
 * Describes the format of objects in a repository.
 *
 * The contents of this object are stored encrypted since they contain
 * sensitive key material (HMAC secret, master key).
 */
@Serializable
data class RepositoryConfig(
    // ContentFormat fields (flattened)
    /** Identifier of the hash algorithm used. */
    val hash: String = "",

    /** Identifier of the encryption algorithm used. */
    val encryption: String = "",

    /** Identifier of the ECC algorithm used. */
    val ecc: String = "",

    /** Space overhead percentage for ECC. */
    val eccOverheadPercent: Int = 0,

    /** HMAC secret used to generate encryption keys. */
    val secret: ByteArray = ByteArray(0),

    /** Master encryption key. */
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
    val enablePasswordChange: Boolean = true,

    // ObjectFormat fields (flattened)
    /** Splitter algorithm used to break objects into chunks. */
    val splitter: String = ObjectFormat.DEFAULT_SPLITTER,

    // Additional config fields
    /** Upgrade lock intent (during maintenance). */
    val upgradeLock: UpgradeLockIntent? = null,

    /** Required features for this repository. */
    val requiredFeatures: List<String> = emptyList()
) {
    /**
     * Returns the content format portion of this config.
     */
    fun getContentFormat(): ContentFormat = ContentFormat(
        hash = hash,
        encryption = encryption,
        ecc = ecc,
        eccOverheadPercent = eccOverheadPercent,
        hmacSecret = secret,
        masterKey = masterKey,
        version = version,
        maxPackSize = maxPackSize,
        indexVersion = indexVersion,
        epochParameters = epochParameters,
        enablePasswordChange = enablePasswordChange
    )

    /**
     * Returns the object format portion of this config.
     */
    fun getObjectFormat(): ObjectFormat = ObjectFormat(splitter = splitter)

    /**
     * Returns the mutable parameters from this config.
     */
    fun getMutableParameters(): MutableParameters = MutableParameters(
        version = version,
        maxPackSize = maxPackSize,
        indexVersion = indexVersion,
        epochParameters = epochParameters
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RepositoryConfig) return false

        if (hash != other.hash) return false
        if (encryption != other.encryption) return false
        if (ecc != other.ecc) return false
        if (eccOverheadPercent != other.eccOverheadPercent) return false
        if (!secret.contentEquals(other.secret)) return false
        if (!masterKey.contentEquals(other.masterKey)) return false
        if (version != other.version) return false
        if (maxPackSize != other.maxPackSize) return false
        if (indexVersion != other.indexVersion) return false
        if (epochParameters != other.epochParameters) return false
        if (enablePasswordChange != other.enablePasswordChange) return false
        if (splitter != other.splitter) return false
        if (upgradeLock != other.upgradeLock) return false
        if (requiredFeatures != other.requiredFeatures) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.hashCode()
        result = 31 * result + encryption.hashCode()
        result = 31 * result + ecc.hashCode()
        result = 31 * result + eccOverheadPercent
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + masterKey.contentHashCode()
        result = 31 * result + version
        result = 31 * result + maxPackSize
        result = 31 * result + indexVersion
        result = 31 * result + epochParameters.hashCode()
        result = 31 * result + enablePasswordChange.hashCode()
        result = 31 * result + splitter.hashCode()
        result = 31 * result + (upgradeLock?.hashCode() ?: 0)
        result = 31 * result + requiredFeatures.hashCode()
        return result
    }

    companion object {
        /**
         * Creates a RepositoryConfig from content and object formats.
         */
        fun from(contentFormat: ContentFormat, objectFormat: ObjectFormat): RepositoryConfig =
            RepositoryConfig(
                hash = contentFormat.hash,
                encryption = contentFormat.encryption,
                ecc = contentFormat.ecc,
                eccOverheadPercent = contentFormat.eccOverheadPercent,
                secret = contentFormat.hmacSecret,
                masterKey = contentFormat.masterKey,
                version = contentFormat.version,
                maxPackSize = contentFormat.maxPackSize,
                indexVersion = contentFormat.indexVersion,
                epochParameters = contentFormat.epochParameters,
                enablePasswordChange = contentFormat.enablePasswordChange,
                splitter = objectFormat.splitter
            )
    }
}

/**
 * Wrapper for encrypted repository configuration.
 *
 * This matches Go's EncryptedRepositoryConfig structure.
 */
@Serializable
data class EncryptedRepositoryConfig(
    val format: RepositoryConfig
)
