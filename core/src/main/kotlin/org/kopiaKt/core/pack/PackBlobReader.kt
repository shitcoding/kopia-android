package org.kopiaKt.core.pack

import org.kopiaKt.core.content.ContentInfo
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads and extracts data from pack blobs.
 *
 * Pack blob structure:
 * [PREAMBLE] [CONTENT BLOCKS] [LOCAL INDEX] [POSTAMBLE]
 *
 * This reader can:
 * - Find and parse the postamble
 * - Extract the local index for recovery
 * - Extract individual content blocks by offset
 */
object PackBlobReader {

    private val logger = Logger.getLogger(PackBlobReader::class.java.name)

    /**
     * Recovers the content index from a pack blob's local index.
     *
     * This is used for disaster recovery when the main repository
     * index is damaged or lost.
     *
     * @param packData The full pack blob data
     * @param encryptionOverhead The encryption overhead (for V1 index original length computation)
     * @return List of content infos recovered from the local index, or null if recovery fails
     */
    fun recoverIndex(packData: ByteArray, encryptionOverhead: UInt = 0u): List<ContentInfo>? {
        // Find postamble
        val postamble = PackBlobPostamble.findPostamble(packData) ?: return null

        // Extract local index
        val indexOffset = postamble.localIndexOffset.toInt()
        val indexLength = postamble.localIndexLength.toInt()

        if (indexOffset < 0 || indexOffset + indexLength > packData.size) {
            return null
        }

        val localIndexData = packData.copyOfRange(indexOffset, indexOffset + indexLength)

        // In a real implementation, we would decrypt the index here using postamble.localIndexIV
        // For now, we assume the index is stored unencrypted

        return try {
            val index = PackIndexV1.open(localIndexData, encryptionOverhead)
            index.iterate().toList()
        } catch (e: Exception) {
            // Best-effort recovery probe: a failure here just means this blob has no readable local
            // index, which is an ordinary outcome (this is also called on arbitrary data). Log at
            // FINE so it's available when debugging recovery without spamming normal operation —
            // unlike the index/manifest load paths, a null here is not evidence of corruption.
            logger.log(Level.FINE, "No recoverable local index in pack blob: ${e.message}", e)
            null
        }
    }

    /**
     * Extracts content data from a pack blob at the specified offset.
     *
     * @param packData The full pack blob data
     * @param offset Byte offset where the content starts
     * @param length Length of the content in bytes
     * @return The extracted content data
     * @throws IllegalArgumentException if offset/length are out of bounds
     */
    fun extractContent(packData: ByteArray, offset: Int, length: Int): ByteArray {
        require(offset >= 0) { "Offset must be non-negative" }
        require(length >= 0) { "Length must be non-negative" }
        require(offset + length <= packData.size) {
            "Content range [$offset, ${offset + length}) exceeds pack size ${packData.size}"
        }

        return packData.copyOfRange(offset, offset + length)
    }

    /**
     * Extracts content data from a pack blob using content info.
     *
     * @param packData The full pack blob data
     * @param contentInfo The content info with offset and length
     * @return The extracted content data
     */
    fun extractContent(packData: ByteArray, contentInfo: ContentInfo): ByteArray {
        return extractContent(
            packData,
            contentInfo.packOffset.toInt(),
            contentInfo.packedLength.toInt()
        )
    }

    /**
     * Parses the postamble from a pack blob.
     *
     * @param packData The full pack blob data
     * @return The postamble, or null if not found
     */
    fun parsePostamble(packData: ByteArray): PackBlobPostamble? {
        return PackBlobPostamble.findPostamble(packData)
    }

    /**
     * Gets information about a pack blob.
     *
     * @param packData The full pack blob data
     * @return Pack blob info, or null if invalid
     */
    fun getPackInfo(packData: ByteArray): PackBlobInfo? {
        val postamble = PackBlobPostamble.findPostamble(packData) ?: return null

        return PackBlobInfo(
            totalSize = packData.size,
            localIndexOffset = postamble.localIndexOffset.toInt(),
            localIndexLength = postamble.localIndexLength.toInt(),
            localIndexIV = postamble.localIndexIV
        )
    }
}

/**
 * Information about a pack blob structure.
 */
data class PackBlobInfo(
    val totalSize: Int,
    val localIndexOffset: Int,
    val localIndexLength: Int,
    val localIndexIV: ByteArray
) {
    /**
     * Size of the content data area (between preamble and local index).
     * Note: This doesn't account for preamble size which is unknown.
     */
    val contentAreaEnd: Int
        get() = localIndexOffset

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackBlobInfo) return false

        if (totalSize != other.totalSize) return false
        if (localIndexOffset != other.localIndexOffset) return false
        if (localIndexLength != other.localIndexLength) return false
        if (!localIndexIV.contentEquals(other.localIndexIV)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = totalSize
        result = 31 * result + localIndexOffset
        result = 31 * result + localIndexLength
        result = 31 * result + localIndexIV.contentHashCode()
        return result
    }
}
