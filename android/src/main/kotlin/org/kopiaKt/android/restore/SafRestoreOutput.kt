package org.kopiaKt.android.restore

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.restore.FileWriteProgress
import org.kopiaKt.snapshot.restore.RestoreOutput
import java.io.InputStream

/**
 * RestoreOutput implementation that writes to Android Storage Access Framework (SAF) URIs.
 *
 * This allows restoring files to any user-selected directory, including:
 * - Download folder
 * - Documents folder
 * - External storage locations
 * - USB drives
 * - Cloud storage providers
 */
class SafRestoreOutput(
    private val context: Context,
    private val rootUri: Uri
) : RestoreOutput {

    private val contentResolver: ContentResolver = context.contentResolver
    private val rootDocument: DocumentFile = DocumentFile.fromTreeUri(context, rootUri)
        ?: throw IllegalArgumentException("Invalid root URI: $rootUri")

    // Cache of created directories to avoid repeated lookups
    private val directoryCache = mutableMapOf<String, DocumentFile>()

    // Track root directory name when restoring a single directory (not snapshot root)
    private var rootDirectoryName: String? = null

    init {
        directoryCache[""] = rootDocument
    }

    override fun parallelizable(): Boolean = false // SAF is not thread-safe for writes

    override suspend fun beginDirectory(relativePath: String, entry: DirEntry) {
        withContext(Dispatchers.IO) {
            // When restoring a single directory (first call with empty path), preserve its name
            if (relativePath.isEmpty() && rootDirectoryName == null) {
                rootDirectoryName = entry.name
            }

            val effectivePath = mapPath(relativePath, entry.name)
            getOrCreateDirectory(effectivePath)
        }
    }

    override suspend fun finishDirectory(relativePath: String, entry: DirEntry) {
        // SAF doesn't support setting directory metadata after creation
    }

    override suspend fun writeDirEntry(relativePath: String, entry: DirEntry) {
        withContext(Dispatchers.IO) {
            val effectivePath = mapPath(relativePath, entry.name)
            getOrCreateDirectory(effectivePath)
        }
    }

    override suspend fun writeFile(
        relativePath: String,
        entry: DirEntry,
        reader: InputStream,
        progressCallback: FileWriteProgress?
    ) {
        withContext(Dispatchers.IO) {
            val effectivePath = mapPath(relativePath, entry.name)

            val parentPath = effectivePath.substringBeforeLast('/', "")
            val fileName = effectivePath.substringAfterLast('/')

            val parentDir = getOrCreateDirectory(parentPath)

            // Check if file already exists and delete it
            parentDir.findFile(fileName)?.delete()

            // Determine MIME type from extension
            val mimeType = getMimeType(fileName)

            // Create new file
            val newFile = parentDir.createFile(mimeType, fileName)
                ?: throw IllegalStateException("Failed to create file: $relativePath")

            // Write content
            contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytesWritten = 0L
                var bytesRead: Int

                while (reader.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead
                    progressCallback?.invoke(bytesRead.toLong())
                }
            } ?: throw IllegalStateException("Failed to open output stream for: $relativePath")
        }
    }

    override suspend fun fileExists(relativePath: String, entry: DirEntry): Boolean {
        return withContext(Dispatchers.IO) {
            val effectivePath = mapPath(relativePath, entry.name)
            val parentPath = effectivePath.substringBeforeLast('/', "")
            val fileName = effectivePath.substringAfterLast('/')

            val parentDir = directoryCache[parentPath]
                ?: getDirectoryIfExists(parentPath)
                ?: return@withContext false

            val existingFile = parentDir.findFile(fileName)
            if (existingFile == null || !existingFile.isFile) {
                return@withContext false
            }

            // Check size match for incremental restore
            existingFile.length() == entry.fileSize
        }
    }

    override suspend fun createSymlink(relativePath: String, entry: DirEntry, target: String) {
        // SAF doesn't support symlinks - skip or create as regular file
        // For now, we'll skip symlinks silently
    }

    override suspend fun symlinkExists(relativePath: String, entry: DirEntry, target: String): Boolean {
        // SAF doesn't support symlinks
        return false
    }

    override fun close() {
        directoryCache.clear()
        rootDirectoryName = null
    }

    /**
     * Maps a relative path to account for root directory name when restoring a single directory.
     * When restoring a directory (not snapshot root), paths need to be prefixed with the directory name.
     */
    private fun mapPath(relativePath: String, entryName: String): String {
        return when {
            // Empty path on first beginDirectory - use entry name as root
            relativePath.isEmpty() && rootDirectoryName != null -> rootDirectoryName!!
            // Empty path in other contexts (e.g., single file) - use entry name
            relativePath.isEmpty() -> entryName
            // Path already includes root directory - use as-is
            rootDirectoryName != null && relativePath.startsWith("$rootDirectoryName/") -> relativePath
            // Child path needs root directory prepended
            rootDirectoryName != null -> "$rootDirectoryName/$relativePath"
            // No root directory tracking - use as-is
            else -> relativePath
        }
    }

    private fun getOrCreateDirectory(relativePath: String): DocumentFile {
        if (relativePath.isEmpty()) {
            return rootDocument
        }

        directoryCache[relativePath]?.let { return it }

        val parts = relativePath.split('/')
        var currentDir = rootDocument
        var currentPath = ""

        for (part in parts) {
            if (part.isEmpty()) continue

            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"

            currentDir = directoryCache[currentPath] ?: run {
                // Try to find existing directory
                val existingDir = currentDir.findFile(part)
                if (existingDir != null && existingDir.isDirectory) {
                    existingDir
                } else {
                    // Create new directory
                    currentDir.createDirectory(part)
                        ?: throw IllegalStateException("Failed to create directory: $currentPath")
                }
            }

            directoryCache[currentPath] = currentDir
        }

        return currentDir
    }

    private fun getDirectoryIfExists(relativePath: String): DocumentFile? {
        if (relativePath.isEmpty()) {
            return rootDocument
        }

        directoryCache[relativePath]?.let { return it }

        val parts = relativePath.split('/')
        var currentDir = rootDocument

        for (part in parts) {
            if (part.isEmpty()) continue

            val child = currentDir.findFile(part)
            if (child == null || !child.isDirectory) {
                return null
            }
            currentDir = child
        }

        return currentDir
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "gz", "gzip" -> "application/gzip"
            "tar" -> "application/x-tar"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
    }
}
