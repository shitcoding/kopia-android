package org.kopiaKt.snapshot.restore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.EntryType
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

@DisplayName("Restore Path Traversal Protection")
class RestorePathTraversalTest {

    @TempDir
    lateinit var tempDir: Path

    @Nested
    @DisplayName("File Path Traversal")
    inner class FilePathTraversal {

        @Test
        fun `should reject file with dot-dot in path`() = runTest {
            val output = FilesystemOutput(tempDir)
            val content = "malicious".toByteArray()
            val entry = makeFileEntry("escape.txt", content.size.toLong())

            assertThrows<RestoreException> {
                output.writeFile("../escape.txt", entry, ByteArrayInputStream(content))
            }

            // Verify no file was created in parent directory
            assertThat(tempDir.parent.resolve("escape.txt").exists()).isFalse()
        }

        @Test
        fun `should reject file with absolute path`() = runTest {
            val output = FilesystemOutput(tempDir)
            val content = "malicious".toByteArray()
            val entry = makeFileEntry("shadow", content.size.toLong())

            assertThrows<RestoreException> {
                output.writeFile("/etc/shadow", entry, ByteArrayInputStream(content))
            }
        }

        @Test
        fun `should reject file with multiple dot-dot sequences`() = runTest {
            val output = FilesystemOutput(tempDir)
            val content = "malicious".toByteArray()
            val entry = makeFileEntry("passwd", content.size.toLong())

            assertThrows<RestoreException> {
                output.writeFile("../../etc/passwd", entry, ByteArrayInputStream(content))
            }
        }
    }

    @Nested
    @DisplayName("Directory Path Traversal")
    inner class DirectoryPathTraversal {

        @Test
        fun `should reject directory with dot-dot`() = runTest {
            val output = FilesystemOutput(tempDir)
            val entry = makeDirEntry("escapedir")

            assertThrows<RestoreException> {
                output.beginDirectory("../escapedir", entry)
            }

            // Verify no directory was created in parent
            assertThat(tempDir.parent.resolve("escapedir").exists()).isFalse()
        }
    }

    @Nested
    @DisplayName("Symlink Path Traversal")
    inner class SymlinkPathTraversal {

        @Test
        fun `should reject symlink targeting outside restore root`() = runTest {
            val output = FilesystemOutput(tempDir)
            val entry = makeDirEntry("link.txt")

            assertThrows<RestoreException> {
                output.createSymlink("link.txt", entry, "../../../etc/passwd")
            }

            // Verify no symlink was created
            assertThat(tempDir.resolve("link.txt").exists()).isFalse()
        }
    }

    @Nested
    @DisplayName("Symlink-in-Path Traversal")
    inner class SymlinkInPathTraversal {

        @Test
        fun `should reject file write through symlink pointing outside root`() = runTest {
            val output = FilesystemOutput(tempDir)

            // Create a symlink inside restore root pointing to parent directory
            val linkPath = tempDir.resolve("escape_link")
            Files.createSymbolicLink(linkPath, tempDir.parent)

            val content = "malicious".toByteArray()
            val entry = makeFileEntry("payload.txt", content.size.toLong())

            assertThrows<RestoreException> {
                output.writeFile("escape_link/payload.txt", entry, ByteArrayInputStream(content))
            }

            // Verify no file was written outside restore root
            assertThat(tempDir.parent.resolve("payload.txt").exists()).isFalse()
        }

        @Test
        fun `should reject directory creation through symlink pointing outside root`() = runTest {
            val output = FilesystemOutput(tempDir)

            // Create a symlink inside restore root pointing to parent directory
            val linkPath = tempDir.resolve("escape_link")
            Files.createSymbolicLink(linkPath, tempDir.parent)

            val entry = makeDirEntry("subdir")

            assertThrows<RestoreException> {
                output.beginDirectory("escape_link/subdir", entry)
            }
        }

        @Test
        fun `should allow file write through symlink pointing within root`() = runTest {
            val output = FilesystemOutput(tempDir)

            // Create a real subdirectory and a symlink pointing to it
            val realDir = tempDir.resolve("real_subdir")
            Files.createDirectories(realDir)
            val linkPath = tempDir.resolve("link_to_subdir")
            Files.createSymbolicLink(linkPath, realDir)

            val content = "safe content".toByteArray()
            val entry = makeFileEntry("file.txt", content.size.toLong())

            output.writeFile("link_to_subdir/file.txt", entry, ByteArrayInputStream(content))
            assertThat(realDir.resolve("file.txt").exists()).isTrue()
        }
    }

    @Nested
    @DisplayName("Legitimate Paths")
    inner class LegitimatePaths {

        @Test
        fun `should allow legitimate path containing dots`() = runTest {
            val output = FilesystemOutput(tempDir)
            val content = "dotted filename".toByteArray()
            val entry = makeFileEntry("my.file.txt", content.size.toLong())

            output.writeFile("my.file.txt", entry, ByteArrayInputStream(content))

            assertThat(tempDir.resolve("my.file.txt").exists()).isTrue()
        }

        @Test
        fun `should allow legitimate nested path`() = runTest {
            val output = FilesystemOutput(tempDir)
            val content = "nested content".toByteArray()
            val entry = makeFileEntry("file.txt", content.size.toLong())

            output.writeFile("subdir/deep/file.txt", entry, ByteArrayInputStream(content))

            assertThat(tempDir.resolve("subdir/deep/file.txt").exists()).isTrue()
        }
    }

    // --- Helper Functions ---

    private fun makeFileEntry(name: String, size: Long) = DirEntry(
        name = name.substringAfterLast('/'),
        type = EntryType.FILE,
        permissions = 420, // 0o644
        fileSize = size,
        modTime = Instant.now(),
    )

    private fun makeDirEntry(name: String) = DirEntry(
        name = name.substringAfterLast('/'),
        type = EntryType.DIRECTORY,
        permissions = 493, // 0o755
        fileSize = 0,
        modTime = Instant.now(),
    )
}
