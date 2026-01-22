package org.kopiaKt.snapshot.maintenance

import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Safety parameters for garbage collection operations.
 *
 * These parameters define timing constraints that prevent data loss
 * during concurrent operations (e.g., GC running while snapshot is being created).
 *
 * Go type: maintenance.SafetyParameters
 */
@Serializable
data class SafetyParameters(
    /**
     * Minimum age of content before it can be subject to GC.
     * Content younger than this is always retained.
     * Default: 24 hours
     */
    @Serializable(with = JavaDurationSerializer::class)
    val minContentAgeSubjectToGC: Duration = DEFAULT_MIN_CONTENT_AGE,

    /**
     * Required margin between snapshot GC cycles.
     * Two successful GC cycles with this margin must occur before
     * deleted content can be permanently dropped from indexes.
     * Default: 4 hours
     */
    @Serializable(with = JavaDurationSerializer::class)
    val marginBetweenSnapshotGC: Duration = DEFAULT_MARGIN_BETWEEN_GC,

    /**
     * Whether to require two GC cycles before dropping deleted content.
     * This protects against race conditions between GC and snapshot creation.
     * Default: true
     */
    val requireTwoGCCycles: Boolean = true,

    /**
     * Minimum delay between content rewrite and orphaned pack deletion.
     * Allows other clients to refresh their cached indexes.
     * Default: 4 hours
     */
    @Serializable(with = JavaDurationSerializer::class)
    val minRewriteToOrphanDeletionDelay: Duration = DEFAULT_REWRITE_TO_ORPHAN_DELAY,

    /**
     * Minimum age for orphaned packs before they can be deleted.
     * Default: 24 hours
     */
    @Serializable(with = JavaDurationSerializer::class)
    val orphanedPackMinAge: Duration = DEFAULT_ORPHAN_PACK_MIN_AGE,

    /**
     * Minimum age for content before it can be rewritten to a new pack.
     * Default: 2 hours
     */
    @Serializable(with = JavaDurationSerializer::class)
    val rewriteMinAge: Duration = DEFAULT_REWRITE_MIN_AGE,

    /**
     * Whether to disable eventual consistency safety checks.
     * Should only be set for strongly consistent storage backends.
     * Default: false
     */
    val disableEventualConsistencySafety: Boolean = false
) {
    companion object {
        /**
         * Default minimum content age before GC (24 hours).
         */
        val DEFAULT_MIN_CONTENT_AGE: Duration = Duration.ofHours(24)

        /**
         * Default margin between GC cycles (4 hours).
         */
        val DEFAULT_MARGIN_BETWEEN_GC: Duration = Duration.ofHours(4)

        /**
         * Default delay between rewrite and orphan deletion (4 hours).
         */
        val DEFAULT_REWRITE_TO_ORPHAN_DELAY: Duration = Duration.ofHours(4)

        /**
         * Default minimum age for orphaned packs (24 hours).
         */
        val DEFAULT_ORPHAN_PACK_MIN_AGE: Duration = Duration.ofHours(24)

        /**
         * Default minimum age for content rewrite (2 hours).
         */
        val DEFAULT_REWRITE_MIN_AGE: Duration = Duration.ofHours(2)

        /**
         * Default safety parameters.
         */
        val Default = SafetyParameters()
    }
}
