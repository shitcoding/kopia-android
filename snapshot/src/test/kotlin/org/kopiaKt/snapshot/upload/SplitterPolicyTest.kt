package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.policy.SplitterPolicy
import org.kopiaKt.snapshot.testutil.MockFile

/**
 * A source's splitter policy reaches the object writer.
 *
 * `FileUploader` took a `splitterPolicy` and never read it, so a user who set one silently got the
 * repository's own splitter instead (task-78). Go passes it on the FILE and STREAMFILE writers only
 * (`upload.go:248`, `:364`) — symlink targets and directory manifests deliberately do not get it.
 *
 * Both cases use FIXED splitters so the chunk count is arithmetic rather than a coin flip: a buzhash
 * splitter emits a boundary when its rolling hash happens to match, which cannot be asserted on.
 */
@DisplayName("The source's splitter policy")
class SplitterPolicyTest {

    /** Incompressible enough that compression cannot collapse the chunk count. */
    private fun payload(size: Int) = ByteArray(size) { (it * 31 + (it shr 8) * 17).toByte() }

    @Test
    fun `overrides the repository's splitter for file content`(): Unit = runBlocking {
        // The repository says 8 MiB, so 3 MiB would be a single chunk on its own. The policy asks
        // for 1 MiB — three chunks, which cannot be addressed without indirection.
        assertThat(uploadedFileId("FIXED-8M", SplitterPolicy(algorithm = "FIXED-1M")).indirection)
            .isAtLeast(1)
    }

    @Test
    fun `an empty policy leaves the repository's splitter alone`(): Unit = runBlocking {
        // The control. Without it the test above would also pass for a build that ignored the
        // repository and always split at 1 MiB — which is exactly the defect task-78 fixed.
        assertThat(uploadedFileId("FIXED-8M", SplitterPolicy()).indirection).isEqualTo(0)
    }

    /** Uploads one 3 MiB file and returns the object id of its content. */
    private suspend fun uploadedFileId(repositorySplitter: String, policy: SplitterPolicy): ObjectId {
        val (repository, _) = TestRepositoryFactory.createInMemory(splitter = repositorySplitter)
        try {
            val writer = repository.newWriter(WriteSessionOptions())
            try {
                val entry = FileUploader(
                    writer = writer,
                    progress = NullUploadProgress(),
                    splitterPolicy = policy,
                ).processFile(
                    file = MockFile("big.bin", payload(3 * 1024 * 1024)),
                    relativePath = "big.bin",
                    previousEntries = emptyList(),
                    checkpointRegistry = CheckpointRegistry(),
                )
                return ObjectId.parse(checkNotNull(entry.objectId) { "the upload produced no object id" })
            } finally {
                writer.close()
            }
        } finally {
            repository.close()
        }
    }
}
