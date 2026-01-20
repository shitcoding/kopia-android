package org.kopiaKt.core.pack

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Builds pack blobs containing multiple content entries.
 *
 * Pack blob structure:
 * [PREAMBLE] [CONTENT BLOCKS] [LOCAL INDEX] [POSTAMBLE]
 *
 * The builder:
 * 1. Generates a random preamble (or uses provided one)
 * 2. Writes content blocks sequentially (already encrypted)
 * 3. Builds a local index (not encrypted in this simplified version)
 * 4. Appends a postamble with recovery info
 *
 * @property packBlobId The ID for this pack blob
 * @property preambleLength Length of the random preamble in bytes
 * @property encryptionOverhead The encryption overhead (for computing original length)
 * @property timestampSeconds The timestamp to use for content entries (defaults to current time)
 * @property preamble Optional custom preamble (random generated if not provided)
 */
class PackBlobBuilder(
    private val packBlobId: BlobId,
    private val preambleLength: Int = DEFAULT_PREAMBLE_LENGTH,
    private val encryptionOverhead: Int = 0,
    private val timestampSeconds: Long = System.currentTimeMillis() / 1000,
    preamble: ByteArray? = null
) {
    private val preambleData: ByteArray = preamble ?: generateRandomBytes(preambleLength)
    private val contentBuffer = ByteArrayOutputStream()
    private val contentInfos = mutableListOf<PendingContentInfo>()
    private var built = false

    init {
        // Write preamble to start of buffer
        contentBuffer.write(preambleData)
    }

    /**
     * Adds encrypted content to the pack blob.
     *
     * @param contentId The content ID for this content
     * @param encryptedData The encrypted content data
     * @param originalLength The original (unencrypted) length of the content
     * @param compressionHeaderId The compression algorithm header ID (0 = none)
     * @param formatVersion The format version
     * @param encryptionKeyId The encryption key ID
     */
    fun addContent(
        contentId: ContentId,
        encryptedData: ByteArray,
        originalLength: UInt,
        compressionHeaderId: Int = 0,
        formatVersion: Byte = 0,
        encryptionKeyId: Byte = 0
    ) {
        check(!built) { "Cannot add content after pack has been built" }

        val packOffset = contentBuffer.size().toUInt()

        contentInfos.add(
            PendingContentInfo(
                contentId = contentId,
                packOffset = packOffset,
                packedLength = encryptedData.size.toUInt(),
                originalLength = originalLength,
                compressionHeaderId = compressionHeaderId,
                formatVersion = formatVersion,
                encryptionKeyId = encryptionKeyId
            )
        )

        contentBuffer.write(encryptedData)
    }

    /**
     * Returns the current size of the pack blob (preamble + content data).
     */
    fun currentSize(): Int = contentBuffer.size()

    /**
     * Returns the number of content entries added.
     */
    fun contentCount(): Int = contentInfos.size

    /**
     * Builds the final pack blob.
     *
     * @return Pair of (pack blob data, list of content infos)
     */
    fun build(): Pair<ByteArray, List<ContentInfo>> {
        check(!built) { "Pack has already been built" }
        built = true

        // Build content infos with final data
        val finalInfos = contentInfos.map { pending ->
            ContentInfo(
                contentId = pending.contentId,
                packBlobId = packBlobId,
                timestampSeconds = timestampSeconds,
                originalLength = pending.originalLength,
                packedLength = pending.packedLength,
                packOffset = pending.packOffset,
                compressionHeaderId = pending.compressionHeaderId,
                deleted = false,
                formatVersion = pending.formatVersion,
                encryptionKeyId = pending.encryptionKeyId
            )
        }

        // Build local index
        val localIndexOffset = contentBuffer.size()
        val localIndexData = buildLocalIndex(finalInfos)

        // Compute IV for local index (hash of the index data)
        val localIndexIV = computeIndexIV(localIndexData)

        // Write local index (in real implementation this would be encrypted)
        contentBuffer.write(localIndexData)

        // Build and write postamble
        val postamble = PackBlobPostamble(
            localIndexIV = localIndexIV,
            localIndexOffset = localIndexOffset.toUInt(),
            localIndexLength = localIndexData.size.toUInt()
        )
        contentBuffer.write(postamble.toBytes())

        return contentBuffer.toByteArray() to finalInfos
    }

    /**
     * Builds the local index for recovery purposes.
     */
    private fun buildLocalIndex(infos: List<ContentInfo>): ByteArray {
        return PackIndexV1.build(infos)
    }

    /**
     * Computes the IV for encrypting the local index.
     * Uses the last 16 bytes of SHA-256 hash of the index data.
     */
    private fun computeIndexIV(indexData: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(indexData)
        // Use last 16 bytes as IV (matching Go implementation)
        return hash.copyOfRange(hash.size - 16, hash.size)
    }

    private data class PendingContentInfo(
        val contentId: ContentId,
        val packOffset: UInt,
        val packedLength: UInt,
        val originalLength: UInt,
        val compressionHeaderId: Int,
        val formatVersion: Byte,
        val encryptionKeyId: Byte
    )

    companion object {
        /**
         * Default preamble length in bytes.
         */
        const val DEFAULT_PREAMBLE_LENGTH = 32

        /**
         * Default maximum preamble length in bytes.
         */
        const val DEFAULT_MAX_PREAMBLE_LENGTH = 32

        private val secureRandom = SecureRandom()

        private fun generateRandomBytes(length: Int): ByteArray {
            val bytes = ByteArray(length)
            secureRandom.nextBytes(bytes)
            return bytes
        }
    }
}
