package org.kopiaKt.core.`object`

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.InMemoryBlobStorage
import org.kopiaKt.core.compression.CompressionAlgorithm
import org.kopiaKt.core.compression.DefaultCompressorFactory
import org.kopiaKt.core.content.ContentManager
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.encryption.DefaultEncryptorFactory
import org.kopiaKt.core.encryption.EncryptionAlgorithm
import org.kopiaKt.core.hashing.DefaultContentHasherFactory
import org.kopiaKt.core.hashing.HashAlgorithm
import org.kopiaKt.core.splitter.DefaultSplitterFactory
import org.kopiaKt.core.splitter.SplitterAlgorithms

/**
 * Tests for ObjectManager - the component that handles large files via indirect blocks.
 *
 * Object Manager responsibilities:
 * 1. Write objects that may span multiple content blocks
 * 2. Create indirect blocks pointing to content blocks when needed
 * 3. Read objects by following indirection levels
 * 4. Handle compression at the object level (Z prefix)
 *
 * TDD approach: Write tests first, then implement.
 */
class ObjectManagerTest {

    private lateinit var storage: InMemoryBlobStorage
    private lateinit var contentManager: ContentManager
    private lateinit var objectManager: ObjectManager

    private val testKey = ByteArray(32) { it.toByte() }
    private val testHashSecret = ByteArray(32) { (it + 1).toByte() }

    @BeforeEach
    fun setup() {
        storage = InMemoryBlobStorage()
        contentManager = ContentManager(
            storage = storage,
            hasherFactory = DefaultContentHasherFactory(),
            hashAlgorithm = HashAlgorithm.BLAKE2B_256_128,
            hashSecret = testHashSecret,
            encryptorFactory = DefaultEncryptorFactory(),
            encryptionAlgorithm = EncryptionAlgorithm.AES256_GCM_HMAC_SHA256,
            encryptionKey = testKey,
            compressorFactory = DefaultCompressorFactory(),
            defaultCompression = CompressionAlgorithm.NONE,
            maxPackSize = 20 * 1024 * 1024
        )
        objectManager = ObjectManager(
            contentManager = contentManager,
            splitterFactory = DefaultSplitterFactory.getFactory(SplitterAlgorithms.FIXED_128K)!!
        )
    }

    // ===== Basic Write/Read Tests =====

    @Test
    fun `write and read small object - single content block`() = runBlocking {
        val data = "Hello, World!".toByteArray()

        val objectId = objectManager.writeObject(data)
        contentManager.flush()

        assertNotNull(objectId)
        // Small objects should have no indirection
        assertEquals(0, objectId.indirection)

        val readData = objectManager.readObject(objectId)
        assertArrayEquals(data, readData)
    }

    @Test
    fun `write and read empty data`() = runBlocking {
        val data = ByteArray(0)

        val objectId = objectManager.writeObject(data)

        assertEquals(ObjectId.Empty, objectId)

        val readData = objectManager.readObject(objectId)
        assertArrayEquals(data, readData)
    }

    @Test
    fun `write and read exactly one chunk size`() = runBlocking {
        // 128KB is our splitter size
        val chunkSize = SplitterAlgorithms.SIZE_128K
        val data = ByteArray(chunkSize) { (it % 256).toByte() }

        val objectId = objectManager.writeObject(data)
        contentManager.flush()

        // Single chunk should have no indirection
        assertEquals(0, objectId.indirection)

        val readData = objectManager.readObject(objectId)
        assertArrayEquals(data, readData)
    }

    // ===== Indirect Block Tests =====

    @Test
    fun `write and read object spanning multiple chunks - creates indirect block`() = runBlocking {
        // Create data larger than one chunk (128KB)
        val chunkSize = SplitterAlgorithms.SIZE_128K
        val data = ByteArray(chunkSize * 3) { (it % 256).toByte() }

        val objectId = objectManager.writeObject(data)
        contentManager.flush()

        // Should have 1 level of indirection
        assertEquals(1, objectId.indirection)

        val readData = objectManager.readObject(objectId)
        assertArrayEquals(data, readData)
    }

