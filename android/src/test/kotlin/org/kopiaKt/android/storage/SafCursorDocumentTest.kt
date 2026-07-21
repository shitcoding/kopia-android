package org.kopiaKt.android.storage

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Tests for the cursor-backed SAF provider. Verifies that listing a directory is ONE
 * `queryChildDocuments` round-trip (not `DocumentFile.listFiles()`'s per-child, per-property IPC), that a
 * directory queries under ITS OWN document id (recursion), and that each child's metadata is materialized
 * from its cursor row. (task-14)
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("SAF cursor document provider")
class SafCursorDocumentTest {

    private val treeUri: Uri = Uri.parse("content://com.example.docs/tree/root")

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private fun cursorOf(vararg rows: Array<Any?>): MatrixCursor = MatrixCursor(projection).apply { rows.forEach { addRow(it) } }

    /** The exact children-URI the provider must query for [documentId] under [treeUri]. */
    private fun childrenUriFor(documentId: String): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

    private fun provider(resolver: ContentResolver, documentId: String): SafCursorDocument = SafCursorDocument(
        resolver = resolver,
        treeUri = treeUri,
        documentId = documentId,
        displayName = documentId,
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        size = 0L,
        modified = 0L,
    )

    @Test
    fun `listFiles queries the directory's own child URI once and maps every row`() {
        val resolver = mockk<ContentResolver>()
        // Stub ONLY the root's children URI -- a query against any other URI returns no match, so an impl
        // that queried the wrong URI would see an empty listing and fail the assertions below.
        every { resolver.query(childrenUriFor("root"), any(), any(), any(), any()) } returns cursorOf(
            arrayOf<Any?>("doc:photo.jpg", "photo.jpg", "image/jpeg", 2048L, 1_700_000_000_000L),
            // A directory row reports a null size.
            arrayOf<Any?>("doc:subdir", "subdir", DocumentsContract.Document.MIME_TYPE_DIR, null, 1_700_000_000_001L),
        )

        val children = provider(resolver, "root").listFiles()

        assertThat(children.map { it.getName() }).containsExactly("photo.jpg", "subdir")

        val photo = children.first { it.getName() == "photo.jpg" }
        assertThat(photo.isFile()).isTrue()
        assertThat(photo.isDirectory()).isFalse()
        assertThat(photo.length()).isEqualTo(2048L)
        assertThat(photo.lastModified()).isEqualTo(1_700_000_000_000L)

        val subdir = children.first { it.getName() == "subdir" }
        assertThat(subdir.isDirectory()).isTrue()
        assertThat(subdir.isFile()).isFalse()
        assertThat(subdir.length()).isEqualTo(0L) // null cursor size -> 0

        // The whole directory came from ONE query against the root's children URI.
        verify(exactly = 1) { resolver.query(childrenUriFor("root"), any(), any(), any(), any()) }
    }

    @Test
    fun `a subdirectory lists under its own document id, not the root's`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(childrenUriFor("root"), any(), any(), any(), any()) } returns cursorOf(
            arrayOf<Any?>("doc:subdir", "subdir", DocumentsContract.Document.MIME_TYPE_DIR, null, 0L),
        )
        every { resolver.query(childrenUriFor("doc:subdir"), any(), any(), any(), any()) } returns cursorOf(
            arrayOf<Any?>("doc:nested.txt", "nested.txt", "text/plain", 7L, 0L),
        )

        val subdir = provider(resolver, "root").listFiles().single { it.isDirectory() }
        val nested = subdir.listFiles().orEmpty()

        assertThat(nested.map { it.getName() }).containsExactly("nested.txt")
        verify(exactly = 1) { resolver.query(childrenUriFor("doc:subdir"), any(), any(), any(), any()) }
    }

    @Test
    fun `listFiles returns empty when the provider yields a null cursor`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any<Uri>(), any(), any(), any(), any()) } returns null

        assertThat(provider(resolver, "root").listFiles()).isEmpty()
    }

    @Test
    fun `an empty MIME row is treated as unknown, not a file`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(childrenUriFor("root"), any(), any(), any(), any()) } returns cursorOf(
            arrayOf<Any?>("doc:weird", "weird", "", 0L, 0L),
        )

        val entry = provider(resolver, "root").listFiles().single()
        assertThat(entry.isFile()).isFalse()
        assertThat(entry.isDirectory()).isFalse()
    }

    @Test
    fun `findFile returns the matching child by display name`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(childrenUriFor("root"), any(), any(), any(), any()) } answers {
            cursorOf(
                arrayOf<Any?>("doc:a.txt", "a.txt", "text/plain", 10L, 0L),
                arrayOf<Any?>("doc:b.txt", "b.txt", "text/plain", 20L, 0L),
            )
        }

        val root = provider(resolver, "root")
        val found = root.findFile("b.txt")
        assertThat(found).isNotNull()
        assertThat(found!!.getName()).isEqualTo("b.txt")
        assertThat(found.length()).isEqualTo(20L)
        assertThat(root.findFile("missing.txt")).isNull()
    }

    @Test
    fun `child document URI is built under the tree so it is openable`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(childrenUriFor("root"), any(), any(), any(), any()) } returns cursorOf(
            arrayOf<Any?>("doc:child", "child", "text/plain", 1L, 0L),
        )

        val child = provider(resolver, "root").listFiles().single()

        // Equivalent to what DocumentFile hands ContentResolver.openInputStream.
        assertThat(child.getUri())
            .isEqualTo(DocumentsContract.buildDocumentUriUsingTree(treeUri, "doc:child"))
    }
}
