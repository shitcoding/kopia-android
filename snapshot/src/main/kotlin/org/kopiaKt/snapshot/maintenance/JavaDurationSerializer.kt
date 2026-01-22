package org.kopiaKt.snapshot.maintenance

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Duration

/**
 * Serializer for java.time.Duration that uses Go-compatible nanosecond format.
 *
 * Go's time.Duration is serialized as nanoseconds (int64).
 */
object JavaDurationSerializer : KSerializer<Duration> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Duration", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Duration) {
        encoder.encodeLong(value.toNanos())
    }

    override fun deserialize(decoder: Decoder): Duration {
        return Duration.ofNanos(decoder.decodeLong())
    }
}
