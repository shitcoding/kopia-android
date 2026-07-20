package org.kopiaKt.snapshot.fs

import kotlinx.coroutines.runBlocking
import kotlin.io.path.ExperimentalPathApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.policy.FilesPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/**
 * Tests for IgnoreFS wrapper.
 */
@OptIn(ExperimentalPathApi::class)
class IgnoreFSTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("kopiaKt-ignorefs-test")
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Nested
    inner class BasicFiltering {
        @Test
        fun `filters files by pattern`() = runBlocking {
            // Create test files
            tempDir.resolve("keep.txt").writeText("keep")
            tempDir.resolve("ignore.log").writeText("ignore")
            tempDir.resolve("also.log").writeText("also ignore")

            val dir = LocalFilesystem.directory(tempDir)
            val matchers = WildcardMatcher.parseAll(listOf("*.log"))
            val filtered = IgnoreFS.wrap(dir, matchers)

            val entries = filtered.readEntries()

            assertEquals(1, entries.size)
            assertEquals("keep.txt", entries[0].name)
        }

        @Test
        fun `filters directories by pattern`() = runBlocking {
            tempDir.resolve("keep").createDirectories()
            tempDir.resolve("node_modules").createDirectories()
            tempDir.resolve(".git").createDirectories()

            val dir = LocalFilesystem.directory(tempDir)
            val matchers = WildcardMatcher.parseAll(listOf("node_modules/", ".git/"))
            val filtered = IgnoreFS.wrap(dir, matchers)

            val entries = filtered.readEntries()

            assertEquals(1, entries.size)
            assertEquals("keep", entries[0].name)
        }

        @Test
        fun `child returns null for ignored entry`() = runBlocking {
            tempDir.resolve("keep.txt").writeText("keep")
            tempDir.resolve("ignore.log").writeText("ignore")

            val dir = LocalFilesystem.directory(tempDir)
            val matchers = WildcardMatcher.parseAll(listOf("*.log"))
            val filtered = IgnoreFS.wrap(dir, matchers)

            assertNotNull(filtered.child("keep.txt"))
            assertNull(filtered.child("ignore.log"))
        }
    }

    @Nested
    inner class PatternMatching {
        @Test
        fun `double star matches nested paths`() = runBlocking {
            tempDir.resolve("src/main/test.log").apply {
                parent.createDirectories()
                writeText("log")
            }
            tempDir.resolve("src/main/Test.kt").apply {
                writeText("code")
            }

            val dir = LocalFilesystem.directory(tempDir)
            val matchers = WildcardMatcher.parseAll(listOf("**/*.log"))
            val filtered = IgnoreFS.wrap(dir, matchers)

            val allFiles = collectAllFiles(filtered)

            assertEquals(1, allFiles.size)
            assertTrue(allFiles[0].endsWith("Test.kt"))
        }

        @Test
        fun `negation pattern un-ignores files`() = runBlocking {
            tempDir.resolve("debug.log").writeText("debug")
            tempDir.resolve("error.log").writeText("error")
            tempDir.resolve("important.log").writeText("important")

            val dir = LocalFilesystem.directory(tempDir)
            val matchers = WildcardMatcher.parseAll(listOf(
                "*.log",
                "!important.log"
            ))
            val filtered = IgnoreFS.wrap(dir, matchers)

            val entries = filtered.readEntries()

            assertEquals(1, entries.size)
            assertEquals("important.log", entries[0].name)
        }
    }

    @Nested
    inner class DotIgnoreFiles {
        @Test
        fun `loads patterns from kopiaignore file`() = runBlocking {
            // Create .kopiaignore
            tempDir.resolve(".kopiaignore").writeText("*.tmp\n*.bak\n")

            // Create test files
            tempDir.resolve("keep.txt").writeText("keep")
            tempDir.resolve("ignore.tmp").writeText("ignore")
            tempDir.resolve("also.bak").writeText("also ignore")

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, dotIgnoreFiles = listOf(".kopiaignore"))

            val entries = filtered.readEntries()
            val names = entries.map { it.name }.filter { it != ".kopiaignore" }

            assertEquals(1, names.size)
            assertEquals("keep.txt", names[0])
        }

        @Test
        fun `inherits patterns from parent kopiaignore`() = runBlocking {
            // Root .kopiaignore
            tempDir.resolve(".kopiaignore").writeText("*.log\n")

            // Create subdirectory with files
            val subdir = tempDir.resolve("subdir")
            subdir.createDirectories()
            subdir.resolve("keep.txt").writeText("keep")
            subdir.resolve("ignore.log").writeText("ignore")

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, dotIgnoreFiles = listOf(".kopiaignore"))

            // Navigate to subdir
            val subdirEntry = filtered.child("subdir") as Directory
            val entries = subdirEntry.readEntries()

            assertEquals(1, entries.size)
            assertEquals("keep.txt", entries[0].name)
        }

        @Test
        fun `subdirectory kopiaignore adds patterns`() = runBlocking {
            // Root .kopiaignore
            tempDir.resolve(".kopiaignore").writeText("*.log\n")

            // Create subdirectory with its own .kopiaignore
            val subdir = tempDir.resolve("subdir")
            subdir.createDirectories()
            subdir.resolve(".kopiaignore").writeText("*.tmp\n")
            subdir.resolve("keep.txt").writeText("keep")
            subdir.resolve("ignore.log").writeText("ignored by parent")
            subdir.resolve("also.tmp").writeText("ignored by local")

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, dotIgnoreFiles = listOf(".kopiaignore"))

            val subdirEntry = filtered.child("subdir") as Directory
            val entries = subdirEntry.readEntries().filter { it.name != ".kopiaignore" }

            assertEquals(1, entries.size)
            assertEquals("keep.txt", entries[0].name)
        }
    }

    @Nested
    inner class FilesPolicy {
        @Test
        fun `applies policy ignore rules`() = runBlocking {
            tempDir.resolve("keep.txt").writeText("keep")
            tempDir.resolve("ignore.log").writeText("ignore")

            val policy = FilesPolicy(ignoreRules = listOf("*.log"))

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, policy)

            val entries = filtered.readEntries()

            assertEquals(1, entries.size)
            assertEquals("keep.txt", entries[0].name)
        }

        @Test
        fun `applies max file size filter`() = runBlocking {
            tempDir.resolve("small.txt").writeText("small")
            tempDir.resolve("large.txt").writeText("x".repeat(1000))

            val policy = FilesPolicy(maxFileSize = 100)

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, policy)

            val entries = filtered.readEntries()

            assertEquals(1, entries.size)
            assertEquals("small.txt", entries[0].name)
        }

        @Test
        fun `combines policy rules with dot ignore files`() = runBlocking {
            tempDir.resolve(".kopiaignore").writeText("*.tmp\n")
            tempDir.resolve("keep.txt").writeText("keep")
            tempDir.resolve("ignore.log").writeText("from policy")
            tempDir.resolve("also.tmp").writeText("from dotfile")

            val policy = FilesPolicy(
                ignoreRules = listOf("*.log"),
                dotIgnoreFiles = listOf(".kopiaignore")
            )

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, policy)

            val entries = filtered.readEntries().filter { it.name != ".kopiaignore" }

            assertEquals(1, entries.size)
            assertEquals("keep.txt", entries[0].name)
        }
    }

    @Nested
    inner class NoParentFlags {
        @Test
        fun `noParentIgnoreRules stops pattern inheritance`() = runBlocking {
            // Root .kopiaignore
            tempDir.resolve(".kopiaignore").writeText("*.log\n")

            // Subdirectory
            val subdir = tempDir.resolve("subdir")
            subdir.createDirectories()
            subdir.resolve("keep.log").writeText("keep")
            subdir.resolve("keep.txt").writeText("keep")

            val policy = FilesPolicy(
                dotIgnoreFiles = listOf(".kopiaignore"),
                noParentIgnoreRules = true
            )

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, policy)

            val subdirEntry = filtered.child("subdir") as Directory
            val entries = subdirEntry.readEntries()

            // Without parent rules, *.log should NOT be ignored
            assertEquals(2, entries.size)
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `empty directory works correctly`() = runBlocking {
            val emptyDir = tempDir.resolve("empty")
            emptyDir.createDirectories()

            val dir = LocalFilesystem.directory(emptyDir)
            val filtered = IgnoreFS.wrap(dir, listOf(WildcardMatcher.parse("*.log")))

            val entries = filtered.readEntries()

            assertTrue(entries.isEmpty())
        }

        @Test
        fun `all files ignored results in empty list`() = runBlocking {
            tempDir.resolve("a.log").writeText("a")
            tempDir.resolve("b.log").writeText("b")

            val dir = LocalFilesystem.directory(tempDir)
            val matchers = WildcardMatcher.parseAll(listOf("*.log"))
            val filtered = IgnoreFS.wrap(dir, matchers)

            val entries = filtered.readEntries()

            assertTrue(entries.isEmpty())
        }

        @Test
        fun `no patterns means nothing filtered`() = runBlocking {
            tempDir.resolve("a.txt").writeText("a")
            tempDir.resolve("b.log").writeText("b")
            tempDir.resolve("c").createDirectories()

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, emptyList())

            val entries = filtered.readEntries()

            assertEquals(3, entries.size)
        }

        @Test
        fun `deeply nested filtering works`() = runBlocking {
            // Create deep structure with .kopiaignore at each level
            val path = tempDir.resolve("a/b/c/d")
            path.createDirectories()
            tempDir.resolve("a/.kopiaignore").writeText("*.log\n")
            tempDir.resolve("a/b/.kopiaignore").writeText("*.tmp\n")
            tempDir.resolve("a/b/c/.kopiaignore").writeText("*.bak\n")

            path.resolve("keep.txt").writeText("keep")
            path.resolve("ignore.log").writeText("log")
            path.resolve("ignore.tmp").writeText("tmp")
            path.resolve("ignore.bak").writeText("bak")

            val dir = LocalFilesystem.directory(tempDir)
            val filtered = IgnoreFS.wrap(dir, dotIgnoreFiles = listOf(".kopiaignore"))

            // Navigate to deepest level
            val a = filtered.child("a") as Directory
            val b = a.child("b") as Directory
            val c = b.child("c") as Directory
            val d = c.child("d") as Directory

            val entries = d.readEntries()

            assertEquals(1, entries.size)
            assertEquals("keep.txt", entries[0].name)
        }
    }

    // Helper function to collect all files recursively
    private suspend fun collectAllFiles(dir: Directory, basePath: String = ""): List<String> {
        val files = mutableListOf<String>()

        dir.iterate().use { iterator ->
            while (true) {
                val entry = iterator.next() ?: break
                val path = if (basePath.isEmpty()) entry.name else "$basePath/${entry.name}"

                when {
                    entry.isFile() -> files.add(path)
                    entry is Directory -> files.addAll(collectAllFiles(entry, path))
                }
            }
        }

        return files
    }
}
