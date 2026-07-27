package org.kopiaKt.snapshot.restore

import org.kopiaKt.snapshot.model.DirEntry
import java.io.Closeable
import java.io.InputStream

/**
 * Callback to report progress during file restoration.
 *
 * Go type: restore.FileWriteProgress
 */
typealias FileWriteProgress = (bytesWritten: Long) -> Unit

/**
 * Output interface for restore operations.
 *
 * Implementations handle actual restoration to various destinations
 * (local filesystem, tar archive, zip archive, etc.).
 *
 * Go type: restore.Output
 */
interface RestoreOutput : Closeable {
    /**
     * Whether this output supports parallel writes.
     *
     * If false, restore will use a single worker thread.
     */
    fun parallelizable(): Boolean

    /**
     * Called when beginning restoration of a directory.
     *
     * This is called before any children are restored.
     * The implementation should create the directory if needed.
     *
     * @param relativePath Path relative to the restore root
     * @param entry Directory entry metadata
     */
    suspend fun beginDirectory(relativePath: String, entry: DirEntry)

    /**
     * Called when all children of a directory have been restored.
     *
     * The implementation should finalize the directory (set attributes, etc.).
     *
     * @param relativePath Path relative to the restore root
     * @param entry Directory entry metadata
     */
    suspend fun finishDirectory(relativePath: String, entry: DirEntry)

    /**
     * Writes directory entry metadata for shallow restores.
     *
     * Used when restoring directory placeholders instead of full content.
     *
     * @param relativePath Path relative to the restore root
     * @param entry Directory entry metadata
     */
    suspend fun writeDirEntry(relativePath: String, entry: DirEntry)

    /**
     * Writes a file to the output.
     *
     * @param relativePath Path relative to the restore root
     * @param entry File entry metadata
     * @param reader Stream containing file data
     * @param progressCallback Optional callback for progress reporting
     */
    suspend fun writeFile(
        relativePath: String,
        entry: DirEntry,
        reader: InputStream,
        progressCallback: FileWriteProgress? = null,
    )

    /**
     * Checks if a file already exists with matching metadata.
     *
     * Used for incremental restore to skip unchanged files.
     *
     * @param relativePath Path relative to the restore root
     * @param entry Expected file entry metadata
     * @return true if file exists and matches, false otherwise
     */
    suspend fun fileExists(relativePath: String, entry: DirEntry): Boolean

    /**
     * Creates a symbolic link.
     *
     * @param relativePath Path relative to the restore root
     * @param entry Symlink entry metadata
     * @param target The symlink target path
     */
    suspend fun createSymlink(relativePath: String, entry: DirEntry, target: String): Boolean

    /**
     * Checks if a symlink already exists with matching target.
     *
     * Used for incremental restore to skip unchanged symlinks.
     *
     * @param relativePath Path relative to the restore root
     * @param entry Expected symlink entry metadata
     * @param target Expected target path
     * @return true if symlink exists with correct target, false otherwise
     */
    suspend fun symlinkExists(relativePath: String, entry: DirEntry, target: String): Boolean

    /**
     * Closes the output and releases resources.
     */
    override fun close()
}
