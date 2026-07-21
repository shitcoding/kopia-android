package org.kopiaKt.snapshot.fs

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/**
 * Tests for LocalFilesystem.
 */
@OptIn(ExperimentalPathApi::class)
class LocalFilesystemTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("kopiaKt-fs-test")
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Nested
    inner class EntryCreation {
        @Test
        fun `creates file entry for regular file`() {
            val file = tempDir.resolve("test.txt")
            file.writeText("Hello, World!")

            val entry = LocalFilesystem.entry(file)

            assertEquals("test.txt", entry.name)
            assertEquals(EntryType.FILE, entry.type)
            assertEquals(13L, entry.size)
            assertTrue(entry.isFile())
            assertFalse(entry.isDirectory())
            assertFalse(entry.isSymlink())
        }

        @Test
        fun `creates directory entry for directory`() {
            val dir = tempDir.resolve("subdir")
            dir.createDirectories()

            val entry = LocalFilesystem.entry(dir)

            assertEquals("subdir", entry.name)
            assertEquals(EntryType.DIRECTORY, entry.type)
            assertTrue(entry.isDirectory())
            assertFalse(entry.isFile())
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `creates symlink entry for symbolic link`() {
            val target = tempDir.resolve("target.txt")
            target.writeText("target content")

            val link = tempDir.resolve("link.txt")
            link.createSymbolicLinkPointingTo(target)

            val entry = LocalFilesystem.entry(link)

            assertEquals("link.txt", entry.name)
            assertEquals(EntryType.SYMLINK, entry.type)
            assertTrue(entry.isSymlink())
        }

        @Test
        fun `creates error entry for non-existent path`() {
            val nonExistent = tempDir.resolve("does-not-exist")

            val entry = LocalFilesystem.entry(nonExistent)

            assertEquals(EntryType.ERROR, entry.type)
            assertTrue(entry is ErrorEntry)
            assertNotNull((entry as ErrorEntry).error)
        }

        @Test
        fun `entry has correct local filesystem path`() {
            val file = tempDir.resolve("test.txt")
            file.createFile()

            val entry = LocalFilesystem.entry(file)

            assertEquals(file.toAbsolutePath().toString(), entry.localFilesystemPath)
        }
    }

    @Nested
    inner class DirectoryOperations {
        @Test
        fun `directory can enumerate children`() = runBlocking {
            // Create test structure
            tempDir.resolve("file1.txt").writeText("content1")
            tempDir.resolve("file2.txt").writeText("content2")
            tempDir.resolve("subdir").createDirectories()

            val dir = LocalFilesystem.directory(tempDir)
            val entries = dir.readEntries()

            assertEquals(3, entries.size)
            assertTrue(entries.any { it.name == "file1.txt" })
            assertTrue(entries.any { it.name == "file2.txt" })
            assertTrue(entries.any { it.name == "subdir" })
        }

        @Test
        fun `directory can get child by name`() = runBlocking {
            val file = tempDir.resolve("test.txt")
            file.writeText("content")

            val dir = LocalFilesystem.directory(tempDir)
            val child = dir.child("test.txt")

            assertNotNull(child)
            assertEquals("test.txt", child!!.name)
            assertTrue(child.isFile())
        }

        @Test
        fun `child returns null for non-existent entry`() = runBlocking {
            val dir = LocalFilesystem.directory(tempDir)
            val child = dir.child("does-not-exist")

            assertNull(child)
        }

        @Test
        fun `directory iterator works correctly`() = runBlocking {
            tempDir.resolve("a.txt").createFile()
            tempDir.resolve("b.txt").createFile()
            tempDir.resolve("c.txt").createFile()

            val dir = LocalFilesystem.directory(tempDir)
            val names = mutableListOf<String>()

            dir.iterate().use { iterator ->
                while (true) {
                    val entry = iterator.next() ?: break
                    names.add(entry.name)
                }
            }

            assertEquals(3, names.size)
            assertTrue(names.containsAll(listOf("a.txt", "b.txt", "c.txt")))
        }

        @Test
        fun `empty directory has no children`() = runBlocking {
            val emptyDir = tempDir.resolve("empty")
            emptyDir.createDirectories()

            val dir = LocalFilesystem.directory(emptyDir)
            val entries = dir.readEntries()

            assertTrue(entries.isEmpty())
        }
    }

    @Nested
    inner class FileOperations {
        @Test
        fun `file can be opened for reading`() = runBlocking {
            val file = tempDir.resolve("test.txt")
            file.writeText("Hello, Kopia!")

            val entry = LocalFilesystem.entry(file) as File
            val content = entry.open().use { it.readAllBytes() }

            assertEquals("Hello, Kopia!", String(content))
        }

        @Test
        fun `large file can be read`() = runBlocking {
            val file = tempDir.resolve("large.bin")
            val data = ByteArray(1024 * 1024) { it.toByte() } // 1MB
            file.toFile().writeBytes(data)

            val entry = LocalFilesystem.entry(file) as File

            assertEquals(1024L * 1024, entry.size)

            entry.open().use { stream ->
                val readData = stream.readAllBytes()
                assertTrue(data.contentEquals(readData))
            }
        }
    }

    @Nested
    @DisabledOnOs(OS.WINDOWS)
    inner class SymlinkOperations {
        @Test
        fun `symlink readlink returns target path`() = runBlocking {
            val target = tempDir.resolve("target.txt")
            target.writeText("target content")

            val link = tempDir.resolve("link.txt")
            link.createSymbolicLinkPointingTo(target)

            val entry = LocalFilesystem.entry(link) as Symlink
            val targetPath = entry.readlink()

            assertTrue(targetPath.endsWith("target.txt"))
        }

        @Test
        fun `symlink resolve returns target entry`() = runBlocking {
            val target = tempDir.resolve("target.txt")
            target.writeText("target content")

            val link = tempDir.resolve("link.txt")
            link.createSymbolicLinkPointingTo(target)

            val entry = LocalFilesystem.entry(link) as Symlink
            val resolved = entry.resolve()

            assertNotNull(resolved)
            assertTrue(resolved!!.isFile())
            assertEquals("target.txt", resolved.name)
        }

        @Test
        fun `symlink to directory resolves correctly`() = runBlocking {
            val targetDir = tempDir.resolve("targetdir")
            targetDir.createDirectories()
            targetDir.resolve("child.txt").writeText("child")

            val link = tempDir.resolve("linkdir")
            link.createSymbolicLinkPointingTo(targetDir)

            val entry = LocalFilesystem.entry(link) as Symlink
            val resolved = entry.resolve()

            assertNotNull(resolved)
            assertTrue(resolved!!.isDirectory())
        }

        @Test
        fun `symlink to non-existent target returns null`() = runBlocking {
            val link = tempDir.resolve("broken-link")
            link.createSymbolicLinkPointingTo(tempDir.resolve("does-not-exist"))

            val entry = LocalFilesystem.entry(link) as Symlink
            val resolved = entry.resolve()

            assertNull(resolved)
        }

        @Test
        fun `deeply nested symlinks are resolved`() = runBlocking {
            val target = tempDir.resolve("target.txt")
            target.writeText("content")

            // Create chain: link1 -> link2 -> link3 -> target
            val link3 = tempDir.resolve("link3")
            link3.createSymbolicLinkPointingTo(target)

            val link2 = tempDir.resolve("link2")
            link2.createSymbolicLinkPointingTo(link3)

            val link1 = tempDir.resolve("link1")
            link1.createSymbolicLinkPointingTo(link2)

            val entry = LocalFilesystem.entry(link1) as Symlink
            val resolved = entry.resolve()

            assertNotNull(resolved)
            assertTrue(resolved!!.isFile())
            assertEquals("target.txt", resolved.name)
        }
    }

    @Nested
    inner class RecursiveEnumeration {
        @Test
        fun `can recursively enumerate directory tree`() = runBlocking {
            // Create nested structure
            tempDir.resolve("a/b/c").createDirectories()
            tempDir.resolve("a/file1.txt").writeText("1")
            tempDir.resolve("a/b/file2.txt").writeText("2")
            tempDir.resolve("a/b/c/file3.txt").writeText("3")

            val allFiles = mutableListOf<String>()
            enumerateRecursively(LocalFilesystem.directory(tempDir), "") { path, entry ->
                if (entry.isFile()) {
                    allFiles.add(path)
                }
            }

            assertEquals(3, allFiles.size)
            assertTrue(allFiles.any { it.endsWith("file1.txt") })
            assertTrue(allFiles.any { it.endsWith("file2.txt") })
            assertTrue(allFiles.any { it.endsWith("file3.txt") })
        }

        private suspend fun enumerateRecursively(
            dir: Directory,
            basePath: String,
            visitor: (String, Entry) -> Unit,
        ) {
            dir.iterate().use { iterator ->
                while (true) {
                    val entry = iterator.next() ?: break
                    val path = if (basePath.isEmpty()) entry.name else "$basePath/${entry.name}"
                    visitor(path, entry)

                    if (entry is Directory) {
                        enumerateRecursively(entry, path, visitor)
                    }
                }
            }
        }
    }

    @Nested
    inner class MetadataAccess {
        @Test
        fun `modification time is populated`() {
            val file = tempDir.resolve("test.txt")
            file.writeText("content")

            val entry = LocalFilesystem.entry(file)

            assertTrue(entry.modTime.epochSecond > 0)
        }

        @Test
        fun `file size is accurate`() {
            val file = tempDir.resolve("test.txt")
            file.writeText("12345") // 5 bytes

            val entry = LocalFilesystem.entry(file)

            assertEquals(5L, entry.size)
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `permissions are read on Unix`() {
            val file = tempDir.resolve("test.txt")
            file.createFile()
            // Set permissions to 644 (rw-r--r--)
            file.toFile().setReadable(true, false)
            file.toFile().setWritable(true, true)
            file.toFile().setExecutable(false, false)

            val entry = LocalFilesystem.entry(file)

            // Mode should have read/write for owner
            assertTrue(entry.mode and 0b110000000 != 0)
        }
    }
}
