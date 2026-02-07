package org.kopiaKt.core.pack

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.content.hexToByteArray
import org.kopiaKt.core.content.toHexString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pack Index Version 1 format.
 *
 * This format does not support content-level compression metadata.
 * Original length is computed from packed length minus encryption overhead.
 *
 * Binary format:
 * - Header (8 bytes):
 *   - Byte 0: Version (0x01)
 *   - Byte 1: Key size (content ID byte length including prefix marker)
 *   - Bytes 2-3: Entry size (big-endian uint16, always 20 for V1)
 *   - Bytes 4-7: Entry count (big-endian uint32)
 * - Entries (sorted by key):
 *   - Key: keySize bytes (content ID as bytes)
 *   - Entry: 20 bytes (content metadata)
 * - Extra data: Pack blob ID strings
 *
 * Entry format (20 bytes):
 * - Bytes 0-7: Timestamp + format version + pack blob ID length
 *   - Bits 63-16: Timestamp seconds (48 bits)
 *   - Bits 15-8: Format version (8 bits)
 *   - Bits 7-0: Pack blob ID length (8 bits)
 * - Bytes 8-11: Pack blob ID offset in extra data (big-endian uint32)
 * - Bytes 12-15: Deleted flag (bit 31) + Pack offset (bits 0-30)
 * - Bytes 16-19: Packed length (big-endian uint32)
 */
object PackIndexV1 {
    const val VERSION = 1
    const val HEADER_SIZE = 8
    const val ENTRY_SIZE = 20
    const val DELETED_MARKER = 0x80000000.toInt()
    const val PACK_OFFSET_MASK = 0x7FFFFFFF
    const val MAX_ENTRY_SIZE = 256

    /**
     * Header information parsed from a V1 index.
     */
    data class HeaderInfo(
        val version: Int,
        val keySize: Int,
        val entrySize: Int,
        val entryCount: Int
    )

    /**
     * Parsed entry data before content ID association.
     */
    data class ParsedEntry(
        val timestampSeconds: Long,
        val formatVersion: Byte,
        val packBlobId: String,
        val packOffset: UInt,
        val packedLength: UInt,
        val deleted: Boolean
    )

