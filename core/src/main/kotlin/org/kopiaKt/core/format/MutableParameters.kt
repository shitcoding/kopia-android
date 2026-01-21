package org.kopiaKt.core.format

import kotlinx.serialization.Serializable

/**
 * Parameters of the content manager that can be mutated after repository creation.
 */
@Serializable
data class MutableParameters(
    /** Format version number (1, 2, or 3). */
    val version: Int = FormatVersion.CURRENT.value,

    /** Maximum size of a pack blob in bytes. */
    val maxPackSize: Int = DEFAULT_MAX_PACK_SIZE,

    /** Index format version (1 or 2). */
    val indexVersion: Int = DEFAULT_INDEX_VERSION,

    /** Epoch manager parameters. */
    val epochParameters: EpochParameters = EpochParameters.DEFAULT
) {
    /**
     * Validates the mutable parameters.
     *
     * @throws IllegalArgumentException if parameters are invalid
     */
    fun validate() {
        require(version in 1..FormatVersion.MAX_VERSION) {
            "Version must be between 1 and ${FormatVersion.MAX_VERSION}, got $version"
        }
        require(maxPackSize >= MIN_VALID_PACK_SIZE) {
            "Max pack size too small, must be >= ${MIN_VALID_PACK_SIZE / 1_000_000} MB"
        }
        require(maxPackSize <= MAX_VALID_PACK_SIZE) {
            "Max pack size too big, must be <= ${MAX_VALID_PACK_SIZE / 1_000_000} MB"
        }
        require(indexVersion in 1..2) {
            "Invalid index version, supported versions are 1 & 2"
        }
        epochParameters.validate()
    }

    companion object {
        /** Default maximum pack size (20 MB). */
        const val DEFAULT_MAX_PACK_SIZE = 20 * 1024 * 1024

        /** Minimum valid pack size (10 MB). */
        const val MIN_VALID_PACK_SIZE = 10 * 1024 * 1024

        /** Maximum valid pack size (120 MB). */
        const val MAX_VALID_PACK_SIZE = 120 * 1024 * 1024

        /** Default index version. */
        const val DEFAULT_INDEX_VERSION = 2

        /** Legacy index version (for format V1). */
        const val LEGACY_INDEX_VERSION = 1
    }
}
