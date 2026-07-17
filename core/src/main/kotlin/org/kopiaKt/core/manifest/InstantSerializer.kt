package org.kopiaKt.core.manifest

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.kopiaKt.core.time.formatRfc3339Nano
import java.time.Instant

/**
 * Serializer for java.time.Instant to/from Go's RFC3339Nano format
 * ("2006-01-02T15:04:05.999999999Z07:00"). Serializes byte-identically to Go via
 * [formatRfc3339Nano]; parses both fractional and non-fractional forms.
 */
internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(formatRfc3339Nano(value))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val str = decoder.decodeString()
        return Instant.parse(str)
    }
}
