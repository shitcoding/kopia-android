package org.kopiaKt.snapshot.snapshotfs

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.manifest.EntryMetadata
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.`object`.ObjectReader
import org.kopiaKt.core.repository.ClientOptions
import org.kopiaKt.core.repository.Repository
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.EntryType
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.SnapshotManifest
import org.kopiaKt.snapshot.model.SourceInfo
import java.time.Instant
import org.kopiaKt.snapshot.model.EntryType as SnapshotEntryType

class RepositoryFSTest {

    @Test
    fun `entryFromDirEntry creates file entry`() {
        val repo = MockRepository()
        val dirEntry = DirEntry(
            name = "test.txt",
            type = SnapshotEntryType.FILE,
            permissions = 420, // 0o644
            fileSize = 1000,
            modTime = Instant.now(),
            objectId = "abc123"
        )

        val entry = entryFromDirEntry(repo, dirEntry)

        assertThat(entry.name).isEqualTo("test.txt")
        assertThat(entry.type).isEqualTo(EntryType.FILE)
        assertThat(entry.size).isEqualTo(1000)
        assertThat(entry.isFile()).isTrue()
        assertThat(entry.isDirectory()).isFalse()
    }

    @Test
    fun `entryFromDirEntry creates directory entry`() {
        val repo = MockRepository()
        val dirEntry = DirEntry(
            name = "mydir",
            type = SnapshotEntryType.DIRECTORY,
            permissions = 493, // 0o755
            objectId = "dir123"
        )

        val entry = entryFromDirEntry(repo, dirEntry)

        assertThat(entry.name).isEqualTo("mydir")
        assertThat(entry.type).isEqualTo(EntryType.DIRECTORY)
        assertThat(entry.isDirectory()).isTrue()
        assertThat(entry.isFile()).isFalse()
    }

    @Test
    fun `entryFromDirEntry creates symlink entry`() {
        val repo = MockRepository()
        val dirEntry = DirEntry(
            name = "link",
            type = SnapshotEntryType.SYMLINK,
            objectId = "link123"
        )

        val entry = entryFromDirEntry(repo, dirEntry)

        assertThat(entry.name).isEqualTo("link")
        assertThat(entry.type).isEqualTo(EntryType.SYMLINK)
        assertThat(entry.isSymlink()).isTrue()
    }

    @Test
    fun `RepositoryDirectory iterates children`() = runBlocking {
        val children = listOf(
            DirEntry(name = "file1.txt", type = SnapshotEntryType.FILE, fileSize = 100),
            DirEntry(name = "file2.txt", type = SnapshotEntryType.FILE, fileSize = 200),
            DirEntry(name = "subdir", type = SnapshotEntryType.DIRECTORY)
        )
        val dirManifest = DirManifest(entries = children)
        // Use valid hex object ID (kabcdef12 = k prefix + abcdef12 hex hash; 9 chars = odd, so k is prefix)
        val dirObjId = "kabcdef12"
        val repo = MockRepository(
            objects = mapOf(dirObjId to Json.encodeToString(dirManifest).toByteArray())
        )

        val dirEntry = DirEntry(
            name = "testdir",
            type = SnapshotEntryType.DIRECTORY,
            objectId = dirObjId
        )
        val dir = entryFromDirEntry(repo, dirEntry) as org.kopiaKt.snapshot.fs.Directory

        val entries = dir.readEntries()
        assertThat(entries).hasSize(3)

        val fileNames = entries.map { it.name }.toSet()
        assertThat(fileNames).contains("file1.txt")
        assertThat(fileNames).contains("file2.txt")
        assertThat(fileNames).contains("subdir")
    }

