package org.kopiaKt.android.storage

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Unit tests for SAF filesystem adapter.
 *
 * Uses a FakeDocumentFileProvider to avoid direct dependency on Android's
 * DocumentFile, making tests fast and reliable without instrumentation.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
@DisplayName("SAF Filesystem Adapter")
class SafFilesystemTest {

    private lateinit var fakeContentResolver: FakeContentResolverProvider

    @BeforeEach
    fun setup() {
        fakeContentResolver = FakeContentResolverProvider()
    }

    @Nested
    @DisplayName("SafDirectory")
    inner class SafDirectoryTests {

        @Test
        fun `iterate returns files and subdirectories`(): Unit = runTest {
            val childFile = FakeDocumentFileProvider(
                name = "photo.jpg",
                uri = Uri.parse("content://test/photo.jpg"),
                isDirectory = false,
                isFile = true,
                length = 1024L,
                lastModified = 1700000000000L,
            )
            val childDir = FakeDocumentFileProvider(
                name = "subdir",
                uri = Uri.parse("content://test/subdir"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
            )
            val root = FakeDocumentFileProvider(
                name = "root",
                uri = Uri.parse("content://test/root"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = listOf(childFile, childDir),
            )

            val dir = SafDirectory(root, fakeContentResolver)
            val entries = dir.readEntries()

            assertThat(entries).hasSize(2)
            val names = entries.map { it.name }
            assertThat(names).containsExactly("photo.jpg", "subdir")
        }

        @Test
        fun `iterate on empty directory returns no entries`(): Unit = runTest {
            val root = FakeDocumentFileProvider(
                name = "empty",
                uri = Uri.parse("content://test/empty"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = emptyList(),
            )

            val dir = SafDirectory(root, fakeContentResolver)
            val entries = dir.readEntries()

            assertThat(entries).isEmpty()
        }

        @Test
        fun `child returns correct entry for existing child`(): Unit = runTest {
            val childFile = FakeDocumentFileProvider(
                name = "notes.txt",
                uri = Uri.parse("content://test/notes.txt"),
                isDirectory = false,
                isFile = true,
                length = 512L,
                lastModified = 1700000000000L,
            )
            val root = FakeDocumentFileProvider(
                name = "root",
                uri = Uri.parse("content://test/root"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = listOf(childFile),
            )

            val dir = SafDirectory(root, fakeContentResolver)
            val child = dir.child("notes.txt")

            assertThat(child).isNotNull()
            assertThat(child!!.name).isEqualTo("notes.txt")
        }

        @Test
        fun `child returns null for nonexistent child`(): Unit = runTest {
            val root = FakeDocumentFileProvider(
                name = "root",
                uri = Uri.parse("content://test/root"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = emptyList(),
            )

            val dir = SafDirectory(root, fakeContentResolver)
            val child = dir.child("nonexistent.txt")

            assertThat(child).isNull()
        }

        @Test
        fun `entry returns correct type for file child`(): Unit = runTest {
            val childFile = FakeDocumentFileProvider(
                name = "data.bin",
                uri = Uri.parse("content://test/data.bin"),
                isDirectory = false,
                isFile = true,
                length = 2048L,
                lastModified = 1700000000000L,
            )
            val root = FakeDocumentFileProvider(
                name = "root",
                uri = Uri.parse("content://test/root"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = listOf(childFile),
            )

            val dir = SafDirectory(root, fakeContentResolver)
            val entries = dir.readEntries()
            val fileEntry = entries.first()

            assertThat(fileEntry.type).isEqualTo(EntryType.FILE)
            assertThat(fileEntry.isFile()).isTrue()
            assertThat(fileEntry.isDirectory()).isFalse()
            assertThat(fileEntry).isInstanceOf(File::class.java)
        }

        @Test
        fun `entry returns correct type for directory child`(): Unit = runTest {
            val childDir = FakeDocumentFileProvider(
                name = "subdir",
                uri = Uri.parse("content://test/subdir"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = emptyList(),
            )
            val root = FakeDocumentFileProvider(
                name = "root",
                uri = Uri.parse("content://test/root"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = listOf(childDir),
            )

            val dir = SafDirectory(root, fakeContentResolver)
            val entries = dir.readEntries()
            val dirEntry = entries.first()

            assertThat(dirEntry.type).isEqualTo(EntryType.DIRECTORY)
            assertThat(dirEntry.isDirectory()).isTrue()
            assertThat(dirEntry.isFile()).isFalse()
            assertThat(dirEntry).isInstanceOf(Directory::class.java)
        }

        @Test
        fun `modTime returns last modified time`(): Unit = runTest {
            val timestamp = 1700000000000L
            val root = FakeDocumentFileProvider(
                name = "root",
                uri = Uri.parse("content://test/root"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = timestamp,
            )

            val dir = SafDirectory(root, fakeContentResolver)

            assertThat(dir.modTime.toEpochMilli()).isEqualTo(timestamp)
        }

        @Test
        fun `name returns display name`(): Unit = runTest {
            val root = FakeDocumentFileProvider(
                name = "My Documents",
                uri = Uri.parse("content://test/my-documents"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
            )

            val dir = SafDirectory(root, fakeContentResolver)

            assertThat(dir.name).isEqualTo("My Documents")
        }
    }

    @Nested
    @DisplayName("SafFile")
    inner class SafFileTests {

        @Test
        fun `open returns InputStream via ContentResolverProvider`(): Unit = runTest {
            val fileContent = "Hello, SAF!".toByteArray()
            val fileUri = Uri.parse("content://test/hello.txt")

            fakeContentResolver.registerStream(fileUri, fileContent)

            val provider = FakeDocumentFileProvider(
                name = "hello.txt",
                uri = fileUri,
                isDirectory = false,
                isFile = true,
                length = fileContent.size.toLong(),
                lastModified = 1700000000000L,
            )

            val file = SafFile(provider, fakeContentResolver)
            val content = file.open().use { it.readBytes() }

            assertThat(content).isEqualTo(fileContent)
        }

        @Test
        fun `size returns correct byte count`() {
            val provider = FakeDocumentFileProvider(
                name = "data.bin",
                uri = Uri.parse("content://test/data.bin"),
                isDirectory = false,
                isFile = true,
                length = 4096L,
                lastModified = 1700000000000L,
            )

            val file = SafFile(provider, fakeContentResolver)

            assertThat(file.size).isEqualTo(4096L)
        }

        @Test
        fun `modTime returns correct timestamp`() {
            val timestamp = 1680000000000L
            val provider = FakeDocumentFileProvider(
                name = "old.txt",
                uri = Uri.parse("content://test/old.txt"),
                isDirectory = false,
                isFile = true,
                length = 100L,
                lastModified = timestamp,
            )

            val file = SafFile(provider, fakeContentResolver)

            assertThat(file.modTime.toEpochMilli()).isEqualTo(timestamp)
        }

        @Test
        fun `mode returns default permission bits 0644 for files`() {
            val provider = FakeDocumentFileProvider(
                name = "regular.txt",
                uri = Uri.parse("content://test/regular.txt"),
                isDirectory = false,
                isFile = true,
                length = 100L,
                lastModified = 1700000000000L,
            )

            val file = SafFile(provider, fakeContentResolver)

            // 0644 in octal = 420 in decimal
            assertThat(file.mode).isEqualTo(0b110100100) // rw-r--r--
        }

        @Test
        fun `owner returns empty OwnerInfo`() {
            val provider = FakeDocumentFileProvider(
                name = "file.txt",
                uri = Uri.parse("content://test/file.txt"),
                isDirectory = false,
                isFile = true,
                length = 100L,
                lastModified = 1700000000000L,
            )

            val file = SafFile(provider, fakeContentResolver)

            assertThat(file.owner).isEqualTo(OwnerInfo.EMPTY)
        }
    }

    @Nested
    @DisplayName("SAF Symlink behavior")
    inner class SafSymlinkTests {

        @Test
        fun `isSymlink returns false for SAF file entries`() {
            val provider = FakeDocumentFileProvider(
                name = "link.txt",
                uri = Uri.parse("content://test/link.txt"),
                isDirectory = false,
                isFile = true,
                length = 100L,
                lastModified = 1700000000000L,
            )

            val file = SafFile(provider, fakeContentResolver)

            assertThat(file.isSymlink()).isFalse()
        }

        @Test
        fun `entry type is never SYMLINK for SAF entries`(): Unit = runTest {
            val childFile = FakeDocumentFileProvider(
                name = "file.txt",
                uri = Uri.parse("content://test/file.txt"),
                isDirectory = false,
                isFile = true,
                length = 100L,
                lastModified = 1700000000000L,
            )
            val childDir = FakeDocumentFileProvider(
                name = "dir",
                uri = Uri.parse("content://test/dir"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = emptyList(),
            )
            val root = FakeDocumentFileProvider(
                name = "root",
                uri = Uri.parse("content://test/root"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = listOf(childFile, childDir),
            )

            val dir = SafDirectory(root, fakeContentResolver)
            val entries = dir.readEntries()

            for (entry in entries) {
                assertThat(entry.type).isNotEqualTo(EntryType.SYMLINK)
                assertThat(entry.isSymlink()).isFalse()
            }
        }
    }

    @Nested
    @DisplayName("Error handling")
    inner class ErrorHandlingTests {

        @Test
        fun `open on revoked URI throws IOException`(): Unit = runTest {
            val fileUri = Uri.parse("content://test/revoked.txt")

            // Do not register any stream -- simulates revoked/unavailable URI
            fakeContentResolver.registerError(fileUri, IOException("Permission denied"))

            val provider = FakeDocumentFileProvider(
                name = "revoked.txt",
                uri = fileUri,
                isDirectory = false,
                isFile = true,
                length = 100L,
                lastModified = 1700000000000L,
            )

            val file = SafFile(provider, fakeContentResolver)

            assertThrows<IOException> {
                file.open()
            }
        }

        @Test
        fun `iterate on deleted directory returns empty`(): Unit = runTest {
            // Simulate a directory whose children listing returns null
            val root = FakeDocumentFileProvider(
                name = "deleted",
                uri = Uri.parse("content://test/deleted"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                children = null, // null signals deleted/unavailable
            )

            val dir = SafDirectory(root, fakeContentResolver)
            val entries = dir.readEntries()

            assertThat(entries).isEmpty()
        }

        @Test
        fun `iterate wraps ContentResolver exceptions`(): Unit = runTest {
            val root = FakeDocumentFileProvider(
                name = "error",
                uri = Uri.parse("content://test/error"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
                listFilesError = SecurityException("Access denied by provider"),
            )

            val dir = SafDirectory(root, fakeContentResolver)

            assertThrows<IOException> {
                dir.readEntries()
            }
        }
    }

    @Nested
    @DisplayName("Common Entry properties")
    inner class CommonEntryTests {

        @Test
        fun `directory mode returns 0755`() {
            val provider = FakeDocumentFileProvider(
                name = "mydir",
                uri = Uri.parse("content://test/mydir"),
                isDirectory = true,
                isFile = false,
                length = 0L,
                lastModified = 1700000000000L,
            )

            val dir = SafDirectory(provider, fakeContentResolver)

            // 0755 in octal = rwxr-xr-x
            assertThat(dir.mode).isEqualTo(0b111101101)
        }

        @Test
        fun `localFilesystemPath returns empty string`() {
            val provider = FakeDocumentFileProvider(
                name = "file.txt",
                uri = Uri.parse("content://test/file.txt"),
                isDirectory = false,
                isFile = true,
                length = 100L,
                lastModified = 1700000000000L,
            )

            val file = SafFile(provider, fakeContentResolver)

            assertThat(file.localFilesystemPath).isEmpty()
        }

        @Test
        fun `device returns DeviceInfo EMPTY`() {
            val provider = FakeDocumentFileProvider(
                name = "file.txt",
                uri = Uri.parse("content://test/file.txt"),
                isDirectory = false,
                isFile = true,
                length = 100L,
                lastModified = 1700000000000L,
            )

            val file = SafFile(provider, fakeContentResolver)

            assertThat(file.device).isEqualTo(DeviceInfo.EMPTY)
        }

        @Test
        fun `directory size returns 0`() {
            val provider = FakeDocumentFileProvider(
                name = "mydir",
                uri = Uri.parse("content://test/mydir"),
                isDirectory = true,
                isFile = false,
                length = 4096L, // provider reports some size
                lastModified = 1700000000000L,
            )

            val dir = SafDirectory(provider, fakeContentResolver)

            // Directories always report size 0 regardless of provider value
            assertThat(dir.size).isEqualTo(0L)
        }
    }
}

// -- Test doubles --

/**
 * Fake implementation of DocumentFileProvider for testing.
 */
class FakeDocumentFileProvider(
    private val name: String,
    private val uri: Uri,
    private val isDirectory: Boolean,
    private val isFile: Boolean,
    private val length: Long,
    private val lastModified: Long,
    private val children: List<FakeDocumentFileProvider>? = null,
    private val listFilesError: Throwable? = null,
) : DocumentFileProvider {

    override fun getName(): String = name

    override fun getUri(): Uri = uri

    override fun isDirectory(): Boolean = isDirectory

    override fun isFile(): Boolean = isFile

    override fun length(): Long = length

    override fun lastModified(): Long = lastModified

    override fun listFiles(): List<DocumentFileProvider>? {
        if (listFilesError != null) throw listFilesError
        return children
    }

    override fun findFile(name: String): DocumentFileProvider? = children?.find { it.getName() == name }
}

/**
 * Fake ContentResolverProvider that returns pre-registered InputStreams.
 */
class FakeContentResolverProvider : ContentResolverProvider {

    private val streams = mutableMapOf<Uri, ByteArray>()
    private val errors = mutableMapOf<Uri, Throwable>()

    fun registerStream(uri: Uri, data: ByteArray) {
        streams[uri] = data
    }

    fun registerError(uri: Uri, error: Throwable) {
        errors[uri] = error
    }

    override fun openInputStream(uri: Uri): InputStream {
        val error = errors[uri]
        if (error != null) throw error

        val data = streams[uri]
            ?: throw IOException("No stream registered for URI: $uri")

        return ByteArrayInputStream(data)
    }
}
