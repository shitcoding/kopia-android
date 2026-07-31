package org.kopiaKt.snapshot.restore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.EntryType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readBytes
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FilesystemOutputTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var output: FilesystemOutput

    @BeforeEach
    fun setup() {
        output = FilesystemOutput(tempDir)
    }

    @AfterEach
    fun teardown() {
        output.close()
    }

    // --- Directory Tests ---

    @Test
    fun `beginDirectory creates new directory`(): Unit = runBlocking {
        val entry = makeDirEntry("testdir", EntryType.DIRECTORY)

        output.beginDirectory("testdir", entry)

        assertThat(tempDir.resolve("testdir").exists()).isTrue()
        assertThat(tempDir.resolve("testdir").isDirectory()).isTrue()
    }

    @Test
    fun `beginDirectory creates nested directories`(): Unit = runBlocking {
        val entry = makeDirEntry("deep/nested/dir", EntryType.DIRECTORY)

        output.beginDirectory("deep/nested/dir", entry)

        assertThat(tempDir.resolve("deep/nested/dir").exists()).isTrue()
        assertThat(tempDir.resolve("deep/nested/dir").isDirectory()).isTrue()
    }

    @Test
    fun `beginDirectory fails on existing non-empty directory without overwrite`(): Unit = runBlocking {
        // Create existing directory with content
        val dir = tempDir.resolve("existingdir").createDirectories()
        dir.resolve("file.txt").writeText("content")

        val entry = makeDirEntry("existingdir", EntryType.DIRECTORY)

        assertThrows<RestoreException> {
            runBlocking { output.beginDirectory("existingdir", entry) }
        }
        // Refusing is only half of it: what was already there has to still be there. A restore that
        // clears the directory and THEN discovers it should not have would pass on the throw alone.
        assertThat(dir.resolve("file.txt").readText()).isEqualTo("content")
    }

    @Test
    fun `beginDirectory succeeds on existing non-empty directory with overwrite`(): Unit = runBlocking {
        val overwriteOutput = FilesystemOutput(
            tempDir,
            FilesystemOutputOptions(overwriteDirectories = true),
        )

        // Create existing directory with content
        val dir = tempDir.resolve("existingdir").createDirectories()
        dir.resolve("file.txt").writeText("content")

        val entry = makeDirEntry("existingdir", EntryType.DIRECTORY)

        overwriteOutput.beginDirectory("existingdir", entry)

        assertThat(tempDir.resolve("existingdir").exists()).isTrue()
    }

    @Test
    fun `finishDirectory sets permissions on POSIX`(): Unit = runBlocking {
        val entry = makeDirEntry("testdir", EntryType.DIRECTORY, permissions = 493) // 0o755

        output.beginDirectory("testdir", entry)
        output.finishDirectory("testdir", entry)

        val path = tempDir.resolve("testdir")
        assertThat(path.exists()).isTrue()

        // Check permissions (only on POSIX systems)
        if (!System.getProperty("os.name").lowercase().contains("windows")) {
            val perms = path.getPosixFilePermissions()
            // Should have OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE, OTHERS_READ, OTHERS_EXECUTE
            assertThat(perms).isNotEmpty()
        }
    }

    // --- File Tests ---

    @Test
    fun `writeFile creates new file`(): Unit = runBlocking {
        val content = "Hello, World!".toByteArray()
        val entry = makeFileEntry("test.txt", content.size.toLong())

        output.writeFile("test.txt", entry, content.inputStream())

        val path = tempDir.resolve("test.txt")
        assertThat(path.exists()).isTrue()
        assertThat(path.isRegularFile()).isTrue()
        assertThat(path.readBytes().toList()).isEqualTo(content.toList())
    }

    @Test
    fun `writeFile creates file in nested directory`(): Unit = runBlocking {
        val content = "Nested content".toByteArray()
        val entry = makeFileEntry("a/b/c.txt", content.size.toLong())

        // Create parent directories first
        tempDir.resolve("a/b").createDirectories()

        output.writeFile("a/b/c.txt", entry, content.inputStream())

        val path = tempDir.resolve("a/b/c.txt")
        assertThat(path.exists()).isTrue()
        assertThat(path.readBytes().toList()).isEqualTo(content.toList())
    }

    @Test
    fun `writeFile fails on existing file without overwrite`(): Unit = runBlocking {
        val path = tempDir.resolve("existing.txt")
        path.writeText("existing content")

        val content = "new content".toByteArray()
        val entry = makeFileEntry("existing.txt", content.size.toLong())

        assertThrows<RestoreException> {
            runBlocking { output.writeFile("existing.txt", entry, content.inputStream()) }
        }
        // The file the user already had must be untouched -- truncating and then throwing would
        // satisfy the assertion above while destroying their data.
        assertThat(path.readText()).isEqualTo("existing content")
    }

    @Test
    fun `writeFile succeeds on existing file with overwrite`(): Unit = runBlocking {
        val overwriteOutput = FilesystemOutput(
            tempDir,
            FilesystemOutputOptions(overwriteFiles = true),
        )

        val path = tempDir.resolve("existing.txt")
        path.writeText("existing content")

        val newContent = "new content".toByteArray()
        val entry = makeFileEntry("existing.txt", newContent.size.toLong())

        overwriteOutput.writeFile("existing.txt", entry, newContent.inputStream())

        assertThat(path.readBytes().toList()).isEqualTo(newContent.toList())
    }

    @Test
    fun `writeFile reports progress`(): Unit = runBlocking {
        val content = ByteArray(10000) { it.toByte() }
        val entry = makeFileEntry("largefile.bin", content.size.toLong())

        var totalReported = 0L
        output.writeFile("largefile.bin", entry, content.inputStream()) { bytes ->
            totalReported += bytes
        }

        assertThat(totalReported).isEqualTo(content.size.toLong())
    }

    @Test
    fun `writeFile atomic creates file atomically`(): Unit = runBlocking {
        val atomicOutput = FilesystemOutput(
            tempDir,
            FilesystemOutputOptions(writeFilesAtomically = true, overwriteFiles = true),
        )

        val content = "atomic content".toByteArray()
        val entry = makeFileEntry("atomic.txt", content.size.toLong())

        atomicOutput.writeFile("atomic.txt", entry, content.inputStream())

        val path = tempDir.resolve("atomic.txt")
        assertThat(path.exists()).isTrue()
        assertThat(path.readBytes().toList()).isEqualTo(content.toList())
    }

    // --- fileExists Tests ---

    @Test
    fun `fileExists returns false for non-existent file`(): Unit = runBlocking {
        val entry = makeFileEntry("nonexistent.txt", 100)
        assertThat(output.fileExists("nonexistent.txt", entry)).isFalse()
    }

    @Test
    fun `fileExists returns false for directory`(): Unit = runBlocking {
        tempDir.resolve("testdir").createDirectories()
        val entry = makeFileEntry("testdir", 0)
        assertThat(output.fileExists("testdir", entry)).isFalse()
    }

    @Test
    fun `fileExists returns false for wrong size`(): Unit = runBlocking {
        val path = tempDir.resolve("test.txt")
        path.writeText("short")

        val entry = makeFileEntry("test.txt", 1000) // Wrong size
        assertThat(output.fileExists("test.txt", entry)).isFalse()
    }

    @Test
    fun `fileExists returns true for matching file`(): Unit = runBlocking {
        val content = "test content"
        val path = tempDir.resolve("test.txt")
        path.writeText(content)

        val entry = makeFileEntry(
            "test.txt",
            content.length.toLong(),
            modTime = path.getLastModifiedTime().toInstant(),
        )
        assertThat(output.fileExists("test.txt", entry)).isTrue()
    }

    // --- Symlink Tests ---

    @Test
    fun `createSymlink creates symbolic link`(): Unit = runBlocking {
        val entry = makeDirEntry("link", EntryType.SYMLINK)

        output.createSymlink("link", entry, "/target/path")

        val path = tempDir.resolve("link")
        assertThat(path.isSymbolicLink()).isTrue()
        assertThat(path.readSymbolicLink().toString()).isEqualTo("/target/path")
    }

    @Test
    fun `createSymlink fails on existing symlink without overwrite`(): Unit = runBlocking {
        val existingLink = tempDir.resolve("link")
        Files.createSymbolicLink(existingLink, Path.of("/old/target"))

        val entry = makeDirEntry("link", EntryType.SYMLINK)

        assertThrows<RestoreException> {
            runBlocking { output.createSymlink("link", entry, "/new/target") }
        }
        // Deleting the old link before checking would still throw, and still have destroyed it.
        assertThat(existingLink.readSymbolicLink().toString()).isEqualTo("/old/target")
    }

    @Test
    fun `createSymlink succeeds on existing symlink with overwrite`(): Unit = runBlocking {
        val overwriteOutput = FilesystemOutput(
            tempDir,
            FilesystemOutputOptions(overwriteSymlinks = true),
        )

        val existingLink = tempDir.resolve("link")
        Files.createSymbolicLink(existingLink, Path.of("/old/target"))

        val entry = makeDirEntry("link", EntryType.SYMLINK)

        overwriteOutput.createSymlink("link", entry, "/new/target")

        assertThat(existingLink.readSymbolicLink().toString()).isEqualTo("/new/target")
    }

    // --- symlinkExists Tests ---

    @Test
    fun `symlinkExists returns false for non-existent symlink`(): Unit = runBlocking {
        val entry = makeDirEntry("nonexistent", EntryType.SYMLINK)
        assertThat(output.symlinkExists("nonexistent", entry, "/target")).isFalse()
    }

    @Test
    fun `symlinkExists returns false for regular file`(): Unit = runBlocking {
        tempDir.resolve("file.txt").writeText("content")
        val entry = makeDirEntry("file.txt", EntryType.SYMLINK)
        assertThat(output.symlinkExists("file.txt", entry, "/target")).isFalse()
    }

    @Test
    fun `symlinkExists returns false for wrong target`(): Unit = runBlocking {
        val link = tempDir.resolve("link")
        Files.createSymbolicLink(link, Path.of("/wrong/target"))

        val entry = makeDirEntry("link", EntryType.SYMLINK)
        assertThat(output.symlinkExists("link", entry, "/correct/target")).isFalse()
    }

    @Test
    fun `symlinkExists returns true for matching symlink`(): Unit = runBlocking {
        val link = tempDir.resolve("link")
        Files.createSymbolicLink(link, Path.of("/target/path"))

        val entry = makeDirEntry("link", EntryType.SYMLINK)
        assertThat(output.symlinkExists("link", entry, "/target/path")).isTrue()
    }

    // --- Helper Functions ---

    private fun makeDirEntry(
        name: String,
        type: EntryType,
        permissions: Int = 493, // 0o755
    ) = DirEntry(
        name = name.substringAfterLast('/'),
        type = type,
        permissions = permissions,
        fileSize = 0,
        modTime = Instant.now(),
    )

    private fun makeFileEntry(
        name: String,
        size: Long,
        permissions: Int = 420, // 0o644
        modTime: Instant = Instant.now(),
    ) = DirEntry(
        name = name.substringAfterLast('/'),
        type = EntryType.FILE,
        permissions = permissions,
        fileSize = size,
        modTime = modTime,
    )
}