    @Test
    fun `RepositoryDirectory finds child by name`() = runBlocking {
        val children = listOf(
            DirEntry(name = "target.txt", type = SnapshotEntryType.FILE, fileSize = 500),
            DirEntry(name = "other.txt", type = SnapshotEntryType.FILE, fileSize = 100)
        )
        val dirManifest = DirManifest(entries = children)
        // Use valid hex object ID (k1234abcd = k prefix + 1234abcd hex hash; 9 chars = odd, so k is prefix)
        val dirObjId = "k1234abcd"
        val repo = MockRepository(
            objects = mapOf(dirObjId to Json.encodeToString(dirManifest).toByteArray())
        )

        val dirEntry = DirEntry(
            name = "testdir",
            type = SnapshotEntryType.DIRECTORY,
            objectId = dirObjId
        )
        val dir = entryFromDirEntry(repo, dirEntry) as org.kopiaKt.snapshot.fs.Directory

        val found = dir.child("target.txt")
        assertThat(found).isNotNull()
        assertThat(found!!.name).isEqualTo("target.txt")
        assertThat(found.size).isEqualTo(500)

        val notFound = dir.child("nonexistent.txt")
        assertThat(notFound).isNull()
    }

    @Test
    fun `RepositoryFile opens and reads content`() = runBlocking {
        val content = "File content here"
        // Use valid hex object ID (pabcdef12 = p prefix + abcdef12 hex; 9 chars = odd, so p is prefix)
        val fileObjId = "pabcdef12"
        val repo = MockRepository(
            objects = mapOf(fileObjId to content.toByteArray())
        )

        val fileEntry = DirEntry(
            name = "test.txt",
            type = SnapshotEntryType.FILE,
            fileSize = content.length.toLong(),
            objectId = fileObjId
        )
        val file = entryFromDirEntry(repo, fileEntry)
        assertThat(file).isInstanceOf(org.kopiaKt.snapshot.fs.File::class.java)

        val inputStream = (file as org.kopiaKt.snapshot.fs.File).open()
        val readContent = inputStream.bufferedReader().readText()
        inputStream.close()

        assertThat(readContent).isEqualTo(content)
    }

    @Test
    fun `RepositorySymlink reads target`() = runBlocking {
        val target = "/path/to/target"
        // Use valid hex object ID (p12345678 = p prefix + 12345678 hex; 9 chars = odd, so p is prefix)
        val linkObjId = "p12345678"
        val repo = MockRepository(
            objects = mapOf(linkObjId to target.toByteArray())
        )

        val symlinkEntry = DirEntry(
            name = "mylink",
            type = SnapshotEntryType.SYMLINK,
            objectId = linkObjId
        )
        val symlink = entryFromDirEntry(repo, symlinkEntry)
        assertThat(symlink).isInstanceOf(org.kopiaKt.snapshot.fs.Symlink::class.java)

        val readTarget = (symlink as org.kopiaKt.snapshot.fs.Symlink).readlink()
        assertThat(readTarget).isEqualTo(target)
    }

    @Test
    fun `snapshotRoot returns root entry from manifest`() {
        val repo = MockRepository()
        val rootEntry = DirEntry(
            name = "",
            type = SnapshotEntryType.DIRECTORY,
            objectId = "rootobj"
        )
        val manifest = SnapshotManifest(
            id = "snap1",
            source = SourceInfo(host = "localhost", userName = "user", path = "/home/user"),
            startTime = Instant.now(),
            rootEntry = rootEntry
        )

        val entry = snapshotRoot(repo, manifest)

        assertThat(entry.isDirectory()).isTrue()
        assertThat(entry.name).isEqualTo("")
    }

    @Test
    fun `directoryEntry creates directory for object ID`() = runBlocking {
        val children = listOf(
            DirEntry(name = "child.txt", type = SnapshotEntryType.FILE)
        )
        val dirManifest = DirManifest(entries = children)
        // Use valid hex object ID (k87654321 = k prefix + 87654321 hex; 9 chars = odd, so k is prefix)
        val dirObjId = "k87654321"
        val repo = MockRepository(
            objects = mapOf(dirObjId to Json.encodeToString(dirManifest).toByteArray())
        )

        val objectId = ObjectId.parse(dirObjId)
        val dir = directoryEntry(repo, objectId)

        assertThat(dir.name).isEqualTo("/")
        assertThat(dir.isDirectory()).isTrue()

        val entries = dir.readEntries()
        assertThat(entries).hasSize(1)
        assertThat(entries[0].name).isEqualTo("child.txt")
    }

