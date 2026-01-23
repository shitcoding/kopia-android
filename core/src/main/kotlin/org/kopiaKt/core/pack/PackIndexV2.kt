package org.kopiaKt.core.pack

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
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

    fun open(data: ByteArray, perContentOverhead: UInt = 0u): PackIndex {
        return PackIndexV2Impl(data)
    }

    fun build(entries: List<ContentInfo>): ByteArray {
        TODO("V2 index building not yet implemented")
    }
}

private data class V2HeaderInfo(
    val version: Int, val keySize: Int, val entrySize: Int, val entryCount: Int,
    val packCount: Int, val numFormatInfos: Int, val baseTimestamp: Long,
    val entriesOffset: Int, val packsOffset: Int, val formatsOffset: Int
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

        // Calculate offsets based on header size and entry counts (matching Go Kopia)
        val entryStride = keySize + entrySize
        val entriesOffset = PackIndexV2.HEADER_SIZE
        val packsOffset = entriesOffset + entryCount * entryStride
        val formatsOffset = packsOffset + packCount * PackIndexV2.PACK_INFO_SIZE

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
                // The nameOffset is an absolute offset in the index file
                val nameOffset = ByteBuffer.wrap(data, packInfoOffset + 1, 4).order(ByteOrder.BIG_ENDIAN).int
                if (nameOffset >= 0 && nameOffset + nameLength <= data.size) {
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
        } else 0
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
        if (dataOffset + PackIndexV2.ENTRY_MIN_LENGTH > data.size) return null
        val entryData = data.copyOfRange(dataOffset, minOf(dataOffset + header.entrySize, data.size))
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
        var low = 0; var high = header.entryCount
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

    private fun decodeBigEndianUint32(data: ByteArray, offset: Int): UInt =
        ((data[offset].toInt() and 0xFF) shl 24 or (data[offset + 1].toInt() and 0xFF) shl 16 or (data[offset + 2].toInt() and 0xFF) shl 8 or (data[offset + 3].toInt() and 0xFF)).toUInt()

    private fun decodeBigEndianUint24(data: ByteArray, offset: Int): UInt =
        ((data[offset].toInt() and 0xFF) shl 16 or (data[offset + 1].toInt() and 0xFF) shl 8 or (data[offset + 2].toInt() and 0xFF)).toUInt()

    private fun decodeBigEndianUint16(data: ByteArray, offset: Int): UInt =
        ((data[offset].toInt() and 0xFF) shl 8 or (data[offset + 1].toInt() and 0xFF)).toUInt()

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) { val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF); if (diff != 0) return diff }
        return a.size - b.size
    }

    private fun contentIdToBytes(contentId: ContentId): ByteArray {
        val hashBytes = contentId.hashBytes
        return if (contentId.prefix != null) ByteArray(1 + hashBytes.size).apply { this[0] = contentId.prefix.code.toByte(); hashBytes.copyInto(this, 1) }
        else hashBytes.copyOf()
    }

    private fun bytesToContentId(bytes: ByteArray, hasPrefix: Boolean): ContentId {
        if (bytes.isEmpty()) return ContentId.Empty
        return if (hasPrefix && bytes.size > 1) ContentId.fromHash((bytes[0].toInt() and 0xFF).toChar(), bytes.copyOfRange(1, bytes.size))
        else ContentId.fromHash(null, bytes)
    }

    private fun hasPrefixFromKeySize(keyBytes: ByteArray, keySize: Int): Boolean {
        if (keyBytes.isEmpty() || keySize <= 0) return false
        val possiblePrefix = (keyBytes[0].toInt() and 0xFF).toChar()
        return possiblePrefix in 'g'..'z' && (keySize == 17 || keySize == 33)
    }
}
