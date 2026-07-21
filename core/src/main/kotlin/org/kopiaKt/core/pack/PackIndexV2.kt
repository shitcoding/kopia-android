package org.kopiaKt.core.pack

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pack Index Version 2 format.
 */
object PackIndexV2 {
    const val VERSION = 2
    const val HEADER_SIZE = 17
    const val PACK_INFO_SIZE = 5
    const val FORMAT_INFO_SIZE = 6
    const val PACK_OFFSET_MASK = 0x7FFFFFFF

    const val ENTRY_OFFSET_TIMESTAMP = 0
    const val ENTRY_OFFSET_PACK_OFFSET_AND_FLAGS = 4
    const val ENTRY_OFFSET_ORIGINAL_LENGTH = 8
    const val ENTRY_OFFSET_PACKED_LENGTH = 11
    const val ENTRY_OFFSET_PACK_BLOB_ID = 14
    const val ENTRY_MIN_LENGTH = 16
    const val ENTRY_OFFSET_FORMAT_ID = 16
    const val ENTRY_OFFSET_EXTENDED_PACK_ID = 17
    const val ENTRY_OFFSET_HIGH_LENGTH_BITS = 18

    const val HIGH_LENGTH_SHIFT = 24
    const val HIGH_LENGTH_ORIGINAL_SHIFT = 4
    const val HIGH_LENGTH_PACKED_MASK = 0x0F
    const val EXTENDED_PACK_ID_SHIFT = 16

    fun open(data: ByteArray, perContentOverhead: UInt = 0u): PackIndex = PackIndexV2Impl(data)