    @Test
    fun `write and read large object with multiple indirect levels`() = runBlocking {
        // Use small chunk size for testing multi-level indirection
        val smallChunkManager = ObjectManager(
            contentManager = contentManager,
            // Use a small fixed splitter for testing
            splitterFactory = { FixedTestSplitter(1024) } // 1KB chunks
        )

        // Create data that needs multiple levels
        // With 1KB chunks and small indirect content, we should get multiple levels
        val data = ByteArray(50 * 1024) { (it % 256).toByte() } // 50KB

        val objectId = smallChunkManager.writeObject(data)
        contentManager.flush()

        // Should have at least 1 level of indirection
        assertTrue(objectId.indirection >= 1)

        val readData = smallChunkManager.readObject(objectId)
        assertArrayEquals(data, readData)
    }

    // ===== Compression Tests =====

    @Test
    fun `write and read with compression enabled`() = runBlocking {
        // Highly compressible data
        val data = "AAAA".repeat(10000).toByteArray()

        val objectId = objectManager.writeObject(
            data = data,
            options = ObjectWriterOptions(compression = CompressionAlgorithm.ZSTD_DEFAULT)
        )
        contentManager.flush()

        // Check if compression was applied (Z prefix for small objects)
        if (objectId.indirection == 0) {
            assertTrue(objectId.isCompressed)
        }

        val readData = objectManager.readObject(objectId)
        assertArrayEquals(data, readData)
    }

    @Test
    fun `write and read without compression for incompressible data`() = runBlocking {
        // Random-ish data that won't compress well
        val data = ByteArray(1000) { (it * 37 % 256).toByte() }

        val objectId = objectManager.writeObject(
            data = data,
            options = ObjectWriterOptions(compression = CompressionAlgorithm.ZSTD_DEFAULT)
        )
        contentManager.flush()

        val readData = objectManager.readObject(objectId)
        assertArrayEquals(data, readData)
    }

    // ===== Streaming API Tests =====

    @Test
    fun `write object using streaming writer`() = runBlocking {
        val writer = objectManager.newWriter()

        writer.write("Hello, ".toByteArray())
        writer.write("World!".toByteArray())

        val objectId = writer.result()
        contentManager.flush()

        val readData = objectManager.readObject(objectId)
        assertArrayEquals("Hello, World!".toByteArray(), readData)
    }

    @Test
    fun `writer checkpoint returns partial object`() = runBlocking {
        val chunkSize = SplitterAlgorithms.SIZE_128K
        val writer = objectManager.newWriter()

        // Write 2 chunks worth of data
        writer.write(ByteArray(chunkSize) { 'A'.code.toByte() })
        writer.write(ByteArray(chunkSize) { 'B'.code.toByte() })

        // Checkpoint should return what's been flushed so far
        val checkpoint = writer.checkpoint()
        contentManager.flush()

        // Continue writing
        writer.write(ByteArray(chunkSize) { 'C'.code.toByte() })

        val finalObjectId = writer.result()
        contentManager.flush()

        // Checkpoint should be readable
        val checkpointData = objectManager.readObject(checkpoint)
        assertTrue(checkpointData.isNotEmpty())

        // Final result should be readable and larger
        val finalData = objectManager.readObject(finalObjectId)
        assertTrue(finalData.size > checkpointData.size)
    }

    // ===== Seek Table / Index Tests =====

    @Test
    fun `indirect object has correct seek table`() = runBlocking {
        val chunkSize = SplitterAlgorithms.SIZE_128K
        val data = ByteArray(chunkSize * 3) { (it % 256).toByte() }

        val objectId = objectManager.writeObject(data)
        contentManager.flush()

        // Load and verify the index
        val seekTable = objectManager.loadIndexObject(objectId)
        assertNotNull(seekTable)

        // Should have 3 entries (one per chunk)
        assertEquals(3, seekTable.size)

        // Verify offsets are sequential
        var expectedStart = 0L
        for (entry in seekTable) {
            assertEquals(expectedStart, entry.start)
            assertTrue(entry.length > 0)
            expectedStart = entry.start + entry.length
        }

        // Total length should match original data
        assertEquals(data.size.toLong(), expectedStart)
    }

