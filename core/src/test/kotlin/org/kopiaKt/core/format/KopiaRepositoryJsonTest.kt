package org.kopiaKt.core.format

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KopiaRepositoryJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun `create generates unique ID`() {
        val format1 = KopiaRepositoryJson.create()
        val format2 = KopiaRepositoryJson.create()

        assertEquals(32, format1.uniqueID.size)
        assertEquals(32, format2.uniqueID.size)
        assertTrue(!format1.uniqueID.contentEquals(format2.uniqueID))
    }

    @Test
    fun `create uses default algorithm`() {
        val format = KopiaRepositoryJson.create()
        assertEquals(KopiaRepositoryJson.DEFAULT_KEY_DERIVATION_ALGORITHM, format.keyDerivationAlgorithm)
    }

    @Test
    fun `create uses AES256_GCM encryption`() {
        val format = KopiaRepositoryJson.create()
        assertEquals(KopiaRepositoryJson.AES256_GCM_ENCRYPTION, format.encryption)
    }

    @Test
    fun `create uses kopia tool name`() {
        val format = KopiaRepositoryJson.create()
        assertEquals(KopiaRepositoryJson.TOOL_NAME, format.tool)
    }

    @Test
    fun `parse and serialize round-trip`() {
        val original = KopiaRepositoryJson.create(buildVersion = "test-1.0.0")

        val jsonStr = json.encodeToString(original)
        val parsed = json.decodeFromString<KopiaRepositoryJson>(jsonStr)

        assertEquals(original.tool, parsed.tool)
        assertEquals(original.buildVersion, parsed.buildVersion)
        assertArrayEquals(original.uniqueID, parsed.uniqueID)
        assertEquals(original.keyDerivationAlgorithm, parsed.keyDerivationAlgorithm)
        assertEquals(original.encryption, parsed.encryption)
    }

    @Test
    fun `deriveFormatEncryptionKeyFromPassword returns 32 bytes`() {
        val format = KopiaRepositoryJson.create()
        val key = format.deriveFormatEncryptionKeyFromPassword("test-password")

        assertEquals(32, key.size)
    }

    @Test
    fun `same password produces same key`() {
        val format = KopiaRepositoryJson.create()
        val key1 = format.deriveFormatEncryptionKeyFromPassword("test-password")
        val key2 = format.deriveFormatEncryptionKeyFromPassword("test-password")

        assertArrayEquals(key1, key2)
    }

    @Test
    fun `different passwords produce different keys`() {
        val format = KopiaRepositoryJson.create()
        val key1 = format.deriveFormatEncryptionKeyFromPassword("password1")
        val key2 = format.deriveFormatEncryptionKeyFromPassword("password2")

        assertTrue(!key1.contentEquals(key2))
    }

    @Test
    fun `different uniqueIDs produce different keys`() {
        val format1 = KopiaRepositoryJson.create()
        val format2 = KopiaRepositoryJson.create()

        val key1 = format1.deriveFormatEncryptionKeyFromPassword("same-password")
        val key2 = format2.deriveFormatEncryptionKeyFromPassword("same-password")

        assertTrue(!key1.contentEquals(key2))
    }

    @Test
    fun `encrypt and decrypt repository config round-trip`() {
        val format = KopiaRepositoryJson.create()
        val password = "test-password"
        val masterKey = format.deriveFormatEncryptionKeyFromPassword(password)

        val config = RepositoryConfig(
            hash = "BLAKE2B-256-128",
            encryption = "AES256-GCM-HMAC-SHA256",
            secret = ByteArray(32) { it.toByte() },
            masterKey = ByteArray(32) { (it + 100).toByte() },
            version = 3,
            maxPackSize = 20 * 1024 * 1024,
            splitter = "DYNAMIC-4M-BUZHASH"
        )

        // Encrypt
        val encryptedFormat = format.encryptRepositoryConfig(config, masterKey)

        // Decrypt
        val decrypted = encryptedFormat.decryptRepositoryConfig(masterKey)

        assertEquals(config.hash, decrypted.hash)
        assertEquals(config.encryption, decrypted.encryption)
        assertArrayEquals(config.secret, decrypted.secret)
        assertArrayEquals(config.masterKey, decrypted.masterKey)
        assertEquals(config.version, decrypted.version)
        assertEquals(config.maxPackSize, decrypted.maxPackSize)
        assertEquals(config.splitter, decrypted.splitter)
    }

    @Test
    fun `encrypted format is different each time due to random nonce`() {
        val format = KopiaRepositoryJson.create()
        val password = "test-password"
        val masterKey = format.deriveFormatEncryptionKeyFromPassword(password)

        val config = RepositoryConfig(hash = "test")

        val encrypted1 = format.encryptRepositoryConfig(config, masterKey)
        val encrypted2 = format.encryptRepositoryConfig(config, masterKey)

        assertTrue(!encrypted1.encryptedBlockFormat.contentEquals(encrypted2.encryptedBlockFormat))

        // But both should decrypt to the same value
        val decrypted1 = encrypted1.decryptRepositoryConfig(masterKey)
        val decrypted2 = encrypted2.decryptRepositoryConfig(masterKey)

        assertEquals(decrypted1.hash, decrypted2.hash)
    }

    @Test
    fun `parse Go-compatible JSON`() {
        // This JSON structure matches what Go Kopia produces
        val goJson = """
        {
            "tool": "kopia",
            "buildVersion": "v0.13.0",
            "buildInfo": "build info",
            "uniqueID": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "keyAlgo": "scrypt-65536-8-1",
            "encryption": "AES256_GCM"
        }
        """.trimIndent()

        val parsed = KopiaRepositoryJson.parse(goJson.toByteArray())

        assertEquals("kopia", parsed.tool)
        assertEquals("v0.13.0", parsed.buildVersion)
        assertEquals("scrypt-65536-8-1", parsed.keyDerivationAlgorithm)
        assertEquals("AES256_GCM", parsed.encryption)
        assertEquals(32, parsed.uniqueID.size)
    }

    @Test
    fun `FORMAT_BLOB_ID is correct`() {
        assertEquals("kopia.repository", KopiaRepositoryJson.FORMAT_BLOB_ID)
    }
}