    fun build(entries: List<ContentInfo>): ByteArray {
        val entrySize = ENTRY_OFFSET_HIGH_LENGTH_BITS + 1 // 19

        if (entries.isEmpty()) {
            // Return minimal valid header with zero counts
            val header = ByteArray(HEADER_SIZE)
            header[0] = VERSION.toByte()
            header[1] = 17.toByte() // default keySize
            ByteBuffer.wrap(header, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(entrySize.toShort())
            // entryCount, packCount, numFormatInfos, baseTimestamp all stay 0
            return header
        }

        // Convert all contentIds to key bytes and pair with their entries
        val keyEntryPairs = entries.map { entry ->
            contentIdToBytes(entry.contentId) to entry
        }

        // Determine keySize from first entry
        val keySize = keyEntryPairs[0].first.size

        // Sort by key bytes using unsigned byte comparison
        val sorted = keyEntryPairs.sortedWith(
            Comparator { a, b ->
                val ak = a.first
                val bk = b.first
                for (i in 0 until minOf(ak.size, bk.size)) {
                    val diff = (ak[i].toInt() and 0xFF) - (bk[i].toInt() and 0xFF)
                    if (diff != 0) return@Comparator diff
                }
                ak.size - bk.size
            },
        )

        // Compute baseTimestamp = min of all timestampSeconds, clamped to UInt range
        val baseTimestamp: Long = sorted.minOf { (_, entry) ->
            entry.timestampSeconds.coerceIn(0L, UInt.MAX_VALUE.toLong())
        }

        // Deduplicate pack blob IDs, preserving insertion order
        val packBlobIdIndex = LinkedHashMap<String, Int>()
        for ((_, entry) in sorted) {
            val id = entry.packBlobId.value
            if (id !in packBlobIdIndex) {
                packBlobIdIndex[id] = packBlobIdIndex.size
            }
        }
        val packBlobIds = packBlobIdIndex.keys.toList()
        val packCount = packBlobIds.size

        // Deduplicate format tuples
        val formatIndex = LinkedHashMap<Triple<Int, Byte, Byte>, Int>()
        for ((_, entry) in sorted) {
            val key = Triple(entry.compressionHeaderId, entry.formatVersion, entry.encryptionKeyId)
            if (key !in formatIndex) {
                formatIndex[key] = formatIndex.size
            }
        }
        val formats = formatIndex.keys.toList()
        val numFormatInfos = formats.size

        // Build string data (concatenated pack blob ID strings)
        val stringDataBuf = ByteArrayOutputStream()
        val packNameOffsetInStringData = mutableMapOf<String, Int>()
        val packNameLengths = mutableMapOf<String, Int>()
        for (name in packBlobIds) {
            packNameOffsetInStringData[name] = stringDataBuf.size()
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            packNameLengths[name] = nameBytes.size
            stringDataBuf.write(nameBytes)
        }

        val entryCount = sorted.size

        // Calculate absolute offset base for string data section
        val stringDataAbsoluteBase = HEADER_SIZE +
            entryCount * (keySize + entrySize) +
            packCount * PACK_INFO_SIZE +
            numFormatInfos * FORMAT_INFO_SIZE

        // Build output
        val output = ByteArrayOutputStream()

        // Write 17-byte header
        output.write(VERSION)
        output.write(keySize)
        output.write(
            ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
                .putShort(entrySize.toShort()).array(),
        )
        output.write(
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt(entryCount).array(),
        )
        output.write(
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt(packCount).array(),
        )
        output.write(numFormatInfos)
        output.write(
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt(baseTimestamp.toInt()).array(),
        )

        // Write entries section
        for ((keyBytes, entry) in sorted) {
            // Write key bytes
            output.write(keyBytes)

            // Write 19-byte entry data
            val entryData = ByteArray(entrySize)

            // [0..3] relativeTimestamp
            val relTs = (entry.timestampSeconds.coerceIn(0L, UInt.MAX_VALUE.toLong()) - baseTimestamp).toInt()
            ByteBuffer.wrap(entryData, ENTRY_OFFSET_TIMESTAMP, 4)
                .order(ByteOrder.BIG_ENDIAN).putInt(relTs)

            // [4..7] packOffsetAndFlags
            var packOffsetAndFlags = entry.packOffset.toInt()
            if (entry.deleted) {
                packOffsetAndFlags = packOffsetAndFlags or 0x80000000.toInt()
            }
            ByteBuffer.wrap(entryData, ENTRY_OFFSET_PACK_OFFSET_AND_FLAGS, 4)
                .order(ByteOrder.BIG_ENDIAN).putInt(packOffsetAndFlags)

            val origLen = entry.originalLength.toInt()
            val packLen = entry.packedLength.toInt()

            // [8..10] originalLength lower 24 bits (big-endian uint24)
            entryData[ENTRY_OFFSET_ORIGINAL_LENGTH] = ((origLen shr 16) and 0xFF).toByte()
            entryData[ENTRY_OFFSET_ORIGINAL_LENGTH + 1] = ((origLen shr 8) and 0xFF).toByte()
            entryData[ENTRY_OFFSET_ORIGINAL_LENGTH + 2] = (origLen and 0xFF).toByte()

            // [11..13] packedLength lower 24 bits (big-endian uint24)
            entryData[ENTRY_OFFSET_PACKED_LENGTH] = ((packLen shr 16) and 0xFF).toByte()
            entryData[ENTRY_OFFSET_PACKED_LENGTH + 1] = ((packLen shr 8) and 0xFF).toByte()
            entryData[ENTRY_OFFSET_PACKED_LENGTH + 2] = (packLen and 0xFF).toByte()

            // [14..15] packBlobIdIndex lower 16 bits (big-endian uint16)
            val packIdx = packBlobIdIndex[entry.packBlobId.value] ?: 0
            entryData[ENTRY_OFFSET_PACK_BLOB_ID] = ((packIdx shr 8) and 0xFF).toByte()
            entryData[ENTRY_OFFSET_PACK_BLOB_ID + 1] = (packIdx and 0xFF).toByte()

            // [16] formatInfoIndex
            val fmtKey = Triple(entry.compressionHeaderId, entry.formatVersion, entry.encryptionKeyId)
            val fmtIdx = formatIndex[fmtKey] ?: 0
            entryData[ENTRY_OFFSET_FORMAT_ID] = fmtIdx.toByte()

            // [17] extendedPackId high byte (packBlobIdIndex >> 16)
            entryData[ENTRY_OFFSET_EXTENDED_PACK_ID] = ((packIdx shr EXTENDED_PACK_ID_SHIFT) and 0xFF).toByte()

            // [18] highLengthBits: ((originalLength >> 24) << 4) | (packedLength >> 24) & 0x0F
            val highOriginal = (origLen ushr HIGH_LENGTH_SHIFT) and 0x0F
            val highPacked = (packLen ushr HIGH_LENGTH_SHIFT) and HIGH_LENGTH_PACKED_MASK
            entryData[ENTRY_OFFSET_HIGH_LENGTH_BITS] = ((highOriginal shl HIGH_LENGTH_ORIGINAL_SHIFT) or highPacked).toByte()

            output.write(entryData)
        }

        // Write packs section (5 bytes each: nameLength + nameOffset as absolute)
        for (name in packBlobIds) {
            val nameLen = packNameLengths[name] ?: 0
            val nameOffset = stringDataAbsoluteBase + (packNameOffsetInStringData[name] ?: 0)
            output.write(nameLen)
            output.write(
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putInt(nameOffset).array(),
            )
        }

        // Write formats section (6 bytes each)
        for ((compressionHeaderId, formatVersion, encryptionKeyId) in formats) {
            output.write(
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putInt(compressionHeaderId).array(),
            )
            output.write(formatVersion.toInt())
            output.write(encryptionKeyId.toInt())
        }

        // Write string data
        output.write(stringDataBuf.toByteArray())

        return output.toByteArray()
    }

    /**
     * Converts a content ID to its byte representation for V2 index format.
     *
     * V2 index format (keySize 17 or 33):
     * - First byte: marker (0x00 if no prefix, otherwise prefix char 'g'-'z')
     * - Remaining bytes: hash bytes
     */
    internal fun contentIdToBytes(contentId: ContentId): ByteArray {
        val hashBytes = contentId.hashBytes
        return ByteArray(1 + hashBytes.size).apply {
            this[0] = if (contentId.prefix != null) contentId.prefix.code.toByte() else 0x00
            hashBytes.copyInto(this, 1)
        }
    }
}

private data class V2HeaderInfo(
    val version: Int,
    val keySize: Int,
    val entrySize: Int,
    val entryCount: Int,
    val packCount: Int,
    val numFormatInfos: Int,
    val baseTimestamp: Long,
    val entriesOffset: Int,
    val packsOffset: Int,
    val formatsOffset: Int,
)

private data class V2FormatInfo(val compressionHeaderId: Int, val formatVersion: Byte, val encryptionKeyId: Byte)

private class PackIndexV2Impl(private val data: ByteArray) : PackIndex {
    private val header: V2HeaderInfo
    private val formats: List<V2FormatInfo>
    private val packBlobIds: List<String>
    private val entryStride: Int

