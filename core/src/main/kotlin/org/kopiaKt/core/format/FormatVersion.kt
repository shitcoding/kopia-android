package org.kopiaKt.core.format

/**
 * Repository format version.
 *
 * Kopia uses versioning to track format changes:
 * - Version 1: Original format (v0.8 and earlier)
 * - Version 2: Introduced in v0.9, adds password change support and index V2
 * - Version 3: Current default (v0.11+), same features as V2
 */
@JvmInline
value class FormatVersion(val value: Int) : Comparable<FormatVersion> {
    init {
        require(value in 1..MAX_VERSION) {
            "Format version must be between 1 and $MAX_VERSION, got $value"
        }
    }

    override fun compareTo(other: FormatVersion): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        /** Original format (v0.8 and earlier). */
        val V1 = FormatVersion(1)

        /** Introduced in v0.9 - adds password change support and index V2. */
        val V2 = FormatVersion(2)

        /** Current default (v0.11+) - same features as V2. */
        val V3 = FormatVersion(3)

        /** Maximum supported format version. */
        const val MAX_VERSION = 3

        /** Current version for new repositories. */
        val CURRENT = V3

        /** Minimum version this client can read. */
        val MIN_SUPPORTED_READ = V1

        /** Maximum version this client can read. */
        val MAX_SUPPORTED_READ = V3

        /** Minimum version this client can write. */
        val MIN_SUPPORTED_WRITE = V1

        /** Maximum version this client can write. */
        val MAX_SUPPORTED_WRITE = V3
    }
}
