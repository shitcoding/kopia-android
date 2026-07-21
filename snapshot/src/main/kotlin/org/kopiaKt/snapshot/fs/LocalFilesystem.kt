package org.kopiaKt.snapshot.fs

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.readSymbolicLink

/**
 * Local filesystem implementation.
 * Provides access to the local filesystem for backup operations.
 *
 * Go type: localfs.filesystemEntry
 */
object LocalFilesystem {

    /**
     * Creates an Entry from a local filesystem path.
     *
     * @param path The path to create an entry for
     * @param followSymlinks Whether to follow symlinks when reading metadata
     * @return The filesystem entry
     * @throws java.io.IOException if the path cannot be accessed
     */
    fun entry(path: Path, followSymlinks: Boolean = false): Entry {
        val linkOptions = if (followSymlinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)

        return try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, *linkOptions)
            val owner = readOwner(path, linkOptions)
            val device = readDevice(path)

            when {
                attrs.isSymbolicLink -> LocalSymlink(path, attrs, owner, device)
                attrs.isDirectory -> LocalDirectory(path, attrs, owner, device)
                attrs.isRegularFile -> LocalFile(path, attrs, owner, device)
                else -> LocalUnknownEntry(path, attrs, owner, device)
            }
        } catch (e: Exception) {
            LocalErrorEntry(path, e)
        }
    }

    /**
     * Creates a Directory entry from a local filesystem path.
     *
     * @param path The directory path
     * @return The directory entry
     * @throws IllegalArgumentException if the path is not a directory
     */
    fun directory(path: Path): Directory {
        require(path.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            "Path is not a directory: $path"
        }
        return entry(path) as Directory
    }

    private fun readOwner(path: Path, linkOptions: Array<LinkOption>): OwnerInfo = try {
        // Read numeric UID/GID via the unix file attribute view.
        // This is available on Unix/macOS and returns actual numeric IDs
        // that Go Kopia expects (uint32 in the DirEntry JSON).
        val attrs = Files.readAttributes(path, "unix:uid,gid", *linkOptions)
        val uid = attrs["uid"] as? Int ?: 0
        val gid = attrs["gid"] as? Int ?: 0
        OwnerInfo(userId = uid, groupId = gid)
    } catch (e: UnsupportedOperationException) {
        // Windows or other non-POSIX filesystem
        OwnerInfo.EMPTY
    } catch (e: IllegalArgumentException) {
        // unix attribute view not available
        OwnerInfo.EMPTY
    } catch (e: Exception) {
        OwnerInfo.EMPTY
    }

    private fun readDevice(path: Path): DeviceInfo = try {
        // Java doesn't directly expose device IDs
        // On Unix, we could use native calls, but for portability we use a hash
        val fileStore = Files.getFileStore(path)
        DeviceInfo(dev = fileStore.name().hashCode().toLong())
    } catch (e: Exception) {
        DeviceInfo.EMPTY
    }

    private fun modeFromPosixPermissions(perms: Set<PosixFilePermission>): Int {
        var mode = 0
        if (PosixFilePermission.OWNER_READ in perms) mode = mode or 0b100000000
        if (PosixFilePermission.OWNER_WRITE in perms) mode = mode or 0b010000000
        if (PosixFilePermission.OWNER_EXECUTE in perms) mode = mode or 0b001000000
        if (PosixFilePermission.GROUP_READ in perms) mode = mode or 0b000100000
        if (PosixFilePermission.GROUP_WRITE in perms) mode = mode or 0b000010000
        if (PosixFilePermission.GROUP_EXECUTE in perms) mode = mode or 0b000001000
        if (PosixFilePermission.OTHERS_READ in perms) mode = mode or 0b000000100
        if (PosixFilePermission.OTHERS_WRITE in perms) mode = mode or 0b000000010
        if (PosixFilePermission.OTHERS_EXECUTE in perms) mode = mode or 0b000000001
        return mode
    }
}

/**
 * Base class for local filesystem entries.
 */
private abstract class LocalEntry(
    protected val path: Path,
    protected val attrs: BasicFileAttributes,
    override val owner: OwnerInfo,
    override val device: DeviceInfo,
) : Entry {

    override val name: String = path.name

    override val size: Long = attrs.size()

    override val modTime: Instant = attrs.lastModifiedTime().toInstant()

    override val mode: Int by lazy {
        try {
            val posixAttrs = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            var m = 0
            val perms = posixAttrs.permissions()
            if (PosixFilePermission.OWNER_READ in perms) m = m or 0b100000000
            if (PosixFilePermission.OWNER_WRITE in perms) m = m or 0b010000000
            if (PosixFilePermission.OWNER_EXECUTE in perms) m = m or 0b001000000
            if (PosixFilePermission.GROUP_READ in perms) m = m or 0b000100000
            if (PosixFilePermission.GROUP_WRITE in perms) m = m or 0b000010000
            if (PosixFilePermission.GROUP_EXECUTE in perms) m = m or 0b000001000
            if (PosixFilePermission.OTHERS_READ in perms) m = m or 0b000000100
            if (PosixFilePermission.OTHERS_WRITE in perms) m = m or 0b000000010
            if (PosixFilePermission.OTHERS_EXECUTE in perms) m = m or 0b000000001
            m
        } catch (e: UnsupportedOperationException) {
            // Default permissions for non-POSIX systems
            0b110100100 // 644
        }
    }

    override val localFilesystemPath: String = path.toAbsolutePath().toString()

    override fun toString(): String = "LocalEntry($path, type=$type)"
}

