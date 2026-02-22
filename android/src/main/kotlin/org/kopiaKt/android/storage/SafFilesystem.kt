package org.kopiaKt.android.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.DirectoryIterator
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.OwnerInfo
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import org.kopiaKt.snapshot.fs.File as FsFile

/**
 * Abstraction over DocumentFile operations for testability.
 *
 * The real Android DocumentFile API is difficult to mock directly,
 * so this interface provides a thin wrapper that can be faked in tests.
 */
interface DocumentFileProvider {
    fun getName(): String?
    fun getUri(): Uri
    fun isDirectory(): Boolean
    fun isFile(): Boolean
    fun length(): Long
    fun lastModified(): Long
    fun listFiles(): List<DocumentFileProvider>?
    fun findFile(name: String): DocumentFileProvider?
}

/**
 * Abstraction over ContentResolver for testability.
 */
interface ContentResolverProvider {
    fun openInputStream(uri: Uri): InputStream
}

/**
 * Production implementation wrapping a real DocumentFile.
 */
class RealDocumentFileProvider(private val docFile: DocumentFile) : DocumentFileProvider {
    override fun getName(): String? = docFile.name
    override fun getUri(): Uri = docFile.uri
    override fun isDirectory(): Boolean = docFile.isDirectory
    override fun isFile(): Boolean = docFile.isFile
    override fun length(): Long = docFile.length()
    override fun lastModified(): Long = docFile.lastModified()

    override fun listFiles(): List<DocumentFileProvider>? {
        val files = docFile.listFiles()
        return files.map { RealDocumentFileProvider(it) }
    }

    override fun findFile(name: String): DocumentFileProvider? {
        return docFile.findFile(name)?.let { RealDocumentFileProvider(it) }
    }
}

/**
 * Production ContentResolverProvider wrapping an Android Context.
 */
class RealContentResolverProvider(private val context: Context) : ContentResolverProvider {
    override fun openInputStream(uri: Uri): InputStream {
        return context.contentResolver.openInputStream(uri)
            ?: throw IOException("ContentResolver returned null for URI: $uri")
    }
}

/**
 * SAF filesystem adapter.
 *
 * Bridges Android's Storage Access Framework (DocumentFile / Uri)
 * to KopiaKt's snapshot filesystem interfaces (Directory, File, Entry).
 *
 * This allows SnapshotUploader to back up user-selected folders
 * accessed through SAF tree URIs.
 */
object SafFilesystem {

    /**
     * Creates a Directory entry from a SAF tree URI.
     *
     * @param context Android context for ContentResolver access
     * @param treeUri Tree URI obtained from ACTION_OPEN_DOCUMENT_TREE
     * @return Directory entry for backup operations
     * @throws IllegalArgumentException if the URI is invalid or does not point to a directory
     */
    fun directory(context: Context, treeUri: Uri): Directory {
        val docFile = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Invalid tree URI: $treeUri")

        require(docFile.isDirectory) {
            "URI does not point to a directory: $treeUri"
        }

        val provider = RealDocumentFileProvider(docFile)
        val contentResolver = RealContentResolverProvider(context)
        return SafDirectory(provider, contentResolver)
    }

    /**
     * Creates a Directory entry from a DocumentFileProvider (for testing).
     */
    internal fun directory(provider: DocumentFileProvider, contentResolver: ContentResolverProvider): Directory {
        return SafDirectory(provider, contentResolver)
    }
}

/**
 * Base class for SAF filesystem entries.
 */
abstract class SafEntry(
    protected val provider: DocumentFileProvider,
    protected val contentResolverProvider: ContentResolverProvider
) : Entry {

    override val name: String
        get() = provider.getName() ?: ""

    override val modTime: Instant
        get() = Instant.ofEpochMilli(provider.lastModified())

    override val owner: OwnerInfo
        get() = OwnerInfo.EMPTY

    override val device: DeviceInfo
        get() = DeviceInfo.EMPTY

    override val localFilesystemPath: String
        get() = ""

    override fun toString(): String = "SafEntry(name=$name, type=$type, uri=${provider.getUri()})"
}

/**
 * SAF directory implementing the snapshot Directory interface.
 *
 * Iterates children via DocumentFileProvider.listFiles().
 */
class SafDirectory(
    provider: DocumentFileProvider,
    contentResolverProvider: ContentResolverProvider
) : SafEntry(provider, contentResolverProvider), Directory {

    override val type: EntryType = EntryType.DIRECTORY

    override val size: Long = 0L

    override val mode: Int = 0b111101101 // 0755 rwxr-xr-x

    override suspend fun child(name: String): Entry? {
        val childProvider = provider.findFile(name) ?: return null
        return createEntry(childProvider, contentResolverProvider)
    }

    override suspend fun iterate(): DirectoryIterator {
        val children = try {
            provider.listFiles() ?: emptyList()
        } catch (e: Exception) {
            throw IOException("Failed to list directory contents: ${provider.getName()}", e)
        }
        return SafDirectoryIterator(children, contentResolverProvider)
    }

    override fun supportsMultipleIterations(): Boolean = true
}

/**
 * SAF file implementing the snapshot File interface.
 *
 * Opens the file for reading via ContentResolverProvider.
 */
class SafFile(
    provider: DocumentFileProvider,
    contentResolverProvider: ContentResolverProvider
) : SafEntry(provider, contentResolverProvider), FsFile {

    override val type: EntryType = EntryType.FILE

    override val size: Long
        get() = provider.length()

    override val mode: Int = 0b110100100 // 0644 rw-r--r--

    override suspend fun open(): InputStream {
        return contentResolverProvider.openInputStream(provider.getUri())
    }
}

/**
 * Iterator over SAF directory contents.
 */
class SafDirectoryIterator(
    private val children: List<DocumentFileProvider>,
    private val contentResolverProvider: ContentResolverProvider
) : DirectoryIterator {

    private var index = 0

    override suspend fun next(): Entry? {
        if (index >= children.size) return null
        val childProvider = children[index++]
        return createEntry(childProvider, contentResolverProvider)
    }

    override fun close() {
        // No resources to release for SAF iteration
    }
}

/**
 * Factory function to create the appropriate Entry subtype from a DocumentFileProvider.
 */
private fun createEntry(
    provider: DocumentFileProvider,
    contentResolverProvider: ContentResolverProvider
): Entry {
    return when {
        provider.isDirectory() -> SafDirectory(provider, contentResolverProvider)
        provider.isFile() -> SafFile(provider, contentResolverProvider)
        else -> SafUnknownEntry(provider, contentResolverProvider)
    }
}

/**
 * Entry for unknown SAF entry types (neither file nor directory).
 */
private class SafUnknownEntry(
    provider: DocumentFileProvider,
    contentResolverProvider: ContentResolverProvider
) : SafEntry(provider, contentResolverProvider) {

    override val type: EntryType = EntryType.UNKNOWN

    override val size: Long
        get() = provider.length()

    override val mode: Int = 0
}
