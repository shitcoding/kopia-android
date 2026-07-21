package org.kopiaKt.android.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
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
 * Production [DocumentFileProvider] backed by a `DocumentsContract.queryChildDocuments` cursor.
 *
 * `DocumentFile.listFiles()` issues a separate `ContentResolver` query per child, and each metadata getter
 * (`name`/`length`/`isDirectory`) issues ANOTHER query per call, so enumerating a directory of N children
 * costs O(N * properties) IPC round-trips. `queryChildDocuments` returns every child's name/type/size/mtime
 * in a single cursor, so listing a directory is ONE round-trip and the per-child getters are field reads.
 * Metadata is materialized from the cursor row; only [listFiles]/[findFile] touch the provider again.
 */
internal class SafCursorDocument(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val documentId: String,
    private val displayName: String?,
    private val mimeType: String?,
    private val size: Long,
    private val modified: Long,
) : DocumentFileProvider {

    override fun getName(): String? = displayName
    override fun getUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    override fun isDirectory(): Boolean = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

    // Mirror DocumentFile.isFile: an empty/absent MIME is NOT a file (it becomes a SafUnknownEntry that is
    // recorded but never opened), so a bogus row isn't turned into a per-file open() error.
    override fun isFile(): Boolean = !mimeType.isNullOrEmpty() && mimeType != DocumentsContract.Document.MIME_TYPE_DIR
    override fun length(): Long = size
    override fun lastModified(): Long = modified

    override fun listFiles(): List<DocumentFileProvider> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = mutableListOf<DocumentFileProvider>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            // Only DOCUMENT_ID is required (a child is unusable without it). Providers are not obliged to
            // honor the projection, so the other columns are read defensively (missing -> DocumentFile's
            // same 0/null defaults) instead of throwing and failing the whole directory.
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                children.add(
                    SafCursorDocument(
                        resolver = resolver,
                        treeUri = treeUri,
                        documentId = cursor.getString(idIdx),
                        displayName = cursor.stringOrNull(nameIdx),
                        mimeType = cursor.stringOrNull(mimeIdx),
                        size = cursor.longOrZero(sizeIdx),
                        modified = cursor.longOrZero(modIdx),
                    ),
                )
            }
        }
        return children
    }

    override fun findFile(name: String): DocumentFileProvider? = listFiles().firstOrNull { it.getName() == name }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * Root provider for the [rootDocFile] resolved from a picked tree URI. The root's own name/mtime
         * come from [rootDocFile] (one query, for the root only); its children then stream from the cursor.
         * The document id is taken from [DocumentFile.getUri] (always a document-under-tree URI) so it
         * matches the exact node validated by the caller -- `getTreeDocumentId(treeUri)` can point at a
         * DIFFERENT node when the tree URI carries a `/document/` segment.
         */
        fun forTree(context: Context, treeUri: Uri, rootDocFile: DocumentFile): SafCursorDocument = SafCursorDocument(
            resolver = context.contentResolver,
            treeUri = treeUri,
            documentId = DocumentsContract.getDocumentId(rootDocFile.uri),
            displayName = rootDocFile.name,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            size = 0L,
            modified = rootDocFile.lastModified(),
        )
    }
}

private fun Cursor.stringOrNull(index: Int): String? = if (index >= 0 && !isNull(index)) getString(index) else null

private fun Cursor.longOrZero(index: Int): Long = if (index >= 0 && !isNull(index)) getLong(index) else 0L

/**
 * Production ContentResolverProvider wrapping an Android Context.
 */
class RealContentResolverProvider(private val context: Context) : ContentResolverProvider {
    override fun openInputStream(uri: Uri): InputStream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("ContentResolver returned null for URI: $uri")
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

        val provider = SafCursorDocument.forTree(context, treeUri, docFile)
        val contentResolver = RealContentResolverProvider(context)
        return SafDirectory(provider, contentResolver)
    }

    /**
     * Creates a Directory entry from a DocumentFileProvider (for testing).
     */
    internal fun directory(provider: DocumentFileProvider, contentResolver: ContentResolverProvider): Directory = SafDirectory(provider, contentResolver)
}

/**
 * Base class for SAF filesystem entries.
 */
abstract class SafEntry(
    protected val provider: DocumentFileProvider,
    protected val contentResolverProvider: ContentResolverProvider,
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
    contentResolverProvider: ContentResolverProvider,
) : SafEntry(provider, contentResolverProvider),
    Directory {

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
    contentResolverProvider: ContentResolverProvider,
) : SafEntry(provider, contentResolverProvider),
    FsFile {

    override val type: EntryType = EntryType.FILE

    override val size: Long
        get() = provider.length()

    override val mode: Int = 0b110100100 // 0644 rw-r--r--

    override suspend fun open(): InputStream = contentResolverProvider.openInputStream(provider.getUri())
}

/**
 * Iterator over SAF directory contents.
 */
class SafDirectoryIterator(
    private val children: List<DocumentFileProvider>,
    private val contentResolverProvider: ContentResolverProvider,
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
    contentResolverProvider: ContentResolverProvider,
): Entry = when {
    provider.isDirectory() -> SafDirectory(provider, contentResolverProvider)
    provider.isFile() -> SafFile(provider, contentResolverProvider)
    else -> SafUnknownEntry(provider, contentResolverProvider)
}

/**
 * Entry for unknown SAF entry types (neither file nor directory).
 */
private class SafUnknownEntry(
    provider: DocumentFileProvider,
    contentResolverProvider: ContentResolverProvider,
) : SafEntry(provider, contentResolverProvider) {

    override val type: EntryType = EntryType.UNKNOWN

    override val size: Long
        get() = provider.length()

    override val mode: Int = 0
}
