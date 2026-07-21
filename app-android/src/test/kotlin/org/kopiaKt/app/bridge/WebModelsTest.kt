package org.kopiaKt.app.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kopiaKt.android.worker.TaskInfo
import org.kopiaKt.android.worker.TaskKind
import org.kopiaKt.android.worker.TaskStatus
import org.kopiaKt.app.domain.model.ConnectionConfig
import java.time.Instant

class WebModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Serialization assertions use the shared bridgeJson (WebModels.kt) — the SAME encoder the bridge
    // uses — so a change to its config can't silently invalidate these pins.

    @Test
    fun `WebConnectRequest uses repositoryPassword field`() {
        val jsonStr = """
            {
                "config": {"storageType": "S3", "s3": {"bucket": "b", "endpoint": "e", "region": "r", "accessKeyId": "ak", "secretAccessKey": "sk"}},
                "repositoryPassword": "repo-pass"
            }
        """.trimIndent()
        val request = json.decodeFromString<WebConnectRequest>(jsonStr)
        assertEquals("repo-pass", request.repositoryPassword)
    }

    @Test
    fun `WebConnectRequest falls back to legacy password field`() {
        val jsonStr = """
            {
                "config": {"storageType": "LOCAL_FILESYSTEM", "local": {"path": "/tmp"}},
                "password": "legacy-pass"
            }
        """.trimIndent()
        val request = json.decodeFromString<WebConnectRequest>(jsonStr)
        assertEquals("legacy-pass", request.password)
        assertEquals("", request.repositoryPassword)
    }

    @Test
    fun `WebS3Config includes secretAccessKey`() {
        val jsonStr = """{"bucket":"b","endpoint":"e","region":"r","accessKeyId":"ak","secretAccessKey":"secret123"}"""
        val config = json.decodeFromString<WebS3Config>(jsonStr)
        assertEquals("secret123", config.secretAccessKey)
    }

    @Test
    fun `WebWebDavConfig includes password`() {
        val jsonStr = """{"url":"http://example.com","username":"user","password":"pass123"}"""
        val config = json.decodeFromString<WebWebDavConfig>(jsonStr)
        assertEquals("pass123", config.password)
    }

    @Test
    fun `WebSftpConfig includes password`() {
        val jsonStr = """{"host":"h","port":22,"username":"u","path":"/p","password":"sftppass"}"""
        val config = json.decodeFromString<WebSftpConfig>(jsonStr)
        assertEquals("sftppass", config.password)
    }

    @Test
    fun `toDomain maps S3 secretAccessKey`() {
        val webConfig = WebConnectionConfig(
            storageType = "S3",
            s3 = WebS3Config("bucket", "endpoint", "region", "akid", "secret"),
        )
        val domain = webConfig.toDomain() as ConnectionConfig.S3
        assertEquals("secret", domain.secretAccessKey)
        assertEquals("akid", domain.accessKeyId)
        assertEquals("bucket", domain.bucket)
    }

    @Test
    fun `toDomain maps WebDAV password`() {
        val webConfig = WebConnectionConfig(
            storageType = "WEBDAV",
            webdav = WebWebDavConfig("http://url", "user", "pass"),
        )
        val domain = webConfig.toDomain() as ConnectionConfig.WebDAV
        assertEquals("pass", domain.password)
        assertEquals("user", domain.username)
    }

    @Test
    fun `toDomain maps SFTP password`() {
        val webConfig = WebConnectionConfig(
            storageType = "SFTP",
            sftp = WebSftpConfig("host", 22, "user", "/path", "spass"),
        )
        val domain = webConfig.toDomain() as ConnectionConfig.SFTP
        assertEquals("spass", domain.password)
        assertEquals("user", domain.username)
        assertEquals("/path", domain.path)
    }

    @Test
    fun `toDomain maps SFTP host-key trust material`() {
        val webConfig = WebConnectionConfig(
            storageType = "SFTP",
            sftp = WebSftpConfig(
                host = "host",
                port = 22,
                username = "user",
                path = "/path",
                password = "spass",
                knownHostsData = "host ssh-ed25519 AAAAKEY",
                hostKeyFingerprint = "SHA256:abc123",
                insecureSkipHostKeyVerification = true,
            ),
        )
        val domain = webConfig.toDomain() as ConnectionConfig.SFTP
        assertEquals("host ssh-ed25519 AAAAKEY", domain.knownHostsData)
        assertEquals("SHA256:abc123", domain.hostKeyFingerprint)
        assertTrue(domain.insecureSkipHostKeyVerification)
    }

    @Test
    fun `toWeb echoes SFTP host-key trust material`() {
        val domain = ConnectionConfig.SFTP(
            host = "h",
            port = 22,
            username = "u",
            path = "/p",
            password = "pw",
            knownHostsData = "kh",
            hostKeyFingerprint = "SHA256:fp",
            insecureSkipHostKeyVerification = true,
        )
        val web = domain.toWeb().sftp!!
        assertEquals("kh", web.knownHostsData)
        assertEquals("SHA256:fp", web.hostKeyFingerprint)
        assertTrue(web.insecureSkipHostKeyVerification)
    }

    @Test
    fun `WebSftpConfig host-key fields default to empty and secure when omitted`() {
        // Older JS clients omit the new fields; they must decode to safe defaults — crucially,
        // insecureSkipHostKeyVerification must NOT default to true.
        val jsonStr = """{"host":"h","port":22,"username":"u","path":"/p","password":"x"}"""
        val config = json.decodeFromString<WebSftpConfig>(jsonStr)
        assertEquals("", config.knownHostsData)
        assertEquals("", config.hostKeyFingerprint)
        assertFalse(config.insecureSkipHostKeyVerification)
    }

    // ===== task-10: JS <-> Kotlin bridge contract pins =====
    // These lock the field names/shapes that the TS bridge (react-ui/src/services/kopiaBridge.ts and
    // types/kopia.ts) relies on. Because the bridge Json uses ignoreUnknownKeys, a rename on either
    // side silently decodes to defaults instead of failing — exactly the class of bug task-10 fixes —
    // so drift can only be caught by asserting the wire shape here (and in the Vitest counterpart).

    @Test
    fun `WebSafPickResult serializes to the uri and displayName shape the picker callback expects`() {
        // Kotlin pushes onDestinationPicked(<this object>); TS types the callback arg as
        // SafPickResult { uri?, displayName? }. A rename here would make every folder pick return
        // [object Object] on the screens that read result.uri.
        val out = bridgeJson.encodeToString(WebSafPickResult(uri = "content://tree/x", displayName = "Docs"))
        assertTrue(out.contains("\"uri\":\"content://tree/x\""), out)
        assertTrue(out.contains("\"displayName\":\"Docs\""), out)
    }

    @Test
    fun `WebPolicySourceRequest decodes the host userName path object the TS parseSourceId sends`() {
        // getPolicy/resolvePolicy/deletePolicy send parseSourceId(sourceId) -> {userName, host, path}.
        val request = json.decodeFromString<WebPolicySourceRequest>(
            """{"userName":"user","host":"laptop","path":"/home/user/docs"}""",
        )
        assertEquals("laptop", request.host)
        assertEquals("user", request.userName)
        assertEquals("/home/user/docs", request.path)
    }

    @Test
    fun `WebSetPolicyRequest decodes the source and policy wrapper`() {
        // setPolicy sends { source: parseSourceId(...), policy }, NOT a bare { sourceId, policy }.
        val request = json.decodeFromString<WebSetPolicyRequest>(
            """{"source":{"userName":"u","host":"h","path":"/p"},"policy":{}}""",
        )
        assertEquals("h", request.source.host)
        assertEquals("/p", request.source.path)
    }

    @Test
    fun `TaskInfo toWeb maps task kinds to the Go-style wire names the UI expects`() {
        // The TS union/icon map key on Snapshot/Restore/Maintenance/Estimate; the raw enum name
        // (BACKUP) would make TASK_KIND_ICON[kind] undefined and crash the task list.
        fun kindWire(kind: TaskKind) = TaskInfo(
            id = "t",
            kind = kind,
            description = "d",
            status = TaskStatus.RUNNING,
            startTime = Instant.EPOCH,
        ).toWeb().kind
        assertEquals("Snapshot", kindWire(TaskKind.BACKUP))
        assertEquals("Restore", kindWire(TaskKind.RESTORE))
        assertEquals("Maintenance", kindWire(TaskKind.MAINTENANCE))
        assertEquals("Estimate", kindWire(TaskKind.ESTIMATE))
    }

    @Test
    fun `WebTaskInfo emits error field to match the TS contract`() {
        // types/kopia.ts WebTaskInfo names the field `error` (not `errorMessage`); the task UI reads
        // task.error on FAILED. @SerialName("error") must keep emitting that wire name.
        val out = bridgeJson.encodeToString(
            WebTaskInfo(
                id = "t1",
                kind = "Snapshot",
                description = "d",
                status = "FAILED",
                errorMessage = "boom",
                startTimeEpochMs = 0L,
            ),
        )
        assertTrue(out.contains("\"error\":\"boom\""), out)
        assertFalse(out.contains("errorMessage"), out)
    }
}
