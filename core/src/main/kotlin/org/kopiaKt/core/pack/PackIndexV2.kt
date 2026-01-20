package org.kopiaKt.core.pack

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pack Index Version 2 format.
 *
 * This format supports content-level compression metadata and encryption key IDs.
 * It stores original and packed lengths directly, and uses relative timestamps.
 *
 * Binary format:
 * - Header (17 bytes):
 *   - Byte 0: Version (0x02)
 *   - Byte 1: Key size (content ID byte length)
 *   - Bytes 2-3: Entry size (big-endian uint16, 16-19)
 *   - Bytes 4-7: Entry count (big-endian uint32)
 *   - Bytes 8-11: Pack count (big-endian uint32)
 *   - Byte 12: Format count
 *   - Bytes 13-16: Base timestamp (big-endian uint32)
 * - Entries (sorted by key)
 * - Pack infos (5 bytes each)
 * - Format infos (6 bytes each)
 * - Extra data: Pack blob ID strings
 *
 * Entry format (16-19 bytes):
 * - Bytes 0-3: Timestamp offset (relative to base, big-endian)
 * - Bytes 4-7: Deleted flag (bit 31) + Pack offset (bits 0-30)
 * - Bytes 8-10: Original length bits 0-23 (24-bit big-endian)
 * - Bytes 11-13: Packed length bits 0-23 (24-bit big-endian)
 * - Bytes 14-15: Pack blob ID index (16-bit big-endian)
 * Optional bytes:
 * - Byte 16: Format ID index (if > 1 unique format)
 * - Byte 17: Pack blob ID bits 16-23 (if > 65536 packs)
 * - Byte 18: High-order length bits (if any length >= 16 MiB)
 *            Upper 4 bits: original length bits 24-27
 *            Lower 4 bits: packed length bits 24-27
 */
object PackIndexV2 {
    const val VERSION = 2
    const val HEADER_SIZE = 17
    const val PACK_INFO_SIZE = 5
    const val FORMAT_INFO_SIZE = 6
    const val MIN_ENTRY_SIZE = 16
    const val MAX_ENTRY_SIZE = 19
    const val DELETED_MARKER = 0x80000000.toInt()
    const val PACK_OFFSET_MASK = 0x7FFFFFFF
    const val MAX_FORMAT_COUNT = 255
    const val MAX_SHORT_PACK_ID_COUNT = 1 shl 16  // 65536
    const val MAX_PACK_ID_COUNT = 1 shl 24  // 16M
    const val MAX_SHORT_CONTENT_LENGTH = 1 shl 24  // 16 MiB
    const val MAX_CONTENT_LENGTH = 1 shl 28  // 256 MiB

    // Entry offsets
    const val ENTRY_OFFSET_TIMESTAMP = 0
    const val ENTRY_OFFSET_PACK_OFFSET = 4
    const val ENTRY_OFFSET_ORIGINAL_LENGTH = 8
    const val ENTRY_OFFSET_PACKED_LENGTH = 11
    const val ENTRY_OFFSET_PACK_BLOB_ID = 14
    const val ENTRY_OFFSET_FORMAT_ID = 16
    const val ENTRY_OFFSET_EXTENDED_PACK_ID = 17
    const val ENTRY_OFFSET_HIGH_LENGTH_BITS = 18

    /**
     * Header information parsed from a V2 index.
     */
    data class HeaderInfo(
        val version: Int,
        val keySize: Int,
        val entrySize: Int,
        val entryCount: Int,
        val packCount: UInt,
        val formatCount: Int,
        val baseTimestamp: UInt
    )

    /**
     * Format information for a unique combination of compression/format/encryption.
     */
    data class FormatInfo(
        val compressionHeaderId: Int,
        val formatVersion: Byte,
        val encryptionKeyId: Byte
    )

