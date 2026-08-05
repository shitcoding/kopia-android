package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.`object`.ObjectWriterOptions
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.Symlink
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.policy.CompressionPolicy
import org.kopiaKt.snapshot.policy.SplitterPolicy
import java.util.logging.Level
import java.util.logging.Logger

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
    private val forceHashPercentage: Int = 0,
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
        previousEntries: List<DirEntry>,
        checkpointRegistry: CheckpointRegistry,
    ): DirEntry {
        // Cache hit: the first candidate whose metadata still matches the file on disk. Go's
        // findCachedEntry, and the order is the caller's — latest complete snapshot first, then the
        // checkpoints of an interrupted run, so a file that CHANGED since the last complete backup
        // and was already re-uploaded by the interrupted run resolves against the checkpoint.
        val previousEntry = previousEntries.firstOrNull { canReuseEntry(file, it) }
        if (previousEntry != null) {
            // Probabilistic re-hashing: sometimes re-hash even if metadata matches
            if (!shouldForceHash()) {
                progress.cachedFile(relativePath, file.size)
                return createDirEntryFromFile(file, previousEntry.objectId)
            }
        }

        // Upload the file content
        val objectId = uploadFileContent(file, relativePath, checkpointRegistry)

        progress.hashedBytes(file.size)

        return createDirEntryFromFile(file, objectId)
    }

    /**
     * Processes a symlink by storing its target path.
     */
    override suspend fun processSymlink(
        symlink: Symlink,
        relativePath: String,
        previousEntries: List<DirEntry>,
    ): DirEntry {
        // Check if we can reuse a previous entry (see processFile for why this is a list)
        val previousEntry = previousEntries.firstOrNull { canReuseSymlinkEntry(symlink, it) }
        if (previousEntry != null) {
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
            ObjectWriterOptions(), // No compression for symlinks
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
                // 'k' is the Go kopia directory content prefix (objectIDPrefixDirectory). It makes the
                // resulting object a directory ID (snapshotfs.isDirectoryId) so Go can list Kotlin-written
                // directory manifests and snapshot GC can recognise directory objects. Omitting it was a
                // Go cross-compat divergence and a GC data-loss hazard (task-9 prerequisite #1).
                prefix = DIRECTORY_CONTENT_PREFIX,
                compression = DIRECTORY_COMPRESSION,
            ),
        )

        return objectId.toString()
    }

    override suspend fun loadDirManifest(objectId: String): DirManifest? = try {
        // Same cap SnapshotGC.readDirectoryManifest applies: a corrupted previous snapshot whose
        // directory entry points at a huge object must degrade to a re-hash, not OOM the backup.
        val reader = writer.openObject(ObjectId.parse(objectId))
        try {
            val length = reader.length()
            if (length > MAX_DIR_MANIFEST_BYTES) {
                logger.log(Level.WARNING, "Previous directory manifest $objectId is $length bytes; re-hashing subtree")
                null
            } else {
                // fromJson, not this class's own Json: Go writes `"entries": null` for an empty
                // directory, which a plain decode rejects — silently disabling reuse for that tree.
                DirManifest.fromJson(reader.read().decodeToString())
            }
        } finally {
            reader.close()
        }
    } catch (e: CancellationException) {
        throw e // never swallow coroutine cancellation
    } catch (e: Exception) {
        // Best-effort: an unreadable previous manifest only means this subtree is re-hashed, so it
        // must never fail the backup. Logged at FINE because it is expected on a first backup after
        // a format change or a partially-readable repository.
        logger.log(Level.FINE, "Could not load previous directory manifest $objectId; re-hashing subtree", e)
        null
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
     *
     * While the write is in flight the object writer is published to [checkpointRegistry] under the
     * file's path, so a checkpoint taken mid-file can reference the chunks already written. Without
     * that, a checkpoint's `flush()` commits those chunks to a pack blob that nothing in the tree
     * points at — for the one case where this matters most, a multi-gigabyte video on a phone.
     * The entry is renamed by the registry so it can never be read back as the file itself.
     */
    private suspend fun uploadFileContent(
        file: File,
        relativePath: String,
        checkpointRegistry: CheckpointRegistry,
    ): String {
        // Determine compression based on policy
        val compressorName = compressionPolicy.compressorForFile(relativePath, file.size)
        val compression = parseCompression(compressorName)

        val options = ObjectWriterOptions(compression = compression)

        // Stream the file content through the object writer
        file.open().use { inputStream ->
            val objectWriter = writer.newObjectWriter(options)
            checkpointRegistry.addCheckpointCallback(relativePath) {
                // Empty means nothing has reached a chunk boundary yet: there is no content to
                // reference, so contributing no entry is the correct answer, not an error.
                objectWriter.checkpoint()
                    .takeIf { it != ObjectId.Empty }
                    ?.let { createDirEntryFromFile(file, it.toString()) }
            }
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
                checkpointRegistry.removeCheckpointCallback(relativePath)
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
    private fun createDirEntryFromFile(file: File, objectId: String?): DirEntry = DirEntry(
        name = file.name,
        type = EntryType.FILE,
        permissions = file.mode,
        fileSize = file.size,
        modTime = file.modTime,
        userId = file.owner.userId,
        groupId = file.owner.groupId,
        objectId = objectId,
    )

    /**
     * Creates a DirEntry from a Symlink with the given objectId.
     */
    private fun createDirEntryFromSymlink(symlink: Symlink, objectId: String?): DirEntry = DirEntry(
        name = symlink.name,
        type = EntryType.SYMLINK,
        permissions = symlink.mode,
        modTime = symlink.modTime,
        userId = symlink.owner.userId,
        groupId = symlink.owner.groupId,
        objectId = objectId,
    )

    companion object {
        private const val BUFFER_SIZE = 64 * 1024 // 64KB buffer
        private val DIRECTORY_COMPRESSION = CompressionAlgorithm.ZSTD_DEFAULT // Use zstd for directory manifests

        // Go kopia's objectIDPrefixDirectory: directory manifest content is stored with a 'k' prefix.
        private const val DIRECTORY_CONTENT_PREFIX = 'k'

        private val logger = Logger.getLogger(FileUploader::class.java.name)

        /** Cap on a previous directory manifest, mirroring SnapshotGC.readDirectoryManifest. */
        private const val MAX_DIR_MANIFEST_BYTES = 128L * 1024 * 1024
    }
}
