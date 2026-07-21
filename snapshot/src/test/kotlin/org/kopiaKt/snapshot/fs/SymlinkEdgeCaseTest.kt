package org.kopiaKt.snapshot.fs

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/**
 * Edge-case tests for symlink handling in LocalFilesystem.
 *
 * Covers broken symlinks, circular references, parent-directory links,
 * deeply nested chains, and absolute vs relative target paths.
 */
@OptIn(ExperimentalPathApi::class)
@DisabledOnOs(OS.WINDOWS)
class SymlinkEdgeCaseTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("kopiaKt-symlink-edge")
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Nested
    inner class BrokenSymlinks {

        @Test
        fun `should handle broken symlink`() = runBlocking {
            // Create a symlink pointing to a target that does not exist
            val link = tempDir.resolve("broken-link")
            link.createSymbolicLinkPointingTo(tempDir.resolve("nonexistent-target"))

            val entry = LocalFilesystem.entry(link)

            // The entry itself should be recognized as a symlink
            assertThat(entry.type).isEqualTo(EntryType.SYMLINK)
            assertThat(entry.isSymlink()).isTrue()
            assertThat(entry).isInstanceOf(Symlink::class.java)

            // readlink() should return the target path even though target is missing
            val symlink = entry as Symlink
            assertThat(symlink.readlink()).endsWith("nonexistent-target")

            // resolve() should return null for a broken symlink
            val resolved = symlink.resolve()
            assertThat(resolved).isNull()
        }
    }

    @Nested
    inner class CircularSymlinks {

        @Test
        fun `should detect circular symlink`() = runBlocking {
            // Create circular chain: linkA -> linkB -> linkA
            val linkA = tempDir.resolve("linkA")
            val linkB = tempDir.resolve("linkB")

            // Use Files.createSymbolicLink directly because Kotlin's extension
            // requires the target to exist for type inference, but we need both
            // targets to be symlinks pointing at each other.
            Files.createSymbolicLink(linkA, linkB)
            Files.createSymbolicLink(linkB, linkA)

            val entry = LocalFilesystem.entry(linkA)
            assertThat(entry.type).isEqualTo(EntryType.SYMLINK)

            val symlink = entry as Symlink

            // Circular resolution should throw SymlinkLoopException
            try {
                symlink.resolve()
                fail("Expected SymlinkLoopException but resolve() returned normally")
            } catch (e: SymlinkLoopException) {
                // Expected: circular symlink detected
                assertThat(e.message).contains("Maximum symlink depth")
            }
        }
    }

    @Nested
    inner class ParentDirectorySymlinks {

        @Test
        fun `should handle symlink to parent directory`() = runBlocking {
            // Create subdir/link -> .. (parent directory)
            val subdir = tempDir.resolve("subdir")
            subdir.createDirectories()

            val link = subdir.resolve("parent-link")
            // Create a relative symlink pointing to ".."
            Files.createSymbolicLink(link, Path.of(".."))

            val entry = LocalFilesystem.entry(link)
            assertThat(entry.type).isEqualTo(EntryType.SYMLINK)

            val symlink = entry as Symlink
            assertThat(symlink.readlink()).isEqualTo("..")

            // Resolving should yield a directory (the parent = tempDir)
            val resolved = symlink.resolve()
            assertThat(resolved).isNotNull()
            assertThat(resolved!!.isDirectory()).isTrue()
        }
    }

    @Nested
    inner class DeeplyNestedChains {

        @Test
        fun `should handle deeply nested symlink chain`() = runBlocking {
            // Create: linkA -> linkB -> linkC -> linkD -> realFile
            val realFile = tempDir.resolve("real.txt")
            realFile.writeText("deeply nested content")

            val linkD = tempDir.resolve("linkD")
            linkD.createSymbolicLinkPointingTo(realFile)

            val linkC = tempDir.resolve("linkC")
            linkC.createSymbolicLinkPointingTo(linkD)

            val linkB = tempDir.resolve("linkB")
            linkB.createSymbolicLinkPointingTo(linkC)

            val linkA = tempDir.resolve("linkA")
            linkA.createSymbolicLinkPointingTo(linkB)

            val entry = LocalFilesystem.entry(linkA) as Symlink

            // readlink() returns immediate target, not the final one
            assertThat(entry.readlink()).endsWith("linkB")

            // resolve() should follow the entire chain to the real file
            val resolved = entry.resolve()
            assertThat(resolved).isNotNull()
            assertThat(resolved!!.isFile()).isTrue()
            assertThat(resolved.name).isEqualTo("real.txt")
        }
    }

    @Nested
    inner class AbsoluteSymlinkTargets {

        @Test
        fun `should handle absolute symlink targets`() = runBlocking {
            val target = tempDir.resolve("abs-target.txt")
            target.writeText("absolute target content")

            // Create symlink with an absolute path target
            val link = tempDir.resolve("abs-link")
            link.createSymbolicLinkPointingTo(target.toAbsolutePath())

            val entry = LocalFilesystem.entry(link) as Symlink

            // readlink() should return an absolute path
            val targetPath = entry.readlink()
            assertThat(targetPath).startsWith("/")
            assertThat(targetPath).endsWith("abs-target.txt")

            // resolve() should reach the target
            val resolved = entry.resolve()
            assertThat(resolved).isNotNull()
            assertThat(resolved!!.isFile()).isTrue()
            assertThat(resolved.name).isEqualTo("abs-target.txt")
        }
    }

    @Nested
    inner class RelativeSymlinkTargets {

        @Test
        fun `should handle relative symlink targets`() = runBlocking {
            // Create subdir/target.txt and a sibling link using a relative path
            val subdir = tempDir.resolve("subdir")
            subdir.createDirectories()
            val target = subdir.resolve("target.txt")
            target.writeText("relative target content")

            // Create link in tempDir pointing relatively into subdir
            val link = tempDir.resolve("rel-link")
            Files.createSymbolicLink(link, Path.of("subdir", "target.txt"))

            val entry = LocalFilesystem.entry(link) as Symlink

            // readlink() should return the relative path as stored
            val targetPath = entry.readlink()
            assertThat(targetPath).doesNotContain(tempDir.toAbsolutePath().toString())
            assertThat(targetPath).isEqualTo("subdir/target.txt")

            // resolve() should still follow the relative path to the actual file
            val resolved = entry.resolve()
            assertThat(resolved).isNotNull()
            assertThat(resolved!!.isFile()).isTrue()
            assertThat(resolved.name).isEqualTo("target.txt")
        }
    }
}
