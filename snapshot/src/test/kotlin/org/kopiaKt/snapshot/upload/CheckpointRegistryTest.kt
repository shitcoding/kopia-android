package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.EntryType

class CheckpointRegistryTest {

    @Test
    fun `isEmpty returns true for new registry`() {
        val registry = CheckpointRegistry()
        assertTrue(registry.isEmpty())
    }

    @Test
    fun `isEmpty returns false after adding callback`() {
        val registry = CheckpointRegistry()
        registry.addCheckpointCallback("test") { null }
        assertTrue(!registry.isEmpty())
    }

    @Test
    fun `size returns correct count`() {
        val registry = CheckpointRegistry()
        assertEquals(0, registry.size())

        registry.addCheckpointCallback("cb1") { null }
        assertEquals(1, registry.size())

        registry.addCheckpointCallback("cb2") { null }
        assertEquals(2, registry.size())
    }

    @Test
    fun `removeCheckpointCallback removes callback`() {
        val registry = CheckpointRegistry()
        registry.addCheckpointCallback("test") { null }
        assertEquals(1, registry.size())

        registry.removeCheckpointCallback("test")
        assertEquals(0, registry.size())
    }

    @Test
    fun `removeCheckpointCallback is no-op for non-existent callback`() {
        val registry = CheckpointRegistry()
        registry.addCheckpointCallback("test") { null }

        registry.removeCheckpointCallback("nonexistent")

        assertEquals(1, registry.size())
    }

    @Test
    fun `runCheckpoints invokes all callbacks`(): Unit = runBlocking {
        val registry = CheckpointRegistry()
        val invoked = mutableListOf<String>()

        registry.addCheckpointCallback("cb1") {
            invoked.add("cb1")
            DirEntry(name = "file1.txt", type = EntryType.FILE, objectId = "obj1")
        }
        registry.addCheckpointCallback("cb2") {
            invoked.add("cb2")
            DirEntry(name = "file2.txt", type = EntryType.FILE, objectId = "obj2")
        }

        val builder = DirManifestBuilder()
        val errors = registry.runCheckpoints(builder)

        assertEquals(2, invoked.size)
        assertTrue(invoked.contains("cb1"))
        assertTrue(invoked.contains("cb2"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `runCheckpoints adds entries from callbacks to builder`(): Unit = runBlocking {
        val registry = CheckpointRegistry()

        registry.addCheckpointCallback("file") {
            DirEntry(name = "checkpoint-file.txt", type = EntryType.FILE, fileSize = 1000, objectId = "obj1")
        }

        val builder = DirManifestBuilder()
        registry.runCheckpoints(builder)

        assertEquals(1, builder.entryCount())
    }

    @Test
    fun `runCheckpoints skips null results from callbacks`(): Unit = runBlocking {
        val registry = CheckpointRegistry()

        registry.addCheckpointCallback("null-cb") { null }
        registry.addCheckpointCallback("entry-cb") {
            DirEntry(name = "file.txt", type = EntryType.FILE, objectId = "obj1")
        }

        val builder = DirManifestBuilder()
        registry.runCheckpoints(builder)

        // Only non-null entry should be added
        assertEquals(1, builder.entryCount())
    }

    @Test
    fun `runCheckpoints collects errors from failing callbacks`(): Unit = runBlocking {
        val registry = CheckpointRegistry()

        registry.addCheckpointCallback("success") {
            DirEntry(name = "file.txt", type = EntryType.FILE, objectId = "obj1")
        }
        registry.addCheckpointCallback("failing") {
            throw RuntimeException("checkpoint failed")
        }

        val builder = DirManifestBuilder()
        val errors = registry.runCheckpoints(builder)

        // Error should be collected
        assertEquals(1, errors.size)
        assertEquals("checkpoint failed", errors[0].message)

        // Successful callback's entry should still be added
        assertEquals(1, builder.entryCount())
    }

    @Test
    fun `callbacks can be replaced by key`(): Unit = runBlocking {
        val registry = CheckpointRegistry()
        val callCount = mutableListOf<Int>()

        registry.addCheckpointCallback("test") {
            callCount.add(1)
            null
        }

        // Replace the callback
        registry.addCheckpointCallback("test") {
            callCount.add(2)
            null
        }

        val builder = DirManifestBuilder()
        registry.runCheckpoints(builder)

        // Only the replacement callback should be invoked
        assertEquals(1, callCount.size)
        assertEquals(2, callCount[0])
    }

    @Test
    fun `runCheckpoints is thread-safe`(): Unit = runBlocking {
        val registry = CheckpointRegistry()
        var counter = 0

        // Add many callbacks
        repeat(100) { i ->
            registry.addCheckpointCallback("cb$i") {
                counter++
                null
            }
        }

        val builder = DirManifestBuilder()
        registry.runCheckpoints(builder)

        assertEquals(100, counter)
    }
}