    @Test
    fun `isDirectoryId detects directory prefix`() {
        // 'k' prefix indicates directory
        val dirOid = ObjectId.direct(ContentId.parse("kabc123"))
        assertThat(isDirectoryId(dirOid)).isTrue()

        // 'p' prefix is for regular content
        val fileOid = ObjectId.direct(ContentId.parse("pabc123"))
        assertThat(isDirectoryId(fileOid)).isFalse()
    }

    @Test
    fun `isDirectoryId handles indirect objects`() {
        // Indirect directory - uses ContentId with 'k' prefix and indirection level
        val dirContentId = ContentId.parse("kabc123")
        val indirectDirOid = ObjectId.indirect(dirContentId, 1)
        assertThat(isDirectoryId(indirectDirOid)).isTrue()

        // Indirect file - uses ContentId with 'p' prefix and indirection level
        val fileContentId = ContentId.parse("pabc123")
        val indirectFileOid = ObjectId.indirect(fileContentId, 1)
        assertThat(isDirectoryId(indirectFileOid)).isFalse()
    }

    @Test
    fun `RepositoryEntry extracts metadata correctly`() {
        val repo = MockRepository()
        val now = Instant.now()
        val dirEntry = DirEntry(
            name = "test.txt",
            type = SnapshotEntryType.FILE,
            permissions = 420, // 0o644
            fileSize = 1234,
            modTime = now,
            userId = 1000,
            groupId = 100,
            objectId = "obj123"
        )

        val entry = entryFromDirEntry(repo, dirEntry)

        assertThat(entry.name).isEqualTo("test.txt")
        assertThat(entry.type).isEqualTo(EntryType.FILE)
        assertThat(entry.size).isEqualTo(1234)
        assertThat(entry.modTime).isEqualTo(now)
        assertThat(entry.mode).isEqualTo(420)
        assertThat(entry.owner.userId).isEqualTo(1000)
        assertThat(entry.owner.groupId).isEqualTo(100)
    }

    // --- Mock Implementation ---

    private class MockRepository(
        private val objects: Map<String, ByteArray> = emptyMap()
    ) : Repository {
        override fun openObject(objectId: ObjectId): ObjectReader {
            val data = objects[objectId.toString()]
                ?: throw RuntimeException("Object not found: $objectId")
            return MockObjectReader(data)
        }

        override suspend fun readObject(objectId: ObjectId): ByteArray {
            return objects[objectId.toString()]
                ?: throw RuntimeException("Object not found: $objectId")
        }

        override suspend fun verifyObject(objectId: ObjectId): List<ContentId> = emptyList()
        override suspend fun <T> getManifest(id: ManifestId, serializer: kotlinx.serialization.KSerializer<T>): Pair<T, EntryMetadata> =
            throw UnsupportedOperationException()
        override suspend fun findManifests(labels: Map<String, String>): List<EntryMetadata> = emptyList()
        override suspend fun contentInfo(contentId: ContentId) = null
        override fun time(): Instant = Instant.now()
        override fun clientOptions(): ClientOptions = ClientOptions()
        override suspend fun newWriter(options: WriteSessionOptions): RepositoryWriter =
            throw UnsupportedOperationException()
        override fun updateDescription(description: String) {}
        override suspend fun refresh() {}
        override fun close() {}
    }

    private class MockObjectReader(private val data: ByteArray) : ObjectReader {
        override suspend fun read(offset: Long, length: Int): ByteArray {
            val start = offset.toInt().coerceIn(0, data.size)
            val end = if (length < 0) data.size else (start + length).coerceAtMost(data.size)
            return data.copyOfRange(start, end)
        }

        override suspend fun length(): Long = data.size.toLong()
        override fun close() {}
    }
}
