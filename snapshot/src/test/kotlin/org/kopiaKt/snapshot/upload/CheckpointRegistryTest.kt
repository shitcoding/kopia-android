package org.kopiaKt.snapshot.upload

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.DirEntry
import org.kopiaKt.snapshot.model.EntryType
import java.time.Instant

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
    fun `runCheckpoints randomizes the name of non-directory entries`(): Unit = runBlocking {
        val registry = CheckpointRegistry()
        registry.addCheckpointCallback("f1") {
            DirEntry(name = "f1", type = EntryType.FILE, objectId = "obj1")
        }

        val builder = DirManifestBuilder()
        registry.runCheckpoints(builder)

        // Go renames every non-directory checkpoint entry to ".checkpointed.<name>.<uuid>"
        // explicitly "to prevent the use of checkpointed objects as authoritative on subsequent
        // runs" — a half-written file must never satisfy the next run's cache lookup for "f1".
        val name = builder.build(Instant.EPOCH).entries.single().name
        assertTrue(name.startsWith(".checkpointed.f1."), "expected a randomized name, got $name")
        assertTrue(name.length > ".checkpointed.f1.".length, "expected a uuid suffix, got $name")
    }

    @Test
    fun `runCheckpoints keeps the name of directory entries`(): Unit = runBlocking {
        val registry = CheckpointRegistry()
        registry.addCheckpointCallback("sub") {
            DirEntry(name = "sub", type = EntryType.DIRECTORY, objectId = "obj1")
        }

        val builder = DirManifestBuilder()
        registry.runCheckpoints(builder)

        // Directories keep their name: the whole point of a checkpoint is that the next run reads
        // the tree back, and it addresses directories by name.
        assertEquals("sub", builder.build(Instant.EPOCH).entries.single().name)
    }

    @Test
    fun `runCheckpoints gives each checkpointed file a distinct name`(): Unit = runBlocking {
        val registry = CheckpointRegistry()
        val builder = DirManifestBuilder()

        repeat(2) {
            registry.addCheckpointCallback("f1") {
                DirEntry(name = "f1", type = EntryType.FILE, objectId = "obj1")
            }
            registry.runCheckpoints(builder)
        }

        assertEquals(2, builder.build(Instant.EPOCH).entries.map { it.name }.toSet().size)
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
