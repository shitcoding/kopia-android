package org.kopiaKt.core.pack

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.encryption.Encryptor
import org.kopiaKt.core.hashing.ContentHasher
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
 * 3. Builds a local index: [build] leaves it unencrypted (assembly/tests); [buildEncrypted] encrypts
 *    it the way Go kopia does (repo-hash IV, AES-GCM), which is the production path
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
    preamble: ByteArray? = null,
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
        encryptionKeyId: Byte = 0,
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
                encryptionKeyId = encryptionKeyId,
            ),
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
     * Builds the final pack blob with an UNENCRYPTED local index.
     *
     * This is the low-level assembly path (used by pack-mechanics unit tests and non-crypto callers).
     * The postamble IV is a plain hash of the index and is NOT Go-compatible. Production must use
     * [buildEncrypted], which encrypts the local index exactly as Go kopia does.
     *
     * @return Pair of (pack blob data, list of content infos)
     */
    fun build(): Pair<ByteArray, List<ContentInfo>> {
        val finalInfos = finalizeContents()
        val localIndexData = buildLocalIndex(finalInfos)
        writeLocalIndexAndPostamble(localIndexData, computeIndexIV(localIndexData))
        return contentBuffer.toByteArray() to finalInfos
    }

    /**
     * Builds the final pack blob with a Go-compatible ENCRYPTED local (recovery) index.
     *
     * Matches Go kopia's `appendPackFileIndexRecoveryData`: the "IV"/content-id is the repo's keyed
     * hash of the plaintext serialized index (full, untruncated output), the index is encrypted with
     * that id as the key-derivation input and AAD (random nonce prepended), and the postamble records
     * the ciphertext's offset and length. Encryption uses a fresh random nonce, so the bytes are not
     * reproducible — the guarantee is that Go (and [PackBlobReader.recoverIndex] with the matching
     * decryptor) can decrypt it. See task-13.
     *
     * @param hasher The repo content hasher (produces the local index IV from the plaintext index)
     * @param encryptor The repo content encryptor
     * @return Pair of (pack blob data, list of content infos)
     */
    suspend fun buildEncrypted(
        hasher: ContentHasher,
        encryptor: Encryptor,
    ): Pair<ByteArray, List<ContentInfo>> {
        val finalInfos = finalizeContents()
        val localIndexData = buildLocalIndex(finalInfos)
        val localIndexIV = hasher.hashContent(localIndexData)
        val encryptedLocalIndex = encryptor.encryptWithRawId(localIndexData, localIndexIV)
        writeLocalIndexAndPostamble(encryptedLocalIndex, localIndexIV)
        return contentBuffer.toByteArray() to finalInfos
    }

    /**
     * Marks the builder as built and produces the final content infos. Idempotency is enforced here so
     * both [build] and [buildEncrypted] share the single-use guard.
     */
    private fun finalizeContents(): List<ContentInfo> {
        check(!built) { "Pack has already been built" }
        built = true

        return contentInfos.map { pending ->
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
                encryptionKeyId = pending.encryptionKeyId,
            )
        }
    }

    /**
     * Appends the (already plaintext-or-ciphertext) local index bytes and a postamble that records the
     * IV plus the offset and length of exactly those bytes.
     */
    private fun writeLocalIndexAndPostamble(localIndexBytes: ByteArray, localIndexIV: ByteArray) {
        val localIndexOffset = contentBuffer.size()
        contentBuffer.write(localIndexBytes)

        val postamble = PackBlobPostamble(
            localIndexIV = localIndexIV,
            localIndexOffset = localIndexOffset.toUInt(),
            localIndexLength = localIndexBytes.size.toUInt(),
        )
        contentBuffer.write(postamble.toBytes())
    }

    /**
     * Builds the local index for recovery purposes.
     */
    private fun buildLocalIndex(infos: List<ContentInfo>): ByteArray = PackIndexV1.build(infos)

    /**
     * Placeholder IV for the UNENCRYPTED [build] path only (last 16 bytes of SHA-256 of the index).
     * This is NOT Go-compatible — Go derives the local index IV from the repo's keyed hash of the
     * plaintext index. The real (Go-compatible) IV is computed in [buildEncrypted] via the repo hasher.
     */
    private fun computeIndexIV(indexData: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(indexData)
        return hash.copyOfRange(hash.size - 16, hash.size)
    }

    private data class PendingContentInfo(
        val contentId: ContentId,
        val packOffset: UInt,
        val packedLength: UInt,
        val originalLength: UInt,
        val compressionHeaderId: Int,
        val formatVersion: Byte,
        val encryptionKeyId: Byte,
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