    init {
        header = parseHeader()
        formats = parseFormats()
        packBlobIds = parsePackBlobIds()
        entryStride = header.keySize + header.entrySize
    }

    private fun parseHeader(): V2HeaderInfo {
        require(data.size >= PackIndexV2.HEADER_SIZE) { "Header too short" }
        val version = data[0].toInt() and 0xFF
        require(version == PackIndexV2.VERSION) { "Invalid version: $version" }
        val keySize = data[1].toInt() and 0xFF
        val entrySize = ByteBuffer.wrap(data, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        val entryCount = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val packCount = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        val numFormatInfos = data[12].toInt() and 0xFF
        val baseTimestamp = ByteBuffer.wrap(data, 13, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

        require(entryCount >= 0) { "Invalid entry count: $entryCount" }
        require(packCount >= 0) { "Invalid pack count: $packCount" }

        // Validate key/entry sizes to prevent zero-stride CPU DoS on corrupted data
        val entryStride = keySize + entrySize
        require(entryStride > 0 || entryCount == 0) {
            "Invalid key/entry size: keySize=$keySize, entrySize=$entrySize with entryCount=$entryCount"
        }

        // Validate claimed counts against actual data size to prevent OOM on corrupted data
        val dataAfterHeader = data.size - PackIndexV2.HEADER_SIZE
        if (entryStride > 0 && entryCount > 0) {
            val maxPossibleEntries = dataAfterHeader / entryStride
            require(entryCount <= maxPossibleEntries) {
                "Entry count $entryCount exceeds data capacity (max $maxPossibleEntries)"
            }
        }

        val entriesOffset = PackIndexV2.HEADER_SIZE
        val packsOffset = entriesOffset + entryCount * entryStride
        if (packCount > 0) {
            val remainingAfterEntries = maxOf(0, data.size - packsOffset)
            val maxPossiblePacks = remainingAfterEntries / PackIndexV2.PACK_INFO_SIZE
            require(packCount <= maxPossiblePacks) {
                "Pack count $packCount exceeds data capacity (max $maxPossiblePacks)"
            }
        }

        val formatsOffset = packsOffset + packCount * PackIndexV2.PACK_INFO_SIZE

        // The declared format region must fit within the data (Long arithmetic avoids overflow).
        // Failing here surfaces a truncated/corrupt header instead of silently defaulting the format
        // metadata (parseFormats would otherwise skip out-of-range entries and mis-attribute
        // compression/format instead of failing).
        val formatsEnd = formatsOffset.toLong() + numFormatInfos.toLong() * PackIndexV2.FORMAT_INFO_SIZE
        require(formatsEnd <= data.size) {
            "Format region ($numFormatInfos formats at offset $formatsOffset) exceeds data size ${data.size}"
        }

        return V2HeaderInfo(version, keySize, entrySize, entryCount, packCount, numFormatInfos, baseTimestamp, entriesOffset, packsOffset, formatsOffset)
    }

    private fun parseFormats(): List<V2FormatInfo> {
        val formats = mutableListOf<V2FormatInfo>()
        for (i in 0 until header.numFormatInfos) {
            val offset = header.formatsOffset + i * PackIndexV2.FORMAT_INFO_SIZE
            if (offset + PackIndexV2.FORMAT_INFO_SIZE <= data.size) {
                val compressionId = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                formats.add(V2FormatInfo(compressionId, data[offset + 4], data[offset + 5]))
            }
        }
        if (formats.isEmpty()) formats.add(V2FormatInfo(0, 0, 0))
        return formats
    }

    private fun parsePackBlobIds(): List<String> {
        val packIds = mutableListOf<String>()
        for (i in 0 until header.packCount) {
            val packInfoOffset = header.packsOffset + i * PackIndexV2.PACK_INFO_SIZE
            if (packInfoOffset + PackIndexV2.PACK_INFO_SIZE <= data.size) {
                val nameLength = data[packInfoOffset].toInt() and 0xFF
                // The nameOffset is an absolute offset in the index file. Use Long arithmetic for the
                // bounds check: a corrupted nameOffset near Int.MAX would otherwise overflow to a
                // negative value, pass `<= data.size`, and crash String() with an out-of-range index.
                val nameOffset = ByteBuffer.wrap(data, packInfoOffset + 1, 4).order(ByteOrder.BIG_ENDIAN).int
                if (nameOffset >= 0 && nameOffset.toLong() + nameLength <= data.size) {
                    packIds.add(String(data, nameOffset, nameLength, Charsets.UTF_8))
                } else {
                    packIds.add("")
                }
            }
        }
        return packIds
    }

    override fun approximateCount(): Int = header.entryCount

    override fun getInfo(contentId: ContentId): ContentInfo? {
        if (header.entryCount == 0) return null
        val keyBytes = contentIdToBytes(contentId)
        if (keyBytes.size != header.keySize) return null
        val position = findEntryPosition(keyBytes)
        if (position >= header.entryCount) return null
        val entryOffset = header.entriesOffset + entryStride * position
        if (entryOffset + entryStride > data.size) return null
        val keyAtPosition = data.copyOfRange(entryOffset, entryOffset + header.keySize)
        if (!keyAtPosition.contentEquals(keyBytes)) return null
        return parseEntry(contentId, entryOffset + header.keySize)
    }

    override fun iterate(startId: ContentId?, endId: ContentId?): Sequence<ContentInfo> = sequence {
        if (header.entryCount == 0) return@sequence
        val startPosition = if (startId != null) {
            val keyBytes = contentIdToBytes(startId)
            if (keyBytes.size == header.keySize) findEntryPosition(keyBytes) else 0
        } else {
            0
        }
        for (i in startPosition until header.entryCount) {
            val entryOffset = header.entriesOffset + entryStride * i
            if (entryOffset + entryStride > data.size) break
            val keyBytes = data.copyOfRange(entryOffset, entryOffset + header.keySize)
            val hasPrefix = hasPrefixFromKeySize(keyBytes, header.keySize)
            val contentId = bytesToContentId(keyBytes, hasPrefix)
            if (endId != null && contentId.toString() >= endId.toString()) break
            parseEntry(contentId, entryOffset + header.keySize)?.let { yield(it) }
        }
    }

    private fun parseEntry(contentId: ContentId, dataOffset: Int): ContentInfo? {
        // Validate minimum entry length is available
        if (dataOffset + PackIndexV2.ENTRY_MIN_LENGTH > data.size) return null

        // Copy entry data, but validate we got enough bytes
        val entryEndOffset = minOf(dataOffset + header.entrySize, data.size)
        val entryData = data.copyOfRange(dataOffset, entryEndOffset)

        // Reject truncated entries - must have at least ENTRY_MIN_LENGTH bytes
        if (entryData.size < PackIndexV2.ENTRY_MIN_LENGTH) return null

        val relativeTimestamp = decodeBigEndianUint32(entryData, PackIndexV2.ENTRY_OFFSET_TIMESTAMP)
        val timestampSeconds = relativeTimestamp.toLong() + header.baseTimestamp
        val packOffsetAndFlags = decodeBigEndianUint32(entryData, PackIndexV2.ENTRY_OFFSET_PACK_OFFSET_AND_FLAGS)
        val deleted = (entryData[PackIndexV2.ENTRY_OFFSET_PACK_OFFSET_AND_FLAGS].toInt() and 0x80) != 0
        val packOffset = packOffsetAndFlags and PackIndexV2.PACK_OFFSET_MASK.toUInt()
        var originalLength = decodeBigEndianUint24(entryData, PackIndexV2.ENTRY_OFFSET_ORIGINAL_LENGTH)
        var packedLength = decodeBigEndianUint24(entryData, PackIndexV2.ENTRY_OFFSET_PACKED_LENGTH)
        if (entryData.size > PackIndexV2.ENTRY_OFFSET_HIGH_LENGTH_BITS) {
            val highBits = entryData[PackIndexV2.ENTRY_OFFSET_HIGH_LENGTH_BITS].toInt() and 0xFF
            originalLength = originalLength or ((highBits shr PackIndexV2.HIGH_LENGTH_ORIGINAL_SHIFT) shl PackIndexV2.HIGH_LENGTH_SHIFT).toUInt()
            packedLength = packedLength or ((highBits and PackIndexV2.HIGH_LENGTH_PACKED_MASK) shl PackIndexV2.HIGH_LENGTH_SHIFT).toUInt()
        }
        var packIdIndex = decodeBigEndianUint16(entryData, PackIndexV2.ENTRY_OFFSET_PACK_BLOB_ID).toInt()
        if (entryData.size > PackIndexV2.ENTRY_OFFSET_EXTENDED_PACK_ID) {
            packIdIndex = packIdIndex or ((entryData[PackIndexV2.ENTRY_OFFSET_EXTENDED_PACK_ID].toInt() and 0xFF) shl PackIndexV2.EXTENDED_PACK_ID_SHIFT)
        }
        val packBlobId = if (packIdIndex < packBlobIds.size) packBlobIds[packIdIndex] else ""
        val formatIndex = if (entryData.size > PackIndexV2.ENTRY_OFFSET_FORMAT_ID) entryData[PackIndexV2.ENTRY_OFFSET_FORMAT_ID].toInt() and 0xFF else 0
        val format = if (formatIndex < formats.size) formats[formatIndex] else V2FormatInfo(0, 0, 0)
        return ContentInfo(contentId, BlobId(packBlobId), timestampSeconds, originalLength, packedLength, packOffset, format.compressionHeaderId, deleted, format.formatVersion, format.encryptionKeyId)
    }

    private fun findEntryPosition(keyBytes: ByteArray): Int {
        var low = 0
        var high = header.entryCount
        while (low < high) {
            val mid = (low + high) / 2
            val midOffset = header.entriesOffset + entryStride * mid
            if (midOffset + header.keySize > data.size) break
            val midKey = data.copyOfRange(midOffset, midOffset + header.keySize)
            if (compareBytes(midKey, keyBytes) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    override fun close() {}

    private fun decodeBigEndianUint32(data: ByteArray, offset: Int): UInt = (
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        ).toUInt()

    private fun decodeBigEndianUint24(data: ByteArray, offset: Int): UInt = (
        ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
        ).toUInt()

    private fun decodeBigEndianUint16(data: ByteArray, offset: Int): UInt = (
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
        ).toUInt()

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun contentIdToBytes(contentId: ContentId) = PackIndexV2.contentIdToBytes(contentId)

    /**
     * Converts bytes back to a content ID.
     *
     * Content ID encoding in Go Kopia V2 index (keySize = 17 or 33):
     * - First byte is ALWAYS a marker:
     *   - 0x00 = no prefix (remaining bytes are hash)
     *   - 'g'-'z' = prefix character (remaining bytes are hash)
     */
    private fun bytesToContentId(bytes: ByteArray, hasMarkerByte: Boolean): ContentId {
        if (bytes.isEmpty()) return ContentId.Empty
        return if (hasMarkerByte && bytes.size > 1) {
            val markerByte = bytes[0].toInt() and 0xFF
            if (markerByte == 0) {
                // 0x00 means no prefix - remaining bytes are hash
                ContentId.fromHash(null, bytes.copyOfRange(1, bytes.size))
            } else {
                // Non-zero means prefix character
                ContentId.fromHash(markerByte.toChar(), bytes.copyOfRange(1, bytes.size))
            }
        } else {
            ContentId.fromHash(null, bytes)
        }
    }

    /**
     * Determines if the first byte of key bytes is a marker byte.
     *
     * In Go Kopia index format:
     * - KeySize 17 or 33 = 1 marker byte + 16 or 32 hash bytes (standard)
     * - Any odd keySize = 1 marker byte + even-length hash bytes
     *
     * Since contentIdToBytes always adds a marker byte, any index built by
     * this implementation will have an odd keySize.
     */
    private fun hasPrefixFromKeySize(keyBytes: ByteArray, keySize: Int): Boolean = keySize % 2 == 1
}
