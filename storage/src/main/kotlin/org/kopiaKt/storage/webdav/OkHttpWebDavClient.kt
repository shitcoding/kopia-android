package org.kopiaKt.storage.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Lightweight WebDAV client built on OkHttp.
 *
 * Replaces the Sardine library which uses Apache HttpClient 4.x classes
 * that are removed on Android, causing NoSuchFieldError at runtime.
 *
 * Implements only the WebDAV operations needed by [WebDavBlobStorage]:
 * PROPFIND, GET, PUT, DELETE, MKCOL, MOVE.
 */
class OkHttpWebDavClient(
    private val username: String = "",
    private val password: String = "",
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 60,
    writeTimeoutSeconds: Long = 60,
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val authHeader: String? =
        if (username.isNotEmpty()) Credentials.basic(username, password) else null

    /**
     * Lists resources at [url] with the given [depth] (0 or 1).
     *
     * Sends a PROPFIND request and parses the multi-status XML response.
     *
     * @return list of [DavResource] entries (includes the target resource itself for depth 0/1)
     * @throws WebDavException on HTTP errors
     */
    fun list(url: String, depth: Int = 1): List<DavResource> {
        val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
            |<D:propfind xmlns:D="DAV:">
            |  <D:prop>
            |    <D:getcontentlength/>
            |    <D:getlastmodified/>
            |    <D:resourcetype/>
            |    <D:displayname/>
            |  </D:prop>
            |</D:propfind>""".trimMargin()

        val request = newRequestBuilder(url)
            .method("PROPFIND", propfindBody.toRequestBody(XML_MEDIA_TYPE))
            .addHeader("Depth", depth.toString())
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw WebDavException("Not Found", HttpURLConnection.HTTP_NOT_FOUND)
            }
            // Multi-status is 207, but some servers may return 200
            if (resp.code != 207 && resp.code != HttpURLConnection.HTTP_OK) {
                throwForStatus(resp)
            }

            val body = resp.body ?: return emptyList()
            return body.byteStream().use { stream ->
                parsePropfindResponse(stream)
            }
        }
    }

    /**
     * Downloads the resource at [url].
     *
     * @param headers additional headers (e.g. Range)
     * @return an [InputStream] that the caller must close
     * @throws WebDavException on HTTP errors
     */
    fun get(url: String, headers: Map<String, String> = emptyMap()): InputStream {
        val builder = newRequestBuilder(url).get()
        for ((key, value) in headers) {
            builder.addHeader(key, value)
        }

        val response = httpClient.newCall(builder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            val code = response.code
            response.close()
            throw WebDavException(httpStatusMessage(code), code)
        }

        // Return the body stream; caller is responsible for closing.
        // We wrap it so that closing the stream also closes the response.
        val body = response.body ?: run {
            response.close()
            return ByteArray(0).inputStream()
        }
        return ResponseBodyInputStream(body, response)
    }

    /**
     * Uploads [data] to [url] with the given [contentType].
     *
     * @throws WebDavException on HTTP errors
     */
    fun put(url: String, data: ByteArray, contentType: String = "application/octet-stream") {
        val request = newRequestBuilder(url)
            .put(data.toRequestBody(contentType.toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throwForStatus(resp)
            }
        }
    }

    /**
     * Deletes the resource at [url].
     *
     * @throws WebDavException on HTTP errors
     */
    fun delete(url: String) {
        val request = newRequestBuilder(url)
            .delete()
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throwForStatus(resp)
            }
        }
    }

    /**
     * Creates a collection (directory) at [url] using MKCOL.
     *
     * @throws WebDavException on HTTP errors
     */
    fun createDirectory(url: String) {
        val request = newRequestBuilder(url)
            .method("MKCOL", null)
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throwForStatus(resp)
            }
        }
    }

    /**
     * Moves (renames) a resource from [sourceUrl] to [destinationUrl].
     *
     * @param overwrite whether to overwrite if destination exists
     * @throws WebDavException on HTTP errors
     */
    fun move(sourceUrl: String, destinationUrl: String, overwrite: Boolean = true) {
        val request = newRequestBuilder(sourceUrl)
            .method("MOVE", null)
            .addHeader("Destination", destinationUrl)
            .addHeader("Overwrite", if (overwrite) "T" else "F")
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throwForStatus(resp)
            }
        }
    }

    /**
     * Shuts down the HTTP client, releasing connection pool resources.
     */
    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // ---- Private helpers ----

    private fun newRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (authHeader != null) {
            builder.addHeader("Authorization", authHeader)
        }
        return builder
    }

    private fun throwForStatus(response: Response): Nothing {
        throw WebDavException(
            message = httpStatusMessage(response.code),
            statusCode = response.code
        )
    }

    private fun httpStatusMessage(code: Int): String = when (code) {
        HttpURLConnection.HTTP_NOT_FOUND -> "Not Found"
        HttpURLConnection.HTTP_UNAUTHORIZED -> "Unauthorized"
        HttpURLConnection.HTTP_FORBIDDEN -> "Forbidden"
        HttpURLConnection.HTTP_CONFLICT -> "Conflict"
        416 -> "Range Not Satisfiable"
        else -> "HTTP $code"
    }

    /**
     * Parses a WebDAV multi-status (207) PROPFIND XML response into [DavResource] entries.
     *
     * Uses StAX (javax.xml.stream) which is available in the JDK and on Android API 26+.
     * Parses directly from the response [InputStream] to avoid buffering the entire body
     * as a String.
     */
    private fun parsePropfindResponse(input: InputStream): List<DavResource> {
        val resources = mutableListOf<DavResource>()
        val xmlFactory = XMLInputFactory.newInstance()
        // Disable external entity resolution for security
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false)

        val reader: XMLStreamReader = xmlFactory.createXMLStreamReader(BufferedInputStream(input))

        try {
            var href: String? = null
            var contentLength: Long = -1
            var isDirectory = false
            var lastModified: String? = null
            var insideResponse = false
            var insideResourceType = false
            var currentText = StringBuilder()

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        val localName = reader.localName
                        when (localName) {
                            "response" -> {
                                insideResponse = true
                                href = null
                                contentLength = -1
                                isDirectory = false
                                lastModified = null
                            }
                            "resourcetype" -> insideResourceType = true
                            "collection" -> {
                                if (insideResourceType) {
                                    isDirectory = true
                                }
                            }
                        }
                        currentText.clear()
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        currentText.append(reader.text)
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        val localName = reader.localName
                        when (localName) {
                            "href" -> href = currentText.toString().trim()
                            "getcontentlength" -> {
                                contentLength = currentText.toString().trim().toLongOrNull() ?: -1
                            }
                            "getlastmodified" -> lastModified = currentText.toString().trim()
                            "resourcetype" -> insideResourceType = false
                            "response" -> {
                                if (insideResponse && href != null) {
                                    resources.add(
                                        DavResource(
                                            href = href,
                                            contentLength = contentLength,
                                            isDirectory = isDirectory,
                                            lastModified = lastModified,
                                            name = extractName(href)
                                        )
                                    )
                                }
                                insideResponse = false
                            }
                        }
                    }
                }
            }
        } catch (e: XMLStreamException) {
            throw WebDavException("Failed to parse PROPFIND response: ${e.message}", 207)
        } finally {
            try {
                reader.close()
            } catch (_: XMLStreamException) {
                // Ignore close errors
            }
        }

        return resources
    }

    /**
     * Extracts the last path segment as the resource name from an href.
     */
    private fun extractName(href: String): String {
        val path = href.removeSuffix("/")
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash >= 0) path.substring(lastSlash + 1) else path
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
    }
}

/**
 * InputStream wrapper that closes the OkHttp [Response] when the stream is closed.
 */
private class ResponseBodyInputStream(
    private val body: okhttp3.ResponseBody,
    private val response: Response
) : InputStream() {

    private val delegate: InputStream = body.byteStream()

    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray): Int = delegate.read(b)
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun skip(n: Long): Long = delegate.skip(n)
    override fun markSupported(): Boolean = delegate.markSupported()
    override fun mark(readlimit: Int) = delegate.mark(readlimit)
    override fun reset() = delegate.reset()

    override fun close() {
        try {
            delegate.close()
        } finally {
            response.close()
        }
    }
}

/**
 * Represents a single resource returned by a WebDAV PROPFIND response.
 */
data class DavResource(
    /** The href (path or URL) of the resource. */
    val href: String,
    /** Content length in bytes, or -1 if unknown. */
    val contentLength: Long = -1,
    /** Whether this resource is a collection (directory). */
    val isDirectory: Boolean = false,
    /** Last-Modified date string from the server, or null. */
    val lastModified: String? = null,
    /** The last path segment (file/directory name). */
    val name: String = "",
)

/**
 * Exception thrown by [OkHttpWebDavClient] for HTTP errors.
 * Carries the HTTP status code for error classification.
 */
class WebDavException(
    message: String,
    val statusCode: Int
) : RuntimeException("$message (HTTP $statusCode)")
