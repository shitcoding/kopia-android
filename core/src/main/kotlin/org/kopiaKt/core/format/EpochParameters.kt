package org.kopiaKt.core.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Parameters controlling epoch-based index management.
 *
 * Epochs are used to organize and compact index blobs over time.
 * These parameters control when new epochs are created and how
 * old epochs are cleaned up.
 */
@Serializable
data class EpochParameters(
    /** Whether epoch management is enabled. */
    @SerialName("Enabled")
    val enabled: Boolean = false,

    /** How frequently clients list blobs to determine current epoch. */
    @SerialName("EpochRefreshFrequency")
    @Serializable(with = DurationSerializer::class)
    val epochRefreshFrequency: Duration = 20.minutes,

    /** Number of epochs between full checkpoints. */
    @SerialName("FullCheckpointFrequency")
    val fullCheckpointFrequency: Int = 7,

    /** Don't delete uncompacted blobs if corresponding compacted blob age is less than this. */
    @SerialName("CleanupSafetyMargin")
    @Serializable(with = DurationSerializer::class)
    val cleanupSafetyMargin: Duration = 4.hours,

    /** Minimum duration of an epoch. */
    @SerialName("MinEpochDuration")
    @Serializable(with = DurationSerializer::class)
    val minEpochDuration: Duration = 24.hours,

    /** Advance epoch if number of files exceeds this. */
    @SerialName("EpochAdvanceOnCountThreshold")
    val epochAdvanceOnCountThreshold: Int = 20,

    /** Advance epoch if total size of files exceeds this. */
    @SerialName("EpochAdvanceOnTotalSizeBytesThreshold")
    val epochAdvanceOnTotalSizeBytesThreshold: Long = 10 * 1024 * 1024 * 1024L // 10 GB
) {
    /**
     * Validates the epoch parameters.
     *
     * @throws IllegalArgumentException if parameters are invalid
     */
    fun validate() {
        if (!enabled) return // Disabled epochs don't need validation

        require(epochRefreshFrequency.isPositive()) {
            "Epoch refresh frequency must be positive"
        }
        require(fullCheckpointFrequency > 0) {
            "Full checkpoint frequency must be positive"
        }
        require(cleanupSafetyMargin.isPositive()) {
            "Cleanup safety margin must be positive"
        }
        require(minEpochDuration.isPositive()) {
            "Minimum epoch duration must be positive"
        }
        require(epochAdvanceOnCountThreshold > 0) {
            "Epoch advance count threshold must be positive"
        }
        require(epochAdvanceOnTotalSizeBytesThreshold > 0) {
            "Epoch advance size threshold must be positive"
        }
    }

    companion object {
        /** Default epoch parameters for new repositories. */
        val DEFAULT = EpochParameters(
            enabled = true,
            epochRefreshFrequency = 20.minutes,
            fullCheckpointFrequency = 7,
            cleanupSafetyMargin = 4.hours,
            minEpochDuration = 24.hours,
            epochAdvanceOnCountThreshold = 20,
            epochAdvanceOnTotalSizeBytesThreshold = 10L * 1024 * 1024 * 1024 // 10 GB
        )

        /** Disabled epoch parameters. */
        val DISABLED = EpochParameters(enabled = false)
    }
}