    /**
     * Parses the 17-byte header from a V2 index.
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

        require(entrySize in MIN_ENTRY_SIZE..MAX_ENTRY_SIZE) {
            "Invalid entry size: $entrySize, must be between $MIN_ENTRY_SIZE and $MAX_ENTRY_SIZE"
        }

        val entryCount = ByteBuffer.wrap(data, 4, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        val packCount = ByteBuffer.wrap(data, 8, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int.toUInt()

        val formatCount = data[12].toInt() and 0xFF

        val baseTimestamp = ByteBuffer.wrap(data, 13, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int.toUInt()

        return HeaderInfo(
            version = version,
            keySize = keySize,
            entrySize = entrySize,
            entryCount = entryCount,
            packCount = packCount,
            formatCount = formatCount,
            baseTimestamp = baseTimestamp
        )
    }

    /**
     * Builds a V2 index from a list of content infos.
     */
    fun build(entries: List<ContentInfo>): ByteArray {
        if (entries.isEmpty()) {
            return buildEmptyIndex()
        }

        // Sort entries by content ID
        val sortedEntries = entries.sortedBy { it.contentId.toString() }

        // Determine key size from first entry
        val keySize = PackIndexV1.contentIdToBytes(sortedEntries[0].contentId).size

        // Validate all entries have same key size
        for (entry in sortedEntries) {
            val entryKeySize = PackIndexV1.contentIdToBytes(entry.contentId).size
            require(entryKeySize == keySize) {
                "Inconsistent key size: $entryKeySize vs $keySize for ${entry.contentId}"
            }
        }

        // Build unique format info map
        val formatInfoToIndex = buildUniqueFormatToIndexMap(sortedEntries)
        require(formatInfoToIndex.size <= MAX_FORMAT_COUNT) {
            "Too many unique formats: ${formatInfoToIndex.size}, max is $MAX_FORMAT_COUNT"
        }

        // Build unique pack ID map
        val packIdToIndex = buildPackIdToIndexMap(sortedEntries)
        require(packIdToIndex.size <= MAX_PACK_ID_COUNT) {
            "Too many unique pack IDs: ${packIdToIndex.size}, max is $MAX_PACK_ID_COUNT"
        }

        // Compute max lengths and determine entry size
        val (maxPackedLength, maxOriginalLength, _) = maxContentLengths(sortedEntries)
        require(maxPackedLength < MAX_CONTENT_LENGTH.toUInt() && maxOriginalLength < MAX_CONTENT_LENGTH.toUInt()) {
            "Content length too large: packed=$maxPackedLength, original=$maxOriginalLength, max=$MAX_CONTENT_LENGTH"
        }

        var entrySize = MIN_ENTRY_SIZE

        // Need format ID byte if more than one format
        if (formatInfoToIndex.size > 1) {
            entrySize = maxOf(entrySize, ENTRY_OFFSET_FORMAT_ID + 1)
        }

        // Need extended pack ID byte if more than 65536 packs
        if (packIdToIndex.size > MAX_SHORT_PACK_ID_COUNT) {
            entrySize = maxOf(entrySize, ENTRY_OFFSET_EXTENDED_PACK_ID + 1)
        }

        // Need high length bits if any length >= 16 MiB
        if (maxPackedLength >= MAX_SHORT_CONTENT_LENGTH.toUInt() || maxOriginalLength >= MAX_SHORT_CONTENT_LENGTH.toUInt()) {
            entrySize = maxOf(entrySize, ENTRY_OFFSET_HIGH_LENGTH_BITS + 1)
        }

        // Compute base timestamp (minimum timestamp in entries)
        val baseTimestamp = sortedEntries.minOf { it.timestampSeconds }

        // Build extra data (pack blob IDs)
        val packBlobIdOffsets = mutableMapOf<String, Int>()
        val extraData = ByteArrayOutputStream()

        for (entry in sortedEntries) {
            val packBlobId = entry.packBlobId.value
            if (packBlobId !in packBlobIdOffsets) {
                packBlobIdOffsets[packBlobId] = extraData.size()
                extraData.write(packBlobId.toByteArray(Charsets.UTF_8))
            }
        }

        // Calculate offsets
        val entriesOffset = HEADER_SIZE
        val packsOffset = entriesOffset + sortedEntries.size * (keySize + entrySize)
        val formatsOffset = packsOffset + packIdToIndex.size * PACK_INFO_SIZE
        val extraDataOffset = formatsOffset + formatInfoToIndex.size * FORMAT_INFO_SIZE

        // Build output
        val output = ByteArrayOutputStream()

        // Write header
        val header = ByteArray(HEADER_SIZE)
        header[0] = VERSION.toByte()
        header[1] = keySize.toByte()
        ByteBuffer.wrap(header, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(entrySize.toShort())
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(sortedEntries.size)
        ByteBuffer.wrap(header, 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(packIdToIndex.size)
        header[12] = formatInfoToIndex.size.toByte()
        ByteBuffer.wrap(header, 13, 4).order(ByteOrder.BIG_ENDIAN).putInt(baseTimestamp.toInt())
        output.write(header)

        // Write entries
        for (entry in sortedEntries) {
            val keyBytes = PackIndexV1.contentIdToBytes(entry.contentId)
            output.write(keyBytes)

            val entryBytes = buildEntry(
                entry = entry,
                baseTimestamp = baseTimestamp,
                formatInfoToIndex = formatInfoToIndex,
                packIdToIndex = packIdToIndex,
                entrySize = entrySize
            )
            output.write(entryBytes)
        }

        // Write pack infos (in index order)
        val reversePackIdIndex = Array<String>(packIdToIndex.size) { "" }
        for ((id, idx) in packIdToIndex) {
            reversePackIdIndex[idx] = id
        }
        for (packId in reversePackIdIndex) {
            val packInfo = ByteArray(PACK_INFO_SIZE)
            packInfo[0] = packId.length.toByte()
            ByteBuffer.wrap(packInfo, 1, 4).order(ByteOrder.BIG_ENDIAN)
                .putInt(extraDataOffset + packBlobIdOffsets[packId]!!)
            output.write(packInfo)
        }

        // Write format infos (in index order)
        val reverseFormatInfoIndex = Array<FormatInfo?>(formatInfoToIndex.size) { null }
        for ((info, idx) in formatInfoToIndex) {
            reverseFormatInfoIndex[idx] = info
        }
        for (formatInfo in reverseFormatInfoIndex) {
            val fi = formatInfo!!
            val formatInfoBytes = ByteArray(FORMAT_INFO_SIZE)
            ByteBuffer.wrap(formatInfoBytes, 0, 4).order(ByteOrder.BIG_ENDIAN)
                .putInt(fi.compressionHeaderId)
            formatInfoBytes[4] = fi.formatVersion
            formatInfoBytes[5] = fi.encryptionKeyId
            output.write(formatInfoBytes)
        }

        // Write extra data
        output.write(extraData.toByteArray())

        return output.toByteArray()
    }

    /**
     * Opens a V2 index for reading.
     */
    fun open(data: ByteArray): PackIndex {
        return PackIndexV2Impl(data)
    }

    private fun buildEmptyIndex(): ByteArray {
        return ByteArray(HEADER_SIZE).apply {
            this[0] = VERSION.toByte()
            this[1] = 0xFF.toByte() // Unknown key size marker
            // Set entry size to minimum (even though there are no entries)
            ByteBuffer.wrap(this, 2, 2).order(ByteOrder.BIG_ENDIAN)
                .putShort(MIN_ENTRY_SIZE.toShort())
            // entryCount, packCount, formatCount, baseTimestamp all remain 0
        }
    }

    private fun buildUniqueFormatToIndexMap(entries: List<ContentInfo>): Map<FormatInfo, Int> {
        val result = mutableMapOf<FormatInfo, Int>()
        for (entry in entries) {
            val key = FormatInfo(
                compressionHeaderId = entry.compressionHeaderId,
                formatVersion = entry.formatVersion,
                encryptionKeyId = entry.encryptionKeyId
            )
            if (key !in result) {
                result[key] = result.size
            }
        }
        return result
    }

    private fun buildPackIdToIndexMap(entries: List<ContentInfo>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (entry in entries) {
            val id = entry.packBlobId.value
            if (id !in result) {
                result[id] = result.size
            }
        }
        return result
    }

    private fun maxContentLengths(entries: List<ContentInfo>): Triple<UInt, UInt, UInt> {
        var maxPacked = 0u
        var maxOriginal = 0u
        var maxOffset = 0u
        for (entry in entries) {
            if (entry.packedLength > maxPacked) maxPacked = entry.packedLength
            if (entry.originalLength > maxOriginal) maxOriginal = entry.originalLength
            if (entry.packOffset > maxOffset) maxOffset = entry.packOffset
        }
        return Triple(maxPacked, maxOriginal, maxOffset)
    }

    private fun buildEntry(
        entry: ContentInfo,
        baseTimestamp: Long,
        formatInfoToIndex: Map<FormatInfo, Int>,
        packIdToIndex: Map<String, Int>,
        entrySize: Int
    ): ByteArray {
        val buffer = ByteArray(entrySize)

        // Timestamp offset (relative to base)
        val timestampOffset = (entry.timestampSeconds - baseTimestamp).toInt()
        ByteBuffer.wrap(buffer, ENTRY_OFFSET_TIMESTAMP, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(timestampOffset)

        // Pack offset with deleted flag
        var packOffsetAndFlags = entry.packOffset.toInt()
        if (entry.deleted) {
            packOffsetAndFlags = packOffsetAndFlags or DELETED_MARKER
        }
        ByteBuffer.wrap(buffer, ENTRY_OFFSET_PACK_OFFSET, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(packOffsetAndFlags)

        // Original length (24-bit)
        encodeBigEndianUint24(buffer, ENTRY_OFFSET_ORIGINAL_LENGTH, entry.originalLength)

        // Packed length (24-bit)
        encodeBigEndianUint24(buffer, ENTRY_OFFSET_PACKED_LENGTH, entry.packedLength)

        // Pack blob ID index (16-bit)
        val packIdIndex = packIdToIndex[entry.packBlobId.value] ?: 0
        ByteBuffer.wrap(buffer, ENTRY_OFFSET_PACK_BLOB_ID, 2)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(packIdIndex.toShort())

        // Optional bytes
        if (entrySize > ENTRY_OFFSET_FORMAT_ID) {
            val formatInfo = FormatInfo(
                compressionHeaderId = entry.compressionHeaderId,
                formatVersion = entry.formatVersion,
                encryptionKeyId = entry.encryptionKeyId
            )
            buffer[ENTRY_OFFSET_FORMAT_ID] = (formatInfoToIndex[formatInfo] ?: 0).toByte()
        }

        if (entrySize > ENTRY_OFFSET_EXTENDED_PACK_ID) {
            buffer[ENTRY_OFFSET_EXTENDED_PACK_ID] = ((packIdIndex shr 16) and 0xFF).toByte()
        }

        if (entrySize > ENTRY_OFFSET_HIGH_LENGTH_BITS) {
            val highOriginal = ((entry.originalLength.toInt() shr 24) and 0x0F) shl 4
            val highPacked = (entry.packedLength.toInt() shr 24) and 0x0F
            buffer[ENTRY_OFFSET_HIGH_LENGTH_BITS] = (highOriginal or highPacked).toByte()
        }

        return buffer
    }

    private fun encodeBigEndianUint24(buffer: ByteArray, offset: Int, value: UInt) {
        buffer[offset] = ((value.toInt() shr 16) and 0xFF).toByte()
        buffer[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        buffer[offset + 2] = (value.toInt() and 0xFF).toByte()
    }
}

/**
 * Implementation of PackIndex for V2 format.
 */
private class PackIndexV2Impl(
    private val data: ByteArray
) : PackIndex {

    private val header: PackIndexV2.HeaderInfo
    private val formats: List<PackIndexV2.FormatInfo>
    private val packBlobIds: List<String>
    private val entriesOffset: Int
    private val stride: Int

    init {
        header = if (data.size >= PackIndexV2.HEADER_SIZE) {
            PackIndexV2.parseHeader(data)
        } else {
            PackIndexV2.HeaderInfo(PackIndexV2.VERSION, 255, 16, 0, 0u, 0, 0u)
        }
        stride = header.keySize + header.entrySize

        // Calculate offsets
        entriesOffset = PackIndexV2.HEADER_SIZE
        val packsOffset = entriesOffset + header.entryCount * stride
        val formatsOffset = packsOffset + header.packCount.toInt() * PackIndexV2.PACK_INFO_SIZE

        // Parse format infos
        formats = if (header.formatCount > 0) {
            (0 until header.formatCount).map { i ->
                val offset = formatsOffset + i * PackIndexV2.FORMAT_INFO_SIZE
                val compressionId = ByteBuffer.wrap(data, offset, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int
                val formatVersion = data[offset + 4]
                val encryptionKeyId = data[offset + 5]
                PackIndexV2.FormatInfo(compressionId, formatVersion, encryptionKeyId)
            }
        } else {
            emptyList()
        }

        // Parse pack blob IDs
        packBlobIds = if (header.packCount > 0u) {
            (0 until header.packCount.toInt()).map { i ->
                val packInfoOffset = packsOffset + i * PackIndexV2.PACK_INFO_SIZE
                val nameLength = data[packInfoOffset].toInt() and 0xFF
                val nameOffset = ByteBuffer.wrap(data, packInfoOffset + 1, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int
                String(data, nameOffset, nameLength, Charsets.UTF_8)
            }
        } else {
            emptyList()
        }
    }

    override fun approximateCount(): Int = header.entryCount

    override fun getInfo(contentId: ContentId): ContentInfo? {
        if (header.keySize == 255 || header.entryCount == 0) {
            return null
        }

        val keyBytes = PackIndexV1.contentIdToBytes(contentId)
        if (keyBytes.size != header.keySize) {
            return null
        }

        val position = findEntryPosition(keyBytes)
        if (position >= header.entryCount) {
            return null
        }

        val entryOffset = entriesOffset + stride * position
        val keyAtPosition = data.copyOfRange(entryOffset, entryOffset + header.keySize)

        if (!keyAtPosition.contentEquals(keyBytes)) {
            return null
        }

        return parseEntry(contentId, entryOffset + header.keySize)
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

        for (i in startPosition until header.entryCount) {
            val entryOffset = entriesOffset + stride * i
            val keyBytes = data.copyOfRange(entryOffset, entryOffset + header.keySize)
            val contentId = PackIndexV1.bytesToContentId(keyBytes)

            if (endId != null && contentId.toString() >= endId.toString()) {
                break
            }

            yield(parseEntry(contentId, entryOffset + header.keySize)!!)
        }
    }

    override fun close() {
        // No resources to release
    }

    private fun parseEntry(contentId: ContentId, dataOffset: Int): ContentInfo? {
        val entryData = data.copyOfRange(dataOffset, dataOffset + header.entrySize)

        // Timestamp offset
        val timestampOffset = ByteBuffer.wrap(entryData, PackIndexV2.ENTRY_OFFSET_TIMESTAMP, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        val timestampSeconds = header.baseTimestamp.toLong() + timestampOffset

        // Pack offset and deleted flag
        val packOffsetAndFlags = ByteBuffer.wrap(entryData, PackIndexV2.ENTRY_OFFSET_PACK_OFFSET, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        val deleted = (packOffsetAndFlags and PackIndexV2.DELETED_MARKER) != 0
        val packOffset = (packOffsetAndFlags and PackIndexV2.PACK_OFFSET_MASK).toUInt()

        // Original length (24-bit)
        var originalLength = decodeBigEndianUint24(entryData, PackIndexV2.ENTRY_OFFSET_ORIGINAL_LENGTH)

        // Packed length (24-bit)
        var packedLength = decodeBigEndianUint24(entryData, PackIndexV2.ENTRY_OFFSET_PACKED_LENGTH)

        // High bits if present
        if (header.entrySize > PackIndexV2.ENTRY_OFFSET_HIGH_LENGTH_BITS) {
            val highBits = entryData[PackIndexV2.ENTRY_OFFSET_HIGH_LENGTH_BITS].toInt() and 0xFF
            val highOriginal = (highBits shr 4) and 0x0F
            val highPacked = highBits and 0x0F
            originalLength = originalLength or (highOriginal.toUInt() shl 24)
            packedLength = packedLength or (highPacked.toUInt() shl 24)
        }

        // Pack blob ID index
        var packIdIndex = ByteBuffer.wrap(entryData, PackIndexV2.ENTRY_OFFSET_PACK_BLOB_ID, 2)
            .order(ByteOrder.BIG_ENDIAN)
            .short.toInt() and 0xFFFF

        // Extended pack ID bits if present
        if (header.entrySize > PackIndexV2.ENTRY_OFFSET_EXTENDED_PACK_ID) {
            packIdIndex = packIdIndex or ((entryData[PackIndexV2.ENTRY_OFFSET_EXTENDED_PACK_ID].toInt() and 0xFF) shl 16)
        }

        val packBlobId = if (packIdIndex < packBlobIds.size) {
            packBlobIds[packIdIndex]
        } else {
            "---invalid---"
        }

        // Format ID
        val formatId = if (header.entrySize > PackIndexV2.ENTRY_OFFSET_FORMAT_ID) {
            entryData[PackIndexV2.ENTRY_OFFSET_FORMAT_ID].toInt() and 0xFF
        } else {
            0
        }

        val formatInfo = if (formatId < formats.size) {
            formats[formatId]
        } else {
            PackIndexV2.FormatInfo(0, 0, 0)
        }

        return ContentInfo(
            contentId = contentId,
            packBlobId = BlobId(packBlobId),
            timestampSeconds = timestampSeconds,
            originalLength = originalLength,
            packedLength = packedLength,
            packOffset = packOffset,
            compressionHeaderId = formatInfo.compressionHeaderId,
            deleted = deleted,
            formatVersion = formatInfo.formatVersion,
            encryptionKeyId = formatInfo.encryptionKeyId
        )
    }

    private fun decodeBigEndianUint24(data: ByteArray, offset: Int): UInt {
        return (((data[offset].toInt() and 0xFF) shl 16) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                (data[offset + 2].toInt() and 0xFF)).toUInt()
    }

    private fun findEntryPosition(keyBytes: ByteArray): Int {
        var low = 0
        var high = header.entryCount

        while (low < high) {
            val mid = (low + high) / 2
            val midOffset = entriesOffset + stride * mid
            val midKey = data.copyOfRange(midOffset, midOffset + header.keySize)

            if (compareBytes(midKey, keyBytes) < 0) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        return low
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
