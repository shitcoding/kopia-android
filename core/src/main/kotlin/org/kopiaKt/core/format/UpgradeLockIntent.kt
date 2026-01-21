package org.kopiaKt.core.format

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Represents an upgrade lock intent stored in the repository format.
 *
 * This is used during maintenance operations to signal that a repository
 * upgrade is in progress. All clients must respect this lock and drain
 * their I/O accordingly.
 */
@Serializable
data class UpgradeLockIntent(
    /** Unique identifier of the owner holding the lock. */
    val ownerID: String,

    /** When the lock was created. */
    @Serializable(with = InstantSerializer::class)
    val creationTime: Instant,

    /** How long to wait before starting the upgrade (for other clients to notice). */
    @Serializable(with = DurationSerializer::class)
    val advanceNoticeDuration: Duration = DEFAULT_ADVANCE_NOTICE,

    /** How long to wait for I/O operations to drain. */
    @Serializable(with = DurationSerializer::class)
    val ioDrainTimeout: Duration = DEFAULT_IO_DRAIN_TIMEOUT,

    /** Status message describing current upgrade state. */
    val statusMessage: String = "",

    /** Whether the upgrade has been committed. */
    val upgradeCommitted: Boolean = false
) {
    companion object {
        /** Default advance notice duration. */
        val DEFAULT_ADVANCE_NOTICE = 15.minutes

        /** Default I/O drain timeout. */
        val DEFAULT_IO_DRAIN_TIMEOUT = 5.minutes
    }
}

/**
 * Serializer for Instant that uses ISO-8601 format compatible with Go.
 */
object InstantSerializer : kotlinx.serialization.KSerializer<Instant> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "Instant",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING
    )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}
