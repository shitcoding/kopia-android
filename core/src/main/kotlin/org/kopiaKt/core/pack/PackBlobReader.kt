package org.kopiaKt.core.pack

import kotlinx.coroutines.CancellationException
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
        // Unencrypted local index (assembly path / legacy). Go kopia stores it encrypted — use the
        // decryptor overload below for Go-produced (or KopiaKt buildEncrypted) pack blobs.
        val (_, localIndexData) = extractLocalIndex(packData) ?: return null
        return parseLocalIndex(localIndexData, encryptionOverhead)
    }

    /**
     * Recovers the content index from a pack blob whose local index is ENCRYPTED (Go-compatible).
     *
     * Reads the postamble, extracts the ciphertext span, decrypts it with [decryptor] keyed by the
     * postamble's `localIndexIV`, then parses the plaintext pack index. Returns null if the pack has
     * no postamble, the offsets are out of range, decryption fails, or the plaintext is not a readable
     * index — this is a best-effort recovery probe. See task-13.
     *
     * @param packData The full pack blob data
     * @param encryptionOverhead The content encryption overhead (for V1 original-length computation)
     * @param decryptor Decrypts the local index ciphertext given the postamble IV (typically
     *   `Encryptor.decryptWithRawId`)
     */
    suspend fun recoverIndex(
        packData: ByteArray,
        encryptionOverhead: UInt,
        decryptor: LocalIndexDecryptor
    ): List<ContentInfo>? {
        val (postamble, encryptedIndexData) = extractLocalIndex(packData) ?: return null

        val localIndexData = try {
            decryptor.decrypt(encryptedIndexData, postamble.localIndexIV)
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            logger.log(Level.FINE, "Local index decryption failed: ${e.message}", e)
            return null
        }

        return parseLocalIndex(localIndexData, encryptionOverhead)
    }

    /**
     * Locates the postamble and returns it with the raw local-index bytes it points at (ciphertext for
     * encrypted packs, plaintext otherwise). Returns null if there is no valid postamble or the offset/
     * length are out of range.
     */
    private fun extractLocalIndex(packData: ByteArray): Pair<PackBlobPostamble, ByteArray>? {
        val postamble = PackBlobPostamble.findPostamble(packData) ?: return null

        // Long arithmetic: the postamble is untrusted recovery input, and a corrupt (CRC-valid) offset
        // or length near UInt.MAX would overflow Int and slip past the bounds check into an
        // out-of-range copyOfRange. UInt.toLong() is always non-negative, so only the upper bound
        // needs checking, and the sum can't overflow Long (two UInts fit easily).
        val indexOffset = postamble.localIndexOffset.toLong()
        val indexLength = postamble.localIndexLength.toLong()

        if (indexOffset + indexLength > packData.size.toLong()) {
            return null
        }

        return postamble to packData.copyOfRange(indexOffset.toInt(), (indexOffset + indexLength).toInt())
    }

    /**
     * Parses plaintext local-index bytes into content infos, or null if they are not a readable index.
     */
    private fun parseLocalIndex(localIndexData: ByteArray, encryptionOverhead: UInt): List<ContentInfo>? {
        return try {
            // Dispatch on the index version header (V1/V2). Go kopia writes the local index at the repo's
            // configured index version, which defaults to V2 — hardcoding V1 fails to recover Go packs.
            PackIndexFactory.open(localIndexData, encryptionOverhead).iterate().toList()
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
 * Decrypts an encrypted pack-blob local index given the postamble's `localIndexIV`.
 *
 * Typically backed by `Encryptor.decryptWithRawId(ciphertext, iv)`.
 */
fun interface LocalIndexDecryptor {
    suspend fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray
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