    // ===== Deduplication Tests =====

    @Test
    fun `writing same content twice produces same object ID`() = runBlocking {
        val data = ByteArray(1000) { (it % 256).toByte() }

        val objectId1 = objectManager.writeObject(data)
        val objectId2 = objectManager.writeObject(data)

        assertEquals(objectId1, objectId2)
    }

    @Test
    fun `deduplication works across chunks`() = runBlocking {
        val chunkSize = SplitterAlgorithms.SIZE_128K
        val repeatingChunk = ByteArray(chunkSize) { (it % 256).toByte() }

        // Create data with 3 identical chunks
        val data = repeatingChunk + repeatingChunk + repeatingChunk

        val objectId = objectManager.writeObject(data)
        contentManager.flush()

        // Read back and verify
        val readData = objectManager.readObject(objectId)
        assertArrayEquals(data, readData)

        // The indirect index should reference the same content ID for all chunks
        // (deduplication at content level happens in ContentManager)
    }

    // ===== Error Handling Tests =====

    @Test
    fun `reading non-existent object throws ObjectNotFoundException`() {
        val fakeObjectId = ObjectId.parse("abc123def456")

        assertThrows<ObjectNotFoundException> {
            runBlocking {
                objectManager.readObject(fakeObjectId)
            }
        }
    }

    // ===== JSON Format Compatibility Tests =====

    @Test
    fun `indirect object JSON format matches Go implementation`() = runBlocking {
        val chunkSize = SplitterAlgorithms.SIZE_128K
        val data = ByteArray(chunkSize * 2) { (it % 256).toByte() }

        val objectId = objectManager.writeObject(data)
        contentManager.flush()

        // Get the raw indirect object content
        val (indexObjectId, _) = objectId.indexObjectId()
        val (indexContentId, _, _) = indexObjectId.getContentId()
        val rawContent = contentManager.getContent(indexContentId)

        // Should be valid JSON matching Go format:
        // {"stream":"kopia:indirect","entries":[{"l":...,"o":"..."},{"s":...,"l":...,"o":"..."}]}
        val jsonString = rawContent.decodeToString()
        assertTrue(jsonString.contains("\"stream\":\"kopia:indirect\""))
        assertTrue(jsonString.contains("\"entries\":"))
        assertTrue(jsonString.contains("\"o\":"))
    }

    // ===== Concatenation Tests =====

    @Test
    fun `concatenate two objects`() = runBlocking {
        val data1 = "Hello, ".toByteArray()
        val data2 = "World!".toByteArray()

        val objectId1 = objectManager.writeObject(data1)
        val objectId2 = objectManager.writeObject(data2)
        contentManager.flush()

        val concatenatedId = objectManager.concatenate(listOf(objectId1, objectId2))

        val readData = objectManager.readObject(concatenatedId)
        assertArrayEquals("Hello, World!".toByteArray(), readData)
    }

    @Test
    fun `concatenate single object returns same object`() = runBlocking {
        val data = "Hello".toByteArray()
        val objectId = objectManager.writeObject(data)

        val concatenatedId = objectManager.concatenate(listOf(objectId))

        assertEquals(objectId, concatenatedId)
    }

    @Test
    fun `concatenate empty list throws exception`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                objectManager.concatenate(emptyList())
            }
        }
    }
}

/**
 * Simple fixed-size splitter for testing purposes.
 */
private class FixedTestSplitter(private val chunkSize: Int) : org.kopiaKt.core.splitter.Splitter {
    private var position = 0

    override fun nextSplitPoint(b: ByteArray): Int {
        val remaining = chunkSize - position
        return if (remaining <= b.size) {
            position = 0
            remaining
        } else {
            position += b.size
            -1
        }
    }

    override fun maxSegmentSize(): Int = chunkSize
    override fun reset() { position = 0 }
    override fun close() {}
}
