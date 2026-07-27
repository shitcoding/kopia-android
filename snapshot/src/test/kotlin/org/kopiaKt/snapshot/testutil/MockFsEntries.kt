package org.kopiaKt.snapshot.testutil

import kotlinx.coroutines.delay
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.DirectoryIterator
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.fs.File
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.kopiaKt.snapshot.fs.Symlink
import java.io.InputStream
import java.time.Instant

/**
 * Base mock implementation of [Entry] for use in tests.
 */
internal open class MockEntry(
    override val name: String,
    override val type: EntryType,
    override val size: Long = 0,
    override val modTime: Instant = Instant.now(),
    override val mode: Int = 420, // 0o644
    override val owner: OwnerInfo = OwnerInfo.EMPTY,
    override val device: DeviceInfo = DeviceInfo.EMPTY,
    override val localFilesystemPath: String = "",
) : Entry

/**
 * Mock file that returns the given [content] from [open].
 */
internal class MockFile(
    name: String,
    private val content: ByteArray,
    modTime: Instant = Instant.now(),
    mode: Int = 420,
    device: DeviceInfo = DeviceInfo.EMPTY,
) : MockEntry(name, EntryType.FILE, content.size.toLong(), modTime, mode, device = device),
    File {
    override suspend fun open(): InputStream = content.inputStream()
}

/**
 * Mock file that introduces a [delayMs] delay before returning content.
 * Useful for testing cancellation and parallelism.
 */
internal class SlowMockFile(
    name: String,
    private val content: ByteArray,
    private val delayMs: Long,
    modTime: Instant = Instant.now(),
    mode: Int = 420,
) : MockEntry(name, EntryType.FILE, content.size.toLong(), modTime, mode),
    File {
    override suspend fun open(): InputStream {
        delay(delayMs)
        return content.inputStream()
    }
}

/**
 * Mock directory containing the given [entries].
 */
internal class MockDirectory(
    name: String,
    private val entries: List<Entry>,
    modTime: Instant = Instant.now(),
    mode: Int = 493, // 0o755
    device: DeviceInfo = DeviceInfo.EMPTY,
) : MockEntry(name, EntryType.DIRECTORY, 0, modTime, mode, device = device),
    Directory {
    override suspend fun child(name: String): Entry? = entries.find { it.name == name }
    override suspend fun iterate(): DirectoryIterator = MockIterator(entries)
    override fun supportsMultipleIterations(): Boolean = true
}

/**
 * Mock symbolic link pointing to [target].
 */
internal class MockSymlink(
    name: String,
    private val target: String,
    modTime: Instant = Instant.now(),
) : MockEntry(name, EntryType.SYMLINK, 0, modTime),
    Symlink {
    override suspend fun readlink(): String = target
    override suspend fun resolve(): Entry? = null
}

/**
 * Mock file that throws [error] when opened. Useful for testing error handling paths.
 */
internal class FailingFile(
    name: String,
    private val error: Throwable,
) : MockEntry(name, EntryType.FILE, 100),
    File {
    override suspend fun open(): InputStream = throw error
}

/**
 * Simple iterator over a fixed list of entries.
 */
internal class MockIterator(entries: List<Entry>) : DirectoryIterator {
    private val iterator = entries.iterator()
    override suspend fun next(): Entry? = if (iterator.hasNext()) iterator.next() else null
    override fun close() {}
}
