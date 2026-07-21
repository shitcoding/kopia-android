package org.kopiaKt.snapshot.restore

import org.kopiaKt.snapshot.model.DirEntry
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

/**
 * Options for filesystem restore output.
 *
 * Go type: restore.FilesystemOutput
 */
data class FilesystemOutputOptions(
    /**
     * If true, overwrite existing directories.
     * If false, error when a non-empty directory exists.
     */
    val overwriteDirectories: Boolean = false,

    /**
     * If true, overwrite existing files.
     * If false, error when a file already exists.
     */
    val overwriteFiles: Boolean = false,

    /**
     * If true, overwrite existing symlinks.
     * If false, error when a symlink already exists.
     */
    val overwriteSymlinks: Boolean = false,

    /**
     * If true, ignore permission errors during attribute setting.
     */
    val ignorePermissionErrors: Boolean = false,

    /**
     * If true, write files atomically (temp file + rename).
     */
    val writeFilesAtomically: Boolean = false,

    /**
     * If true, skip setting owner information (uid/gid).
     */
    val skipOwners: Boolean = false,

    /**
     * If true, skip setting file permissions.
     */
    val skipPermissions: Boolean = false,

    /**
     * If true, skip setting modification times.
     */
    val skipTimes: Boolean = false,

    /**
     * If true, flush files to disk after writing.
     */
    val flushFiles: Boolean = false,
)

/**
 * Restores files to the local filesystem.
 *
 * Go type: restore.FilesystemOutput
 */
