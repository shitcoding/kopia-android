package org.kopiaKt.snapshot.snapshotfs

import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.Repository
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.DirectoryIterator
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.ErrorEntry
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.kopiaKt.snapshot.fs.Symlink
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.SnapshotManifest
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import org.kopiaKt.snapshot.model.EntryType as SnapshotEntryType

/**
 * Provides virtual filesystem access to snapshot contents stored in a repository.
 *
 * This package mirrors Go's snapshotfs package, allowing filesystem-like access
 * to snapshot data stored in content-addressable storage.
 *
 * Key concepts:
 * - RepositoryDirectory: A directory stored in the repository (lazy loading)
 * - RepositoryFile: A file stored in the repository (streaming read)
 * - RepositorySymlink: A symbolic link stored in the repository
 *
 * Go package: snapshot/snapshotfs
 */

/**
 * Content prefix used for directory objects.
 */
private const val DIRECTORY_OBJECT_PREFIX = "k"

/**
 * Creates an fs.Entry from a snapshot DirEntry and repository.
 *
 * This is the main entry point for converting stored metadata into
 * filesystem entries that can be traversed.
 *
 * Go function: EntryFromDirEntry
 *
 * @param repository The repository to read content from
 * @param entry The directory entry metadata
 * @return A filesystem entry (Directory, File, Symlink, or Error)
 */
fun entryFromDirEntry(repository: Repository, entry: DirEntry): Entry {
    val baseEntry = RepositoryEntry(entry, repository)

    return when (entry.type) {
        SnapshotEntryType.DIRECTORY -> RepositoryDirectory(baseEntry)
        SnapshotEntryType.FILE -> RepositoryFile(baseEntry)
        SnapshotEntryType.SYMLINK -> RepositorySymlink(baseEntry)
        else -> RepositoryErrorEntry(baseEntry, IOException("Unknown entry type: ${entry.type}"))
    }
}

/**
 * Creates a Directory entry for a given object ID.
 *
 * The directory contents are loaded lazily when first accessed.
 *
 * Go function: DirectoryEntry
 *
 * @param repository The repository to read content from
 * @param objectId The object ID containing the directory manifest
 * @return A Directory entry
 */
fun directoryEntry(repository: Repository, objectId: ObjectId): Directory {
    val entry = DirEntry(
        name = "/",
        type = SnapshotEntryType.DIRECTORY,
        permissions = 365, // 0o555
        objectId = objectId.toString(),
    )
    return entryFromDirEntry(repository, entry) as Directory
}

/**
 * Returns the root entry from a snapshot manifest.
 *
 * Go function: SnapshotRoot
 *
 * @param repository The repository to read content from
 * @param manifest The snapshot manifest
 * @return The root entry of the snapshot
 * @throws IllegalArgumentException if manifest has no root entry
 */
fun snapshotRoot(repository: Repository, manifest: SnapshotManifest): Entry {
    val rootEntry = manifest.rootEntry
        ?: throw IllegalArgumentException("Snapshot manifest has no root entry")

    return entryFromDirEntry(repository, rootEntry)
}

/**
 * Determines if an object ID represents a directory.
 *
 * Go function: IsDirectoryID
 *
 * @param objectId The object ID to check
 * @return true if this is a directory object
 */
fun isDirectoryId(objectId: ObjectId): Boolean {
    // Check for indirection
    val (indexOid, isIndirect) = objectId.indexObjectId()
    if (isIndirect) {
        return isDirectoryId(indexOid)
    }

    // Check content ID prefix
    val (contentId, _, ok) = objectId.getContentId()
    if (ok) {
        return contentId.prefix == 'k'
    }

    return false
}

/**
 * Base class for repository entries.
 *
 * Contains metadata from the stored DirEntry and reference to the repository.
 */
