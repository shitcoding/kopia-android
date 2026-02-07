package org.kopiaKt.snapshot.restore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.EntryType
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.getLastModifiedTime

@DisplayName("Metadata Fidelity Tests")
@DisabledOnOs(OS.WINDOWS)
class MetadataFidelityTest {

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

    // --- Helper Functions ---

    private fun makeFileEntry(
        name: String,
        size: Long,
        permissions: Int = 420, // 0o644
        modTime: Instant = Instant.now()
    ) = DirEntry(
        name = name.substringAfterLast('/'),
        type = EntryType.FILE,
        permissions = permissions,
        fileSize = size,
        modTime = modTime
    )

    private fun makeDirEntry(
        name: String,
        permissions: Int = 493, // 0o755
        modTime: Instant = Instant.now()
    ) = DirEntry(
        name = name.substringAfterLast('/'),
        type = EntryType.DIRECTORY,
        permissions = permissions,
        fileSize = 0,
        modTime = modTime
    )

    @Nested
    @DisplayName("Permission Modes")
    inner class PermissionModeTests {

        @Test
        fun `should preserve exact permission mode 0644`() = runBlocking {
            val content = "test content".toByteArray()
            val mode644 = 420 // 0o644
            val entry = makeFileEntry("file644.txt", content.size.toLong(), permissions = mode644)

            output.writeFile("file644.txt", entry, content.inputStream())

            val path = tempDir.resolve("file644.txt")
            val actualPermissions = path.getPosixFilePermissions()

            val expectedPermissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
            )

            assertThat(actualPermissions).isEqualTo(expectedPermissions)
        }

        @Test
        fun `should preserve exact permission mode 0755`() = runBlocking {
            val content = "executable content".toByteArray()
            val mode755 = 493 // 0o755
            val entry = makeFileEntry("file755.sh", content.size.toLong(), permissions = mode755)

            output.writeFile("file755.sh", entry, content.inputStream())

            val path = tempDir.resolve("file755.sh")
            val actualPermissions = path.getPosixFilePermissions()

            val expectedPermissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            )

            assertThat(actualPermissions).isEqualTo(expectedPermissions)
        }

        @Test
        fun `should preserve exact permission mode 0600`() = runBlocking {
            val content = "private content".toByteArray()
            val mode600 = 384 // 0o600
            val entry = makeFileEntry("file600.key", content.size.toLong(), permissions = mode600)

            output.writeFile("file600.key", entry, content.inputStream())

            val path = tempDir.resolve("file600.key")
            val actualPermissions = path.getPosixFilePermissions()

            val expectedPermissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            )

            assertThat(actualPermissions).isEqualTo(expectedPermissions)
        }
    }

    @Nested
    @DisplayName("Timestamps")
    inner class TimestampTests {

        @Test
        fun `should preserve modification time within 1 second tolerance`() = runBlocking {
            val content = "timestamped content".toByteArray()
            // Use a specific past timestamp to avoid filesystem rounding issues
            val expectedModTime = Instant.parse("2024-06-15T10:30:00Z")
            val entry = makeFileEntry(
                "timestamped.txt",
                content.size.toLong(),
                modTime = expectedModTime
            )

            output.writeFile("timestamped.txt", entry, content.inputStream())

            val path = tempDir.resolve("timestamped.txt")
            val actualModTime = path.getLastModifiedTime().toInstant()

            val delta = Duration.between(expectedModTime, actualModTime).abs()
            assertThat(delta.toMillis()).isLessThan(1000L)
        }
    }

    @Nested
    @DisplayName("Parent Directory Attributes (Go issue #4324)")
    inner class ParentDirectoryTests {

        @Test
        fun `should set correct permissions on restored parent directories`() = runBlocking {
            val dirMode = 493 // 0o755
            val dirEntry = makeDirEntry("subdir", permissions = dirMode)

            output.beginDirectory("subdir", dirEntry)

            // Write a file inside the directory
            val content = "nested file".toByteArray()
            val fileEntry = makeFileEntry("file.txt", content.size.toLong())
            output.writeFile("subdir/file.txt", fileEntry, content.inputStream())

            // finishDirectory applies the final attributes (permissions, mtime)
            output.finishDirectory("subdir", dirEntry)

            val dirPath = tempDir.resolve("subdir")
            val actualPermissions = dirPath.getPosixFilePermissions()

            val expectedPermissions = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            )

            assertThat(actualPermissions).isEqualTo(expectedPermissions)
        }

        @Test
        fun `should not set epoch timestamp on parent directories`() = runBlocking {
            // Use a non-epoch timestamp for the directory
            val dirModTime = Instant.parse("2024-03-20T15:45:00Z")
            val dirEntry = makeDirEntry("parentdir", modTime = dirModTime)

            output.beginDirectory("parentdir", dirEntry)
            output.finishDirectory("parentdir", dirEntry)

            val dirPath = tempDir.resolve("parentdir")
            val actualModTime = dirPath.getLastModifiedTime().toInstant()

            // The modification time must NOT be epoch (0)
            assertThat(actualModTime).isNotEqualTo(Instant.EPOCH)

            // It should be within 1 second of the specified time
            val delta = Duration.between(dirModTime, actualModTime).abs()
            assertThat(delta.toMillis()).isLessThan(1000L)
        }
    }
}