class FilesystemOutput(
    /**
     * Root path where files will be restored.
     */
    val targetPath: Path,
    private val options: FilesystemOutputOptions = FilesystemOutputOptions(),
) : RestoreOutput {

    companion object {
        private const val OUTPUT_DIR_MODE = 448 // 0o700 in octal
        private val MAX_TIME_DELTA = Duration.ofSeconds(2)

        // OS check for platform-specific behavior
        private val IS_POSIX = !System.getProperty("os.name").lowercase().contains("windows")
    }

    override fun parallelizable(): Boolean = true

    /**
     * Validates that the resolved path does not escape the target restore directory.
     * This prevents path traversal attacks from maliciously crafted snapshot entries,
     * including symlink-in-path escapes where an earlier restored symlink redirects
     * writes outside the restore root.
     */
    private fun validatePath(relativePath: String): Path {
        val resolved = targetPath.resolve(relativePath).normalize()
        val normalizedTarget = targetPath.normalize()
        if (!resolved.startsWith(normalizedTarget)) {
            throw RestoreException(
                "Path traversal detected: '$relativePath' resolves to '$resolved' which is outside restore root '$normalizedTarget'",
            )
        }

        // Check for symlink components that could redirect writes outside the root.
        // Walk existing path components from the root toward the target.
        var checkPath = normalizedTarget
        val relativePart = normalizedTarget.relativize(resolved)
        for (component in relativePart) {
            checkPath = checkPath.resolve(component)
            if (!checkPath.exists(LinkOption.NOFOLLOW_LINKS)) break
            if (checkPath.isSymbolicLink()) {
                // Use toRealPath to canonically resolve the symlink target.
                // For dangling symlinks (target doesn't exist), toRealPath throws
                // NoSuchFileException — these are safe since they can't redirect writes.
                try {
                    val realPath = checkPath.toRealPath()
                    val realRoot = normalizedTarget.toRealPath()
                    if (!realPath.startsWith(realRoot)) {
                        throw RestoreException(
                            "Symlink-in-path traversal detected: '$checkPath' is a symlink resolving to '$realPath' which is outside restore root",
                        )
                    }
                } catch (_: java.nio.file.NoSuchFileException) {
                    // Dangling symlink — target doesn't exist, can't redirect writes.
                    // Safe to skip for leaf symlinks being restored.
                }
            }
        }

        return resolved
    }

    override suspend fun beginDirectory(relativePath: String, entry: DirEntry) {
        val path = validatePath(relativePath)
        createDirectory(path)
    }

    override suspend fun finishDirectory(relativePath: String, entry: DirEntry) {
        val path = validatePath(relativePath)
        setAttributes(path, entry)
    }

    override suspend fun writeDirEntry(relativePath: String, entry: DirEntry) {
        // Used for shallow restores - currently no-op like in Go
    }

    override suspend fun writeFile(
        relativePath: String,
        entry: DirEntry,
        reader: InputStream,
        progressCallback: FileWriteProgress?,
    ) {
        val path = validatePath(relativePath)

        // Check if file exists
        if (path.exists(LinkOption.NOFOLLOW_LINKS)) {
            if (!options.overwriteFiles) {
                throw RestoreException("File already exists and overwrite is disabled: $path")
            }
        }

        // Ensure parent directory exists
        val parentDir = path.parent
        if (parentDir != null && !parentDir.exists()) {
            Files.createDirectories(parentDir)
        }

        if (options.writeFilesAtomically) {
            writeFileAtomically(path, entry, reader, progressCallback)
        } else {
            writeFileDirect(path, entry, reader, progressCallback)
        }

        setAttributes(path, entry)
    }

    private fun writeFileDirect(
        path: Path,
        entry: DirEntry,
        reader: InputStream,
        progressCallback: FileWriteProgress?,
    ) {
        val openOptions = arrayOf(
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )

        Files.newOutputStream(path, *openOptions).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int

            while (reader.read(buffer).also { bytesRead = it } >= 0) {
                output.write(buffer, 0, bytesRead)
                progressCallback?.invoke(bytesRead.toLong())
            }

            if (options.flushFiles) {
                output.flush()
            }
        }
    }

    private fun writeFileAtomically(
        path: Path,
        entry: DirEntry,
        reader: InputStream,
        progressCallback: FileWriteProgress?,
    ) {
        val tempFile = Files.createTempFile(path.parent, ".kopia-restore-", ".tmp")
        try {
            Files.newOutputStream(tempFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int

                while (reader.read(buffer).also { bytesRead = it } >= 0) {
                    output.write(buffer, 0, bytesRead)
                    progressCallback?.invoke(bytesRead.toLong())
                }

                if (options.flushFiles) {
                    output.flush()
                }
            }

            // Atomic rename
            Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // Clean up temp file on error
            try {
                Files.deleteIfExists(tempFile)
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
            throw e
        }
    }

    override suspend fun fileExists(relativePath: String, entry: DirEntry): Boolean {
        val path = validatePath(relativePath)

        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) {
            return false
        }

        // Must be a regular file
        if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            return false
        }

        // Check size
        if (path.fileSize() != entry.fileSize) {
            return false
        }

        // Check modification time
        val fileTime = path.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS)
        val entryTime = entry.modTime ?: return false

        val timeDelta = Duration.between(fileTime.toInstant(), entryTime).abs()
        return timeDelta < MAX_TIME_DELTA
    }

    override suspend fun createSymlink(relativePath: String, entry: DirEntry, target: String) {
        val path = validatePath(relativePath)

        // Validate relative symlink targets don't escape the restore root.
        // Absolute symlink targets are allowed since they only store metadata
        // (the symlink file itself is inside the restore root).
        val targetPath = Path.of(target)
        if (!targetPath.isAbsolute) {
            val normalizedRoot = this.targetPath.normalize()
            val resolvedTarget = path.parent.resolve(target).normalize()
            if (!resolvedTarget.startsWith(normalizedRoot)) {
                throw RestoreException(
                    "Symlink target traversal detected: target '$target' from '$relativePath' resolves outside restore root",
                )
            }
        }

        // Check if path exists
        if (path.exists(LinkOption.NOFOLLOW_LINKS)) {
            if (path.isSymbolicLink()) {
                if (!options.overwriteSymlinks) {
                    throw RestoreException("Symlink already exists and overwrite is disabled: $path")
                }
                // Remove existing symlink
                Files.delete(path)
            } else {
                throw RestoreException("Cannot create symlink, path exists and is not a symlink: $path")
            }
        }

        // Ensure parent directory exists
        val parentDir = path.parent
        if (parentDir != null && !parentDir.exists()) {
            Files.createDirectories(parentDir)
        }

        Files.createSymbolicLink(path, Path.of(target))
        setSymlinkAttributes(path, entry)
    }

    override suspend fun symlinkExists(relativePath: String, entry: DirEntry, target: String): Boolean {
        val path = validatePath(relativePath)

        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) {
            return false
        }

        if (!path.isSymbolicLink()) {
            return false
        }

        // Check target matches
        val existingTarget = path.readSymbolicLink().toString()
        return existingTarget == target
    }

    override fun close() {
        // Nothing to close for filesystem output
    }

    private fun createDirectory(path: Path) {
        when {
            !path.exists() -> {
                Files.createDirectories(path)
            }
            path.isDirectory() -> {
                if (!options.overwriteDirectories) {
                    val isEmpty = Files.list(path).use { it.findFirst().isEmpty }
                    if (!isEmpty) {
                        throw RestoreException(
                            "Non-empty directory already exists and overwrite is disabled: $path",
                        )
                    }
                }
                // Directory exists, proceed
            }
            else -> {
                throw RestoreException(
                    "Cannot create directory, path exists and is not a directory: $path",
                )
            }
        }
    }

    private fun setAttributes(path: Path, entry: DirEntry) {
        try {
            // Set permissions (POSIX only)
            if (IS_POSIX && !options.skipPermissions && entry.permissions != 0) {
                try {
                    val permissions = permissionsFromMode(entry.permissions)
                    Files.setPosixFilePermissions(path, permissions)
                } catch (e: UnsupportedOperationException) {
                    // Not a POSIX filesystem, skip
                } catch (e: SecurityException) {
                    if (!options.ignorePermissionErrors) throw e
                }
            }

            // Set owner (POSIX only, requires root)
            if (IS_POSIX && !options.skipOwners) {
                try {
                    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
                    if (view != null) {
                        // Note: Setting owner typically requires root privileges
                        // Skip for now as it requires UserPrincipalLookupService
                    }
                } catch (_: UnsupportedOperationException) {
                    // Not supported, skip
                } catch (e: SecurityException) {
                    if (!options.ignorePermissionErrors) throw e
                }
            }

            // Set modification time
            if (!options.skipTimes && entry.modTime != null) {
                try {
                    Files.setLastModifiedTime(path, FileTime.from(entry.modTime))
                } catch (e: SecurityException) {
                    if (!options.ignorePermissionErrors) throw e
                }
            }
        } catch (e: Exception) {
            if (!options.ignorePermissionErrors) {
                throw RestoreException("Failed to set attributes on $path: ${e.message}", e)
            }
        }
    }

    private fun setSymlinkAttributes(path: Path, entry: DirEntry) {
        // Setting attributes on symlinks is platform-specific and limited
        // Most systems don't support setting permissions on symlinks
        // Skip for now as Go also has limited support here
    }

    private fun permissionsFromMode(mode: Int): Set<PosixFilePermission> {
        val permissions = mutableSetOf<PosixFilePermission>()

        // Owner permissions
        if ((mode and 0b100_000_000) != 0) permissions.add(PosixFilePermission.OWNER_READ)
        if ((mode and 0b010_000_000) != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
        if ((mode and 0b001_000_000) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)

        // Group permissions
        if ((mode and 0b000_100_000) != 0) permissions.add(PosixFilePermission.GROUP_READ)
        if ((mode and 0b000_010_000) != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
        if ((mode and 0b000_001_000) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)

        // Others permissions
        if ((mode and 0b000_000_100) != 0) permissions.add(PosixFilePermission.OTHERS_READ)
        if ((mode and 0b000_000_010) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
        if ((mode and 0b000_000_001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)

        return permissions
    }
}

/**
 * Exception thrown during restore operations.
 */
class RestoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