internal open class RepositoryEntry(
    val metadata: DirEntry,
    val repo: Repository,
) : Entry {
    override val name: String get() = metadata.name
    override val type: EntryType get() = when (metadata.type) {
        SnapshotEntryType.FILE -> EntryType.FILE
        SnapshotEntryType.DIRECTORY -> EntryType.DIRECTORY
        SnapshotEntryType.SYMLINK -> EntryType.SYMLINK
        else -> EntryType.UNKNOWN
    }
    override val size: Long get() = metadata.fileSize
    override val modTime: Instant get() = metadata.modTime ?: Instant.EPOCH
    override val mode: Int get() = metadata.permissions
    override val owner: OwnerInfo get() = OwnerInfo(
        userId = metadata.userId ?: 0,
        groupId = metadata.groupId ?: 0,
    )
    override val device: DeviceInfo get() = DeviceInfo.EMPTY
    override val localFilesystemPath: String get() = ""

    /**
     * Returns the object ID for this entry's content.
     */
    fun objectId(): ObjectId {
        val oidStr = metadata.objectId ?: return ObjectId.Empty
        return ObjectId.parse(oidStr)
    }

    /**
     * Returns the original DirEntry.
     */
    fun dirEntry(): DirEntry = metadata
}

/**
 * A directory stored in the repository.
 *
 * Directory contents are loaded lazily on first access.
 *
 * Go type: repositoryDirectory
 */
internal class RepositoryDirectory(
    private val base: RepositoryEntry,
) : Directory,
    Entry by base {

    @Volatile
    private var entries: Map<String, DirEntry>? = null

    @Volatile
    private var loadError: Exception? = null

    private val lock = Object()

    override suspend fun child(name: String): Entry? {
        ensureLoaded()
        val childEntry = entries?.get(name) ?: return null
        return entryFromDirEntry(base.repo, childEntry)
    }

    override suspend fun iterate(): DirectoryIterator {
        ensureLoaded()
        val entryList = entries?.values?.toList() ?: emptyList()
        return RepositoryDirectoryIterator(base.repo, entryList)
    }

    override fun supportsMultipleIterations(): Boolean = true

    private suspend fun ensureLoaded() {
        synchronized(lock) {
            if (entries != null || loadError != null) {
                loadError?.let { throw it }
                return
            }
        }

        try {
            val loadedEntries = loadDirectory()
            synchronized(lock) {
                entries = loadedEntries
            }
        } catch (e: Exception) {
            synchronized(lock) {
                loadError = e
            }
            throw e
        }
    }

    private suspend fun loadDirectory(): Map<String, DirEntry> {
        val objectId = base.objectId()
        if (objectId == ObjectId.Empty) {
            return emptyMap()
        }

        val data = base.repo.readObject(objectId)
        val jsonStr = data.toString(Charsets.UTF_8)

        val dirManifest = DirManifest.fromJson(jsonStr)

        if (!dirManifest.isValidDirectoryStream()) {
            throw IOException("Invalid directory stream type: ${dirManifest.streamType}")
        }

        // Build map from entries
        val result = mutableMapOf<String, DirEntry>()
        for (entry in dirManifest.entries) {
            // For directories, update size and modTime from summary
            val adjustedEntry = if (entry.type == SnapshotEntryType.DIRECTORY && entry.dirSummary != null) {
                entry.copy(
                    fileSize = entry.dirSummary.totalFileSize,
                    modTime = entry.dirSummary.maxModTime,
                )
            } else {
                entry
            }
            result[entry.name] = adjustedEntry
        }

        return result
    }

    /**
     * Returns the DirEntry for this directory.
     */
    fun dirEntry(): DirEntry = base.dirEntry()
}

/**
 * Iterator over directory entries from a repository.
 */
internal class RepositoryDirectoryIterator(
    private val repo: Repository,
    entries: List<DirEntry>,
) : DirectoryIterator {
    private val iterator = entries.iterator()

    override suspend fun next(): Entry? {
        if (!iterator.hasNext()) {
            return null
        }
        return entryFromDirEntry(repo, iterator.next())
    }

    override fun close() {
        // Nothing to close
    }
}

/**
 * A file stored in the repository.
 *
 * Go type: repositoryFile
 */
