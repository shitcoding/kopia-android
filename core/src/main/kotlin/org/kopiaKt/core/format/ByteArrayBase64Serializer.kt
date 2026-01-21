package org.kopiaKt.core.format

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * Serializer for ByteArray that uses standard Base64 encoding.
 *
 * This matches Go's encoding/json behavior for []byte fields.
 */
object ByteArrayBase64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ByteArrayBase64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        val str = decoder.decodeString()
        return if (str.isEmpty()) {
            ByteArray(0)
        } else {
            Base64.getDecoder().decode(str)
        }
    }
}