    /**
     * Parses the 8-byte header from a V1 index.
     *
     * @param data The raw header bytes (at least 8 bytes)
     * @return The parsed header information
     * @throws IllegalArgumentException if header is invalid
     */
    fun parseHeader(data: ByteArray): HeaderInfo {
        require(data.size >= HEADER_SIZE) {
            "Header too short: ${data.size} bytes, expected at least $HEADER_SIZE"
        }

        val version = data[0].toInt() and 0xFF
        require(version == VERSION) {
            "Invalid version: $version, expected $VERSION"
        }

        val keySize = data[1].toInt() and 0xFF
        require(keySize > 1) {
            "Invalid key size: $keySize, must be > 1"
        }

        val entrySize = ByteBuffer.wrap(data, 2, 2)
            .order(ByteOrder.BIG_ENDIAN)
            .short.toInt() and 0xFFFF

        val entryCount = ByteBuffer.wrap(data, 4, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        require(entryCount >= 0) {
            "Invalid entry count: $entryCount"
        }

        return HeaderInfo(version, keySize, entrySize, entryCount)
    }

    /**
     * Parses a 20-byte entry from a V1 index.
     *
     * @param entry The raw entry bytes (20 bytes)
     * @param extraData The extra data section containing pack blob IDs
     * @param extraDataOffset The offset where extra data starts in the full index
     * @return The parsed entry data
     */
    fun parseEntry(
        entry: ByteArray,
        extraData: ByteArray,
        extraDataOffset: Int
    ): ParsedEntry {
        require(entry.size == ENTRY_SIZE) {
            "Invalid entry size: ${entry.size}, expected $ENTRY_SIZE"
        }

        // Bytes 0-7: timestamp/flags combined as big-endian uint64
        val timestampAndFlags = ByteBuffer.wrap(entry, 0, 8)
            .order(ByteOrder.BIG_ENDIAN)
            .long

        // Timestamp is in upper 48 bits (shifted left by 16)
        val timestampSeconds = (timestampAndFlags ushr 16)
        val formatVersion = ((timestampAndFlags ushr 8) and 0xFF).toByte()
        val packBlobIdLength = (timestampAndFlags and 0xFF).toInt()

        // Bytes 8-11: pack blob ID offset (relative to extra data start)
        val packBlobIdOffsetInIndex = ByteBuffer.wrap(entry, 8, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int and 0x7FFFFFFF

        // Calculate offset into extraData array
        val packBlobIdOffsetInExtraData = packBlobIdOffsetInIndex - extraDataOffset

        // Extract pack blob ID from extra data
        val packBlobId = if (packBlobIdLength > 0 && packBlobIdOffsetInExtraData >= 0 &&
            packBlobIdOffsetInExtraData + packBlobIdLength <= extraData.size
        ) {
            String(extraData, packBlobIdOffsetInExtraData, packBlobIdLength, Charsets.UTF_8)
        } else {
            ""
        }

        // Bytes 12-15: deleted flag + pack offset
        val deletedAndOffset = ByteBuffer.wrap(entry, 12, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        val deleted = (deletedAndOffset and DELETED_MARKER) != 0
        val packOffset = (deletedAndOffset and PACK_OFFSET_MASK).toUInt()

        // Bytes 16-19: packed length
        val packedLength = ByteBuffer.wrap(entry, 16, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int.toUInt()

        return ParsedEntry(
            timestampSeconds = timestampSeconds,
            formatVersion = formatVersion,
            packBlobId = packBlobId,
            packOffset = packOffset,
            packedLength = packedLength,
            deleted = deleted
        )
    }

    /**
     * Builds a V1 index from a list of content infos.
     *
     * @param entries The content info entries to include
     * @return The serialized index data
     * @throws IllegalArgumentException if any entry has compression or encryption key ID
     */
    fun build(entries: List<ContentInfo>): ByteArray {
        // Validate entries
        for (entry in entries) {
            require(entry.compressionHeaderId == 0) {
                "Compression not supported in index V1: content ${entry.contentId}"
            }
            require(entry.encryptionKeyId.toInt() == 0) {
                "Encryption key ID not supported in index V1: content ${entry.contentId}"
            }
        }

        if (entries.isEmpty()) {
            // Return empty index with special key size marker
            return buildEmptyIndex()
        }

        // Sort entries by content ID
        val sortedEntries = entries.sortedBy { it.contentId.toString() }

        // Determine key size from first entry
        val keySize = contentIdToBytes(sortedEntries[0].contentId).size

        // Validate all entries have same key size
        for (entry in sortedEntries) {
            val entryKeySize = contentIdToBytes(entry.contentId).size
            require(entryKeySize == keySize) {
                "Inconsistent key size: $entryKeySize vs $keySize for ${entry.contentId}"
            }
        }

        // Build extra data (deduplicated pack blob IDs)
        val packBlobIdOffsets = mutableMapOf<String, Int>()
        val extraData = ByteArrayOutputStream()

        for (entry in sortedEntries) {
            val packBlobId = entry.packBlobId.value
            if (packBlobId !in packBlobIdOffsets) {
                packBlobIdOffsets[packBlobId] = extraData.size()
                extraData.write(packBlobId.toByteArray(Charsets.UTF_8))
            }
        }

        // Calculate extra data offset in the final index
        val extraDataOffset = HEADER_SIZE + sortedEntries.size * (keySize + ENTRY_SIZE)

        // Build output
        val output = ByteArrayOutputStream()

        // Write header
        output.write(VERSION)
        output.write(keySize)
        output.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
            .putShort(ENTRY_SIZE.toShort()).array())
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(sortedEntries.size).array())

        // Write entries
        for (entry in sortedEntries) {
            // Write key
            val keyBytes = contentIdToBytes(entry.contentId)
            output.write(keyBytes)

            // Write entry
            val entryBytes = buildEntry(entry, packBlobIdOffsets, extraDataOffset)
            output.write(entryBytes)
        }

        // Write extra data
        output.write(extraData.toByteArray())

        return output.toByteArray()
    }

    /**
     * Opens a V1 index for reading.
     *
     * @param data The raw index data
     * @param perContentOverhead Encryption overhead to subtract when computing original length
     * @return The opened index
     */
    fun open(data: ByteArray, perContentOverhead: UInt = 0u): PackIndex {
        return PackIndexV1Impl(data, perContentOverhead)
    }

    /**
     * Converts a content ID to its byte representation for V1 index format.
     *
     * Go Kopia V1 index format (keySize 17 or 33):
     * - First byte: marker (0x00 if no prefix, otherwise prefix char 'g'-'z')
     * - Remaining bytes: hash bytes
     *
     * This always includes the marker byte to match the index storage format.
     */
    internal fun contentIdToBytes(contentId: ContentId): ByteArray {
        val hashBytes = contentId.hashBytes
        // Always include marker byte: 0x00 for no prefix, or prefix char
        return ByteArray(1 + hashBytes.size).apply {
            this[0] = if (contentId.prefix != null) {
                contentId.prefix.code.toByte()
            } else {
                0x00 // Marker byte for no prefix
            }
            hashBytes.copyInto(this, 1)
        }
    }

    /**
     * Converts bytes back to a content ID.
     *
     * Content ID encoding in Go Kopia V1 index (keySize = 17 or 33):
     * - First byte is ALWAYS a marker:
     *   - 0x00 = no prefix (remaining bytes are hash)
     *   - 'g'-'z' = prefix character (remaining bytes are hash)
     *
     * For keySize = 16 or 32 (legacy/unusual):
     * - All bytes are hash, no prefix
     *
     * @param bytes The key bytes from the index
     * @param hasMarkerByte Whether the first byte is a prefix/marker byte (keySize=17 or 33)
     */
    internal fun bytesToContentId(bytes: ByteArray, hasMarkerByte: Boolean = false): ContentId {
        if (bytes.isEmpty()) {
            return ContentId.Empty
        }

        return if (hasMarkerByte && bytes.size > 1) {
            // First byte is a marker: either 0x00 (no prefix) or a prefix char
            val markerByte = bytes[0].toInt() and 0xFF
            if (markerByte == 0) {
                // 0x00 means no prefix - remaining bytes are hash
                ContentId.fromHash(null, bytes.copyOfRange(1, bytes.size))
            } else {
                // Non-zero means prefix character
                val prefix = markerByte.toChar()
                ContentId.fromHash(prefix, bytes.copyOfRange(1, bytes.size))
            }
        } else {
            // No marker byte - all bytes are hash
            ContentId.fromHash(null, bytes)
        }
    }

    /**
     * Determines if the first byte of key bytes is a marker/prefix byte.
     *
     * In Go Kopia V1 index format:
     * - KeySize 17 or 33 = 1 marker byte + 16 or 32 hash bytes
     * - KeySize 16 or 32 = pure hash bytes (no marker)
     *
     * The marker byte can be:
     * - 0x00 = no prefix (remaining bytes are hash)
     * - 'g'-'z' = prefix character
     *
     * @param keyBytes The key bytes (not used, but kept for API compatibility)
     * @param keySize The key size from the index header
     * @return true if the first byte should be treated as a marker byte
     */
    internal fun hasPrefixFromKeySize(keyBytes: ByteArray, keySize: Int): Boolean {
        // KeySize 17 or 33 means there's always a marker byte at the front
        return keySize == 17 || keySize == 33
    }

    private fun buildEmptyIndex(): ByteArray {
        return ByteArray(HEADER_SIZE).apply {
            this[0] = VERSION.toByte()
            this[1] = 0xFF.toByte() // Unknown key size marker
            // Entry size and count remain 0
        }
    }

    private fun buildEntry(
        entry: ContentInfo,
        packBlobIdOffsets: Map<String, Int>,
        extraDataOffset: Int
    ): ByteArray {
        val buffer = ByteArray(ENTRY_SIZE)

        // Build timestamp + format version + pack blob ID length as uint64
        val packBlobId = entry.packBlobId.value
        val packBlobIdLength = packBlobId.length

        var timestampAndFlags = entry.timestampSeconds shl 16
        timestampAndFlags = timestampAndFlags or ((entry.formatVersion.toLong() and 0xFF) shl 8)
        timestampAndFlags = timestampAndFlags or (packBlobIdLength.toLong() and 0xFF)

        ByteBuffer.wrap(buffer, 0, 8)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(timestampAndFlags)

        // Pack blob ID offset (in full index, not extra data)
        val packBlobIdOffset = extraDataOffset + (packBlobIdOffsets[packBlobId] ?: 0)
        ByteBuffer.wrap(buffer, 8, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(packBlobIdOffset)

        // Deleted flag + pack offset
        var deletedAndOffset = entry.packOffset.toInt()
        if (entry.deleted) {
            deletedAndOffset = deletedAndOffset or DELETED_MARKER
        }
        ByteBuffer.wrap(buffer, 12, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(deletedAndOffset)

        // Packed length
        ByteBuffer.wrap(buffer, 16, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(entry.packedLength.toInt())

        return buffer
    }
}

/**
 * Implementation of PackIndex for V1 format.
 */
private class PackIndexV1Impl(
    private val data: ByteArray,
    private val perContentOverhead: UInt
) : PackIndex {

    private val header: PackIndexV1.HeaderInfo
    private val extraDataOffset: Int
    private val stride: Int

    init {
        header = if (data.size >= PackIndexV1.HEADER_SIZE) {
            PackIndexV1.parseHeader(data)
        } else {
            PackIndexV1.HeaderInfo(PackIndexV1.VERSION, 255, 0, 0)
        }
        stride = header.keySize + header.entrySize

        // Validate that claimed entry count fits within actual data to prevent
        // OOM from corrupted headers specifying billions of entries
        if (stride > 0 && header.entryCount > 0) {
            val maxPossibleEntries = (data.size - PackIndexV1.HEADER_SIZE) / stride
            require(header.entryCount <= maxPossibleEntries) {
                "Entry count ${header.entryCount} exceeds data capacity (max $maxPossibleEntries for ${data.size} bytes)"
            }
        }

        extraDataOffset = PackIndexV1.HEADER_SIZE + header.entryCount * stride
    }

    override fun approximateCount(): Int = header.entryCount

    override fun getInfo(contentId: ContentId): ContentInfo? {
        if (header.keySize == 255 || header.entryCount == 0) {
            // Empty index
            return null
        }

        val keyBytes = PackIndexV1.contentIdToBytes(contentId)
        if (keyBytes.size != header.keySize) {
            return null
        }

        // Binary search for the entry
        val position = findEntryPosition(keyBytes)
        if (position >= header.entryCount) {
            return null
        }

        // Check if we found exact match
        val entryOffset = PackIndexV1.HEADER_SIZE + stride * position
        val keyAtPosition = data.copyOfRange(entryOffset, entryOffset + header.keySize)

        if (!keyAtPosition.contentEquals(keyBytes)) {
            return null
        }

        // Parse the entry
        val entryData = data.copyOfRange(
            entryOffset + header.keySize,
            entryOffset + header.keySize + PackIndexV1.ENTRY_SIZE
        )

        val extraData = if (extraDataOffset < data.size) {
            data.copyOfRange(extraDataOffset, data.size)
        } else {
            ByteArray(0)
        }

        val parsed = PackIndexV1.parseEntry(entryData, extraData, extraDataOffset)

        return ContentInfo(
            contentId = contentId,
            packBlobId = BlobId(parsed.packBlobId),
            timestampSeconds = parsed.timestampSeconds,
            originalLength = parsed.packedLength - perContentOverhead,
            packedLength = parsed.packedLength,
            packOffset = parsed.packOffset,
            compressionHeaderId = 0, // V1 doesn't support compression
            deleted = parsed.deleted,
            formatVersion = parsed.formatVersion,
            encryptionKeyId = 0 // V1 doesn't support encryption key ID
        )
    }

    override fun iterate(startId: ContentId?, endId: ContentId?): Sequence<ContentInfo> = sequence {
        if (header.keySize == 255 || header.entryCount == 0) {
            return@sequence
        }

        val startPosition = if (startId != null) {
            val keyBytes = PackIndexV1.contentIdToBytes(startId)
            if (keyBytes.size == header.keySize) {
                findEntryPosition(keyBytes)
            } else {
                0
            }
        } else {
            0
        }

        val extraData = if (extraDataOffset < data.size) {
            data.copyOfRange(extraDataOffset, data.size)
        } else {
            ByteArray(0)
        }

        for (i in startPosition until header.entryCount) {
            val entryOffset = PackIndexV1.HEADER_SIZE + stride * i
            val keyBytes = data.copyOfRange(entryOffset, entryOffset + header.keySize)
            val hasPrefix = PackIndexV1.hasPrefixFromKeySize(keyBytes, header.keySize)
            val contentId = PackIndexV1.bytesToContentId(keyBytes, hasPrefix)

            // Check end boundary
            if (endId != null && contentId.toString() >= endId.toString()) {
                break
            }

            val entryData = data.copyOfRange(
                entryOffset + header.keySize,
                entryOffset + header.keySize + PackIndexV1.ENTRY_SIZE
            )

            val parsed = PackIndexV1.parseEntry(entryData, extraData, extraDataOffset)

            yield(
                ContentInfo(
                    contentId = contentId,
                    packBlobId = BlobId(parsed.packBlobId),
                    timestampSeconds = parsed.timestampSeconds,
                    originalLength = parsed.packedLength - perContentOverhead,
                    packedLength = parsed.packedLength,
                    packOffset = parsed.packOffset,
                    compressionHeaderId = 0,
                    deleted = parsed.deleted,
                    formatVersion = parsed.formatVersion,
                    encryptionKeyId = 0
                )
            )
        }
    }

    override fun close() {
        // No resources to release
    }

    /**
     * Binary search for the position of a key.
     */
    private fun findEntryPosition(keyBytes: ByteArray): Int {
        var low = 0
        var high = header.entryCount

        while (low < high) {
            val mid = (low + high) / 2
            val midOffset = PackIndexV1.HEADER_SIZE + stride * mid
            val midKey = data.copyOfRange(midOffset, midOffset + header.keySize)

            if (compareBytes(midKey, keyBytes) < 0) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        return low
    }

    /**
     * Compares two byte arrays lexicographically.
     */
    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