internal class RepositoryFile(
    private val base: RepositoryEntry,
) : File,
    Entry by base {

    override suspend fun open(): InputStream {
        val objectId = base.objectId()
        if (objectId == ObjectId.Empty) {
            return ByteArray(0).inputStream()
        }

        val reader = base.repo.openObject(objectId)
        return RepositoryFileInputStream(reader)
    }

    /**
     * Returns the DirEntry for this file.
     */
    fun dirEntry(): DirEntry = base.dirEntry()
}

/**
 * InputStream wrapper for ObjectReader.
 *
 * Note: This class uses runBlocking to bridge between the suspend-based ObjectReader
 * and the blocking InputStream API. This is necessary because InputStream is a
 * Java blocking API while ObjectReader uses Kotlin coroutines.
 */
internal class RepositoryFileInputStream(
    private val reader: org.kopiaKt.core.`object`.ObjectReader,
) : InputStream() {
    private var position = 0L
    private var buffer = ByteArray(0)
    private var bufferPos = 0
    private var totalLength: Long = -1L // Cached length, -1 means not yet loaded

    private fun ensureLengthLoaded(): Long {
        if (totalLength == -1L) {
            totalLength = kotlinx.coroutines.runBlocking {
                reader.length()
            }
        }
        return totalLength
    }

    override fun read(): Int {
        if (bufferPos >= buffer.size) {
            // Need to refill buffer
            val length = ensureLengthLoaded()
            val remaining = length - position
            if (remaining <= 0) return -1

            val toRead = minOf(remaining, DEFAULT_BUFFER_SIZE.toLong()).toInt()
            buffer = kotlinx.coroutines.runBlocking {
                reader.read(position, toRead)
            }
            bufferPos = 0
            position += buffer.size

            if (buffer.isEmpty()) return -1
        }

        return buffer[bufferPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0

        // First, use any remaining buffered data
        if (bufferPos < buffer.size) {
            val toCopy = minOf(len, buffer.size - bufferPos)
            System.arraycopy(buffer, bufferPos, b, off, toCopy)
            bufferPos += toCopy
            return toCopy
        }

        // Read directly from the reader for large requests
        return kotlinx.coroutines.runBlocking {
            val length = ensureLengthLoaded()
            val remaining = length - position
            if (remaining <= 0) return@runBlocking -1

            val toRead = minOf(len.toLong(), remaining).toInt()
            val data = reader.read(position, toRead)
            if (data.isEmpty()) return@runBlocking -1

            System.arraycopy(data, 0, b, off, data.size)
            position += data.size
            data.size
        }
    }

    override fun available(): Int {
        val buffered = buffer.size - bufferPos
        // Return buffered amount + remaining in object
        // We load length lazily to avoid blocking on construction
        return if (totalLength >= 0) {
            buffered + maxOf(0L, totalLength - position).toInt()
        } else {
            buffered
        }
    }

    override fun close() {
        reader.close()
    }
}

/**
 * A symbolic link stored in the repository.
 *
 * Go type: repositorySymlink
 */
internal class RepositorySymlink(
    private val base: RepositoryEntry,
) : Symlink,
    Entry by base {

    override suspend fun readlink(): String {
        val objectId = base.objectId()
        if (objectId == ObjectId.Empty) {
            return ""
        }

        val data = base.repo.readObject(objectId)
        return data.toString(Charsets.UTF_8)
    }

    override suspend fun resolve(): Entry? {
        // Symlink resolution in repository context is not implemented
        // This would require path resolution within the snapshot tree
        throw UnsupportedOperationException("Symlink.resolve not implemented in RepositoryFS")
    }

    /**
     * Returns the DirEntry for this symlink.
     */
    fun dirEntry(): DirEntry = base.dirEntry()
}

/**
 * An error entry representing a failed entry.
 *
 * Go type: repositoryEntryError
 */
internal class RepositoryErrorEntry(
    private val base: RepositoryEntry,
    override val error: Throwable,
) : ErrorEntry,
    Entry by base
