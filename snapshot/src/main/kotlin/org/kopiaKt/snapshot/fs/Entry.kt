package org.kopiaKt.snapshot.fs

import java.io.Closeable
import java.io.InputStream
import java.nio.file.attribute.FileTime
import java.time.Instant

/**
 * Owner information for filesystem entries.
 *
 * Go type: fs.OwnerInfo
 */
data class OwnerInfo(
    val userId: Int = 0,
    val groupId: Int = 0
) {
    companion object {
        val EMPTY = OwnerInfo(0, 0)
    }
}

/**
 * Device information for filesystem entries.
 * Used for detecting filesystem boundaries (oneFileSystem option).
 *
 * Go type: fs.DeviceInfo
 */
data class DeviceInfo(
    val dev: Long = 0,
    val rdev: Long = 0
) {
    companion object {
        val EMPTY = DeviceInfo(0, 0)
    }
}

/**
 * Type of filesystem entry.
 */
enum class EntryType {
    FILE,
    DIRECTORY,
    SYMLINK,
    UNKNOWN,
    ERROR
}

/**
 * Base interface for all filesystem entries.
 * Provides metadata about files, directories, and symlinks.
 *
 * Go type: fs.Entry
 */
interface Entry : Closeable {
    /**
     * Name of the entry (filename, not full path).
     */
    val name: String

    /**
     * Type of entry (file, directory, symlink, etc.).
     */
    val type: EntryType

    /**
     * Size in bytes. For directories, this is typically 0.
     */
    val size: Long

    /**
     * Modification time.
     */
    val modTime: Instant

    /**
     * Unix file mode/permissions.
     */
    val mode: Int

    /**
     * Owner information (UID/GID).
     */
    val owner: OwnerInfo

    /**
     * Device information for filesystem boundary detection.
     */
    val device: DeviceInfo

    /**
     * Full path on the local filesystem, or empty string if not applicable.
     */
    val localFilesystemPath: String

    /**
     * Whether this entry represents a directory.
     */
    fun isDirectory(): Boolean = type == EntryType.DIRECTORY

    /**
     * Whether this entry represents a regular file.
     */
    fun isFile(): Boolean = type == EntryType.FILE

    /**
     * Whether this entry represents a symbolic link.
     */
    fun isSymlink(): Boolean = type == EntryType.SYMLINK

    /**
     * Default close implementation (no-op for most entries).
     */
    override fun close() {}
}

/**
 * Iterator for directory contents.
 *
 * Go type: fs.DirectoryIterator
 */
interface DirectoryIterator : Closeable {
    /**
     * Returns the next entry in the directory, or null if no more entries.
     * Throws an exception if an error occurs during iteration.
     */
    suspend fun next(): Entry?
}

/**
 * Directory entry that can enumerate its children.
 *
 * Go type: fs.Directory
 */
interface Directory : Entry {
    /**
     * Returns a specific child entry by name, or null if not found.
     */
    suspend fun child(name: String): Entry?

    /**
     * Returns an iterator over all children in this directory.
     */
    suspend fun iterate(): DirectoryIterator

    /**
     * Whether this directory supports multiple concurrent iterations.
     */
    fun supportsMultipleIterations(): Boolean = true

    /**
     * Collects all entries into a list.
     * Convenience method that iterates and collects all children.
     */
    suspend fun readEntries(): List<Entry> {
        val entries = mutableListOf<Entry>()
        iterate().use { iterator ->
            while (true) {
                val entry = iterator.next() ?: break
                entries.add(entry)
            }
        }
        return entries
    }
}

/**
 * File entry that can be read.
 *
 * Go type: fs.File
 */
interface File : Entry {
    /**
     * Opens the file for reading.
     * The caller is responsible for closing the returned stream.
     */
    suspend fun open(): InputStream
}

/**
 * Symbolic link entry.
 *
 * Go type: fs.Symlink
 */
interface Symlink : Entry {
    /**
     * Returns the target path of the symbolic link.
     */
    suspend fun readlink(): String

    /**
     * Resolves the symlink to its target entry.
     * May return null if the target doesn't exist.
     * May throw if circular symlinks are detected.
     */
    suspend fun resolve(): Entry?
}

/**
 * Entry representing an error that occurred during enumeration.
 * Used to continue scanning even when some entries fail.
 *
 * Go type: fs.ErrorEntry
 */
interface ErrorEntry : Entry {
    /**
     * The error that occurred.
     */
    val error: Throwable
}

/**
 * Summary statistics for a directory tree.
 *
 * Go type: fs.DirectorySummary
 */
data class DirectorySummary(
    val totalFileSize: Long = 0,
    val totalFileCount: Long = 0,
    val totalSymlinkCount: Long = 0,
    val totalDirCount: Long = 0,
    val maxModTime: Instant = Instant.EPOCH,
    val fatalErrorCount: Int = 0,
    val ignoredErrorCount: Int = 0,
    val failedEntries: List<EntryWithError> = emptyList()
) {
    /**
     * Combines this summary with another.
     */
    operator fun plus(other: DirectorySummary): DirectorySummary = DirectorySummary(
        totalFileSize = totalFileSize + other.totalFileSize,
        totalFileCount = totalFileCount + other.totalFileCount,
        totalSymlinkCount = totalSymlinkCount + other.totalSymlinkCount,
        totalDirCount = totalDirCount + other.totalDirCount,
        maxModTime = maxOf(maxModTime, other.maxModTime),
        fatalErrorCount = fatalErrorCount + other.fatalErrorCount,
        ignoredErrorCount = ignoredErrorCount + other.ignoredErrorCount,
        failedEntries = (failedEntries + other.failedEntries).take(MAX_FAILED_ENTRIES)
    )

    companion object {
        const val MAX_FAILED_ENTRIES = 10
        val EMPTY = DirectorySummary()
    }
}

/**
 * Entry with associated error information.
 */
data class EntryWithError(
    val entryPath: String,
    val error: Throwable
)
