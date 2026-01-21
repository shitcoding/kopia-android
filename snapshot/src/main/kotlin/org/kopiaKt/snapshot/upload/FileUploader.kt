package org.kopiaKt.snapshot.upload

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.`object`.ObjectWriterOptions
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.Symlink
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.policy.CompressionPolicy
import org.kopiaKt.snapshot.policy.SplitterPolicy

/**
 * Uploads files and directory manifests to the repository.
 *
 * Implements EntryProcessor to handle the actual content upload using
 * RepositoryWriter for hashing, compression, encryption, and deduplication.
 *
 * Go type: snapshotfs.Uploader (file upload portion)
 */
class FileUploader(
    private val writer: RepositoryWriter,
    private val progress: UploadProgress,
    private val compressionPolicy: CompressionPolicy = CompressionPolicy(),
    private val splitterPolicy: SplitterPolicy = SplitterPolicy(),
    private val forceHashPercentage: Int = 0
) : EntryProcessor {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Processes a file by uploading its content to the repository.
     *
     * If previousEntry is provided and metadata matches, the file may be
     * skipped (cached) based on forceHashPercentage setting.
     */
    override suspend fun processFile(
        file: File,
        relativePath: String,
        previousEntry: DirEntry?
    ): DirEntry {
        // Check if we can reuse the previous entry (cache hit)
        if (previousEntry != null && canReuseEntry(file, previousEntry)) {
            // Probabilistic re-hashing: sometimes re-hash even if metadata matches
            if (!shouldForceHash()) {
                progress.cachedFile(relativePath, file.size)
                return createDirEntryFromFile(file, previousEntry.objectId)
            }
        }

        // Upload the file content
        val objectId = uploadFileContent(file, relativePath)

        progress.hashedBytes(file.size)

        return createDirEntryFromFile(file, objectId)
    }

    /**
     * Processes a symlink by storing its target path.
     */
    override suspend fun processSymlink(
        symlink: Symlink,
        relativePath: String,
        previousEntry: DirEntry?
    ): DirEntry {
        // Check if we can reuse the previous entry
        if (previousEntry != null && canReuseSymlinkEntry(symlink, previousEntry)) {
            if (!shouldForceHash()) {
                progress.cachedFile(relativePath, 0)
                return createDirEntryFromSymlink(symlink, previousEntry.objectId)
            }
        }

        // Upload symlink target as object content
        val target = symlink.readlink()
        val targetBytes = target.toByteArray(Charsets.UTF_8)

        val objectId = writer.writeObject(
            targetBytes,
            ObjectWriterOptions() // No compression for symlinks
        ).toString()

        return createDirEntryFromSymlink(symlink, objectId)
    }

    /**
     * Uploads a directory manifest as a JSON object.
     */
    override suspend fun uploadDirectoryManifest(manifest: DirManifest): String {
        val jsonBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

        val objectId = writer.writeObject(
            jsonBytes,
            ObjectWriterOptions(
                compression = DIRECTORY_COMPRESSION
            )
        )

        return objectId.toString()
    }

    /**
     * Checks if a file entry can be reused based on metadata comparison.
     *
     * Matching criteria (same as Go):
     * - Modification time matches
     * - Size matches
     * - Permissions match
     */
    private fun canReuseEntry(file: File, previous: DirEntry): Boolean {
        // Must have an objectId to reuse
        if (previous.objectId == null) return false

        // Type must match
        if (previous.type != EntryType.FILE) return false

        // Size must match
        if (file.size != previous.fileSize) return false

        // Mod time must match
        val prevModTime = previous.modTime
        if (prevModTime == null || file.modTime != prevModTime) return false

        // Permissions must match (if tracked)
        if (previous.permissions != 0 && file.mode != previous.permissions) return false

        return true
    }

    /**
     * Checks if a symlink entry can be reused.
     */
    private fun canReuseSymlinkEntry(symlink: Symlink, previous: DirEntry): Boolean {
        if (previous.objectId == null) return false
        if (previous.type != EntryType.SYMLINK) return false

        // Mod time must match
        val prevModTime = previous.modTime
        if (prevModTime == null || symlink.modTime != prevModTime) return false

        return true
    }

    /**
     * Determines whether to force re-hashing based on probability.
     */
    private fun shouldForceHash(): Boolean {
        if (forceHashPercentage <= 0) return false
        if (forceHashPercentage >= 100) return true
        return (Math.random() * 100) < forceHashPercentage
    }

    /**
     * Uploads file content to the repository.
     */
    private suspend fun uploadFileContent(file: File, relativePath: String): String {
        // Determine compression based on policy
        val compressorName = compressionPolicy.compressorForFile(relativePath, file.size)
        val compression = parseCompression(compressorName)

        val options = ObjectWriterOptions(compression = compression)

        // Stream the file content through the object writer
        file.open().use { inputStream ->
            val objectWriter = writer.newObjectWriter(options)
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // ObjectWriter.write takes full ByteArray, so slice if needed
                    if (bytesRead == buffer.size) {
                        objectWriter.write(buffer)
                    } else {
                        objectWriter.write(buffer.copyOf(bytesRead))
                    }
                }

                val result = objectWriter.result()
                return result.toString()
            } finally {
                objectWriter.close()
            }
        }
    }

    /**
     * Parses a compression algorithm name to the enum value.
     */
    private fun parseCompression(name: String): CompressionAlgorithm? {
        if (name.isEmpty()) return null
        return CompressionAlgorithm.fromId(name)
    }

    /**
     * Creates a DirEntry from a File with the given objectId.
     */
    private fun createDirEntryFromFile(file: File, objectId: String?): DirEntry {
        return DirEntry(
            name = file.name,
            type = EntryType.FILE,
            permissions = file.mode,
            fileSize = file.size,
            modTime = file.modTime,
            userId = file.owner.userId,
            groupId = file.owner.groupId,
            objectId = objectId
        )
    }

    /**
     * Creates a DirEntry from a Symlink with the given objectId.
     */
    private fun createDirEntryFromSymlink(symlink: Symlink, objectId: String?): DirEntry {
        return DirEntry(
            name = symlink.name,
            type = EntryType.SYMLINK,
            permissions = symlink.mode,
            modTime = symlink.modTime,
            userId = symlink.owner.userId,
            groupId = symlink.owner.groupId,
            objectId = objectId
        )
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64KB buffer
        private val DIRECTORY_COMPRESSION = CompressionAlgorithm.ZSTD_DEFAULT // Use zstd for directory manifests
    }
}
