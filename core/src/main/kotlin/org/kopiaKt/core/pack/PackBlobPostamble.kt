package org.kopiaKt.core.pack

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Represents the postamble at the end of a pack blob file.
 *
 * The postamble contains recovery information for the local index
 * embedded in the pack blob. It allows recovering content entries
 * if the main repository index is damaged.
 *
 * Binary format (Go-compatible):
 * - Version varint (always 1)
 * - IV length varint
 * - Local index IV (variable length)
 * - Local index offset varint
 * - Local index length varint
 * - CRC32 checksum (4 bytes, big-endian, IEEE polynomial)
 * - Postamble length (1 byte, must be < 256)
 *
 * @property localIndexIV The IV used to encrypt the local index
 * @property localIndexOffset Byte offset where the encrypted local index starts in the pack blob
 * @property localIndexLength Length of the encrypted local index in bytes
 */
data class PackBlobPostamble(
    val localIndexIV: ByteArray,
    val localIndexOffset: UInt,
    val localIndexLength: UInt
) {
    /**
     * Serializes this postamble to bytes.
     *
     * @return The serialized postamble bytes
     * @throws IllegalArgumentException if the postamble would be too long (> 255 bytes)
     */
    fun toBytes(): ByteArray {
        val buffer = mutableListOf<Byte>()

        // Version 1
        buffer.addAll(encodeVarint(POSTAMBLE_VERSION))
        // IV length
        buffer.addAll(encodeVarint(localIndexIV.size.toULong()))
        // IV
        buffer.addAll(localIndexIV.toList())
        // Offset
        buffer.addAll(encodeVarint(localIndexOffset.toULong()))
        // Length
        buffer.addAll(encodeVarint(localIndexLength.toULong()))

        val payload = buffer.toByteArray()
        val payloadWithChecksum = payload.size + 4 // 4 bytes for CRC32

        require(payloadWithChecksum < 256) {
            "Postamble too long: $payloadWithChecksum bytes (max 255)"
        }

        // Compute CRC32 of payload
        val checksum = computeCRC32(payload)

        // Build final result
        val result = ByteArray(payloadWithChecksum + 1)
        payload.copyInto(result)

        // Add checksum (big-endian)
        ByteBuffer.wrap(result, payload.size, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(checksum)

        // Last byte is the length of everything before it
        result[result.size - 1] = payloadWithChecksum.toByte()

        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackBlobPostamble) return false

        if (!localIndexIV.contentEquals(other.localIndexIV)) return false
        if (localIndexOffset != other.localIndexOffset) return false
        if (localIndexLength != other.localIndexLength) return false

        return true
    }

    override fun hashCode(): Int {
        var result = localIndexIV.contentHashCode()
        result = 31 * result + localIndexOffset.hashCode()
        result = 31 * result + localIndexLength.hashCode()
        return result
    }

    companion object {
        /**
         * Current postamble version.
         */
        const val POSTAMBLE_VERSION = 1uL

        /**
         * Minimum postamble size (checksum + length byte minimum).
         */
        const val MIN_POSTAMBLE_SIZE = 5

        /**
         * Attempts to find and parse a postamble at the end of the given data.
         *
         * This is designed for data recovery and the postamble is not
         * cryptographically signed, so it should be validated against
         * other repository data when possible.
         *
         * @param data The data to search (typically the end of a pack blob)
         * @return The parsed postamble, or null if not found or invalid
         */
        fun findPostamble(data: ByteArray): PackBlobPostamble? {
            if (data.isEmpty()) {
                return null
            }

            // Length of postamble is the last byte
            val postambleLength = data.last().toInt() and 0xFF
            if (postambleLength < MIN_POSTAMBLE_SIZE) {
                return null
            }

            val postambleStart = data.size - 1 - postambleLength
            if (postambleStart < 0) {
                return null
            }

            val postambleEnd = data.size - 1 // Exclude the length byte itself
            val postambleBytes = data.copyOfRange(postambleStart, postambleEnd)

            // Split into payload and checksum
            if (postambleBytes.size < 4) {
                return null
            }

            val payload = postambleBytes.copyOfRange(0, postambleBytes.size - 4)
            val checksumBytes = postambleBytes.copyOfRange(postambleBytes.size - 4, postambleBytes.size)

            // Verify checksum
            val storedChecksum = ByteBuffer.wrap(checksumBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            val computedChecksum = computeCRC32(payload)

            if (storedChecksum != computedChecksum) {
                return null
            }

            return decodePostamble(payload)
        }

        /**
         * Decodes the postamble payload (after checksum verification).
         */
        private fun decodePostamble(payload: ByteArray): PackBlobPostamble? {
            var offset = 0

            // Version
            val (version, versionLen) = decodeVarint(payload, offset)
            if (versionLen <= 0 || version != POSTAMBLE_VERSION) {
                return null
            }
            offset += versionLen

            // IV length
            val (ivLength, ivLenLen) = decodeVarint(payload, offset)
            if (ivLenLen <= 0) {
                return null
            }
            offset += ivLenLen

            // IV
            if (offset + ivLength.toInt() > payload.size) {
                return null
            }
            val iv = payload.copyOfRange(offset, offset + ivLength.toInt())
            offset += ivLength.toInt()

            // Index offset
            val (indexOffset, offsetLen) = decodeVarint(payload, offset)
            if (offsetLen <= 0) {
                return null
            }
            offset += offsetLen

            // Index length
            val (indexLength, lengthLen) = decodeVarint(payload, offset)
            if (lengthLen <= 0) {
                return null
            }

            return PackBlobPostamble(
                localIndexIV = iv,
                localIndexOffset = indexOffset.toUInt(),
                localIndexLength = indexLength.toUInt()
            )
        }

        /**
         * Encodes a value as a varint (Go-compatible unsigned LEB128).
         */
        private fun encodeVarint(value: ULong): List<Byte> {
            val result = mutableListOf<Byte>()
            var v = value
            while (v >= 0x80uL) {
                result.add(((v and 0x7FuL) or 0x80uL).toByte())
                v = v shr 7
            }
            result.add(v.toByte())
            return result
        }

        /**
         * Decodes a varint from the given offset.
         *
         * @return Pair of (value, bytes consumed), or (0, 0) if invalid
         */
        private fun decodeVarint(data: ByteArray, startOffset: Int): Pair<ULong, Int> {
            if (startOffset >= data.size) {
                return 0uL to 0
            }

            var result = 0uL
            var shift = 0
            var offset = startOffset

            while (offset < data.size) {
                val b = data[offset].toInt() and 0xFF
                result = result or ((b.toULong() and 0x7FuL) shl shift)
                offset++

                if ((b and 0x80) == 0) {
                    break
                }
                shift += 7

                // Prevent infinite loop on malformed data
                if (shift > 63) {
                    return 0uL to 0
                }
            }

            return result to (offset - startOffset)
        }

        /**
         * Computes IEEE CRC32 checksum.
         */
        private fun computeCRC32(data: ByteArray): Int {
            val crc = CRC32()
            crc.update(data)
            return crc.value.toInt()
        }
    }
}