/**
 * Local directory implementation.
 */
private class LocalDirectory(
    path: Path,
    attrs: BasicFileAttributes,
    owner: OwnerInfo,
    device: DeviceInfo,
) : LocalEntry(path, attrs, owner, device),
    Directory {

    override val type: EntryType = EntryType.DIRECTORY

    override val size: Long = 0 // Directories don't have a meaningful size

    override suspend fun child(name: String): Entry? {
        val childPath = path.resolve(name)
        return if (childPath.exists(LinkOption.NOFOLLOW_LINKS)) {
            LocalFilesystem.entry(childPath)
        } else {
            null
        }
    }

    override suspend fun iterate(): DirectoryIterator = LocalDirectoryIterator(path)
}

/**
 * Iterator over local directory contents.
 */
private class LocalDirectoryIterator(
    private val dirPath: Path,
) : DirectoryIterator {

    private val stream = Files.newDirectoryStream(dirPath)
    private val iterator = stream.iterator()

    override suspend fun next(): Entry? = if (iterator.hasNext()) {
        val childPath = iterator.next()
        LocalFilesystem.entry(childPath)
    } else {
        null
    }

    override fun close() {
        stream.close()
    }
}

/**
 * Local file implementation.
 */
private class LocalFile(
    path: Path,
    attrs: BasicFileAttributes,
    owner: OwnerInfo,
    device: DeviceInfo,
) : LocalEntry(path, attrs, owner, device),
    File {

    override val type: EntryType = EntryType.FILE

    override suspend fun open(): InputStream = Files.newInputStream(path)
}

/**
 * Local symbolic link implementation.
 */
private class LocalSymlink(
    path: Path,
    attrs: BasicFileAttributes,
    owner: OwnerInfo,
    device: DeviceInfo,
) : LocalEntry(path, attrs, owner, device),
    Symlink {

    override val type: EntryType = EntryType.SYMLINK

    override val size: Long = 0 // Symlink size is the target path length, not meaningful

    private var resolveDepth = 0

    override suspend fun readlink(): String = path.readSymbolicLink().toString()

    override suspend fun resolve(): Entry? {
        if (resolveDepth >= MAX_SYMLINK_DEPTH) {
            throw SymlinkLoopException("Maximum symlink depth ($MAX_SYMLINK_DEPTH) exceeded for $path")
        }

        val targetPath = path.readSymbolicLink()
        val resolvedPath = if (targetPath.isAbsolute) {
            targetPath
        } else {
            path.parent?.resolve(targetPath)?.normalize() ?: targetPath
        }

        if (!resolvedPath.exists(LinkOption.NOFOLLOW_LINKS)) {
            return null
        }

        val entry = LocalFilesystem.entry(resolvedPath)

        // If the target is also a symlink, continue resolving
        return if (entry is LocalSymlink) {
            entry.resolveDepth = resolveDepth + 1
            entry.resolve()
        } else {
            entry
        }
    }

    companion object {
        const val MAX_SYMLINK_DEPTH = 30
    }
}

/**
 * Entry for unknown file types.
 */
private class LocalUnknownEntry(
    path: Path,
    attrs: BasicFileAttributes,
    owner: OwnerInfo,
    device: DeviceInfo,
) : LocalEntry(path, attrs, owner, device) {

    override val type: EntryType = EntryType.UNKNOWN
}

/**
 * Entry representing an error that occurred while reading filesystem metadata.
 */
private class LocalErrorEntry(
    path: Path,
    override val error: Throwable,
) : Entry,
    ErrorEntry {

    override val name: String = path.name
    override val type: EntryType = EntryType.ERROR
    override val size: Long = 0
    override val modTime: Instant = Instant.EPOCH
    override val mode: Int = 0
    override val owner: OwnerInfo = OwnerInfo.EMPTY
    override val device: DeviceInfo = DeviceInfo.EMPTY
    override val localFilesystemPath: String = path.toAbsolutePath().toString()
}

/**
 * Exception thrown when a circular symlink is detected.
 */
class SymlinkLoopException(message: String) : Exception(message)
