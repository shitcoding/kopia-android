package org.kopiaKt.core.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.core.testutil.TestRepositoryFactory

/**
 * The object splitter a repository declares is the one its writes use.
 *
 * It was not. `ObjectManager` was constructed with no `splitterFactory` at both sites in
 * [DirectRepositoryImpl], so every write used the enum default `DYNAMIC-4M-BUZHASH` while
 * `objectFormat()` faithfully reported whatever the format blob said — a repository created by
 * desktop Kopia with `FIXED-1M` was written with buzhash boundaries. Nothing failed and Go read the
 * result back, because a splitter is advisory for writers; what it cost was **dedup between the two
 * implementations**, since the same bytes chunked two ways share no content ids at all (task-78).
 *
 * These two cases are deliberately both FIXED, and therefore deterministic. A buzhash splitter emits
 * a boundary when its rolling hash happens to match, so "3 MiB under DYNAMIC-4M-BUZHASH" is a coin
 * flip and could not be asserted on. Under a fixed splitter the chunk count is arithmetic — and
 * whatever the broken build did, it did the same thing for both repositories here, so the pair
 * cannot both pass unless the declared splitter is actually being read.
 */
@DisplayName("The repository's declared object splitter")
class RepositorySplitterTest {

    /** Incompressible enough that compression cannot collapse the chunk count. */
    private fun payload(size: Int) = ByteArray(size) { (it * 31 + (it shr 8) * 17).toByte() }

    @Test
    fun `FIXED-1M splits three mebibytes into several contents`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory(splitter = "FIXED-1M")
        try {
            val writer = repository.newWriter(WriteSessionOptions())
            val objectId = writer.use { it.writeObject(payload(3 * 1024 * 1024)) }

            // Three 1 MiB chunks cannot be addressed directly, so the id must be indirect.
            assertThat(objectId.indirection).isAtLeast(1)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `FIXED-8M keeps the same three mebibytes in one content`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory(splitter = "FIXED-8M")
        try {
            val writer = repository.newWriter(WriteSessionOptions())
            val objectId = writer.use { it.writeObject(payload(3 * 1024 * 1024)) }

            // Below the split size there is exactly one chunk, so no indirection.
            assertThat(objectId.indirection).isEqualTo(0)
        } finally {
            repository.close()
        }
    }

    /**
     * An empty splitter in the format blob is Go's legacy `FIXED` — the 4 MiB fixed splitter —
     * not an error and not the buzhash default (`repo/object/object_manager.go:231-234`). Refusing
     * it would turn a repository desktop Kopia opens perfectly well into one this app cannot open
     * at all, which is a worse outcome than the chunking it was written with.
     *
     * The two sizes pin 4 MiB specifically: 3 MiB direct rules out FIXED-1M, 6 MiB indirect rules
     * out FIXED-8M.
     */
    @Test
    fun `an empty splitter is Go's legacy fixed 4 MiB, not an error`(): Unit = runBlocking {
        val (repository, _) = TestRepositoryFactory.createInMemory(splitter = "")
        try {
            val writer = repository.newWriter(WriteSessionOptions())
            writer.use {
                assertThat(it.writeObject(payload(3 * 1024 * 1024)).indirection).isEqualTo(0)
                assertThat(it.writeObject(payload(6 * 1024 * 1024)).indirection).isAtLeast(1)
            }
        } finally {
            repository.close()
        }
    }
}
