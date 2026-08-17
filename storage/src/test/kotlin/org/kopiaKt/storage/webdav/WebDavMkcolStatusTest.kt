package org.kopiaKt.storage.webdav

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kopiaKt.core.blob.BlobId
import java.net.HttpURLConnection

/**
 * What MKCOL's status codes mean, which is the opposite of what this backend assumed (task-68).
 *
 * RFC 4918 §9.3.1:
 * - **405 Method Not Allowed** — the collection already exists (MKCOL only runs on an unmapped URL).
 * - **409 Conflict** — an intermediate collection does not exist.
 *
 * `mkdirAll` had these backwards: it swallowed 409 as "already exists" and rethrew 405. So the one
 * status that means "I could not create this because its parent is missing" was read as success,
 * and the one that means the directory is already there aborted the whole write.
 */
@DisplayName("WebDAV MKCOL status handling")
class WebDavMkcolStatusTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun options() = WebDavOptions(
        url = server.url("/dav/").toString(),
        username = "u",
        password = "p",
    )

    /**
     * Answers by method rather than by queue position: the number of PROPFIND/MKCOL pairs depends on
     * how deeply the blob id shards, and a test that has to predict that is testing the wrong thing.
     *
     * [mkcolStatus] is the code under examination. The first PUT always fails with 409 so the
     * mkdir-and-retry path is entered; a later PUT succeeds, so whether the whole write succeeds
     * turns entirely on how `mkdirAll` reads [mkcolStatus].
     */
    private fun dispatcher(mkcolStatus: Int) = object : Dispatcher() {
        private var putsSeen = 0

        /**
         * After the MKCOL, is there a collection to write into? 405 says it was already there; 409
         * says a parent is missing, so no MKCOL at this level could have helped and the retried PUT
         * must keep failing. Getting this wrong is how the first version of this test let the retry
         * succeed and then measured the MOVE's 404 instead of the thing under test.
         */
        private val collectionUsable = mkcolStatus == HttpURLConnection.HTTP_BAD_METHOD

        override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
            "PROPFIND" -> MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
            "MKCOL" -> MockResponse().setResponseCode(mkcolStatus)
            "PUT" -> {
                putsSeen++
                // The first PUT always fails, which is what sends putBlob into mkdir-and-retry.
                val ok = putsSeen > 1 && collectionUsable
                MockResponse().setResponseCode(
                    if (ok) HttpURLConnection.HTTP_CREATED else HttpURLConnection.HTTP_CONFLICT,
                )
            }
            "MOVE" -> MockResponse().setResponseCode(HttpURLConnection.HTTP_CREATED)
            else -> MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
        }
    }

    /**
     * `isCreate = false` is load-bearing, not incidental: creating would PUT `.shards` during
     * `create()`, which would consume the `putsSeen == 1` the dispatcher reserves for the failing
     * write and quietly dismantle the whole premise.
     */
    private suspend fun putThrough(mkcolStatus: Int) {
        server.dispatcher = dispatcher(mkcolStatus)
        val storage = WebDavBlobStorage.create(options(), isCreate = false)
        try {
            storage.putBlob(BlobId("pdeadbeefcafe0123456789abcdef0123"), ByteArray(16))
        } finally {
            storage.close()
        }
    }

    /**
     * 405 means the collection is already there, so the write must go ahead. Reading it as a failure
     * aborts a `putBlob` that had nothing wrong with it — every level `mkdirAll` walks that already
     * exists and races another writer answers 405.
     */
    @Test
    fun `MKCOL 405 means the collection exists, so the write proceeds`(): Unit = runTest {
        putThrough(HttpURLConnection.HTTP_BAD_METHOD)
    }

    /**
     * 409 means a parent is missing — a real failure, and the one worth naming. It used to be
     * swallowed, so `mkdirAll` returned having created nothing and the caller reported the original
     * PUT error instead of the reason it could not be fixed.
     */
    @Test
    fun `MKCOL 409 means a missing parent, and the failure says so`(): Unit = runTest {
        val failure = assertThrows<Exception> { putThrough(HttpURLConnection.HTTP_CONFLICT) }

        val explained = generateSequence<Throwable>(failure) { it.cause }
            .flatMap { sequenceOf(it) + it.suppressed.asSequence() }
            .mapNotNull { it.message }
            .joinToString(" | ")
        assertTrue(
            explained.contains("parent", ignoreCase = true) ||
                explained.contains("intermediate", ignoreCase = true),
            "the failure should name the missing parent collection, but said: $explained",
        )
    }
}
