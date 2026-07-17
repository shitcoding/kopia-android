package org.kopiaKt.app.bridge

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kopiaKt.app.domain.model.ConnectionConfig

class WebModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

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
            s3 = WebS3Config("bucket", "endpoint", "region", "akid", "secret")
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
            webdav = WebWebDavConfig("http://url", "user", "pass")
        )
        val domain = webConfig.toDomain() as ConnectionConfig.WebDAV
        assertEquals("pass", domain.password)
        assertEquals("user", domain.username)
    }

    @Test
    fun `toDomain maps SFTP password`() {
        val webConfig = WebConnectionConfig(
            storageType = "SFTP",
            sftp = WebSftpConfig("host", 22, "user", "/path", "spass")
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
                host = "host", port = 22, username = "user", path = "/path", password = "spass",
                knownHostsData = "host ssh-ed25519 AAAAKEY",
                hostKeyFingerprint = "SHA256:abc123",
                insecureSkipHostKeyVerification = true
            )
        )
        val domain = webConfig.toDomain() as ConnectionConfig.SFTP
        assertEquals("host ssh-ed25519 AAAAKEY", domain.knownHostsData)
        assertEquals("SHA256:abc123", domain.hostKeyFingerprint)
        assertTrue(domain.insecureSkipHostKeyVerification)
    }

    @Test
    fun `toWeb echoes SFTP host-key trust material`() {
        val domain = ConnectionConfig.SFTP(
            host = "h", port = 22, username = "u", path = "/p", password = "pw",
            knownHostsData = "kh", hostKeyFingerprint = "SHA256:fp",
            insecureSkipHostKeyVerification = true
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
}
