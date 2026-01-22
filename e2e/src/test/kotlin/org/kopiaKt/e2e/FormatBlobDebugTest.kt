package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.kopiaKt.core.crypto.deriveKeyFromPassword
import org.kopiaKt.core.format.KopiaRepositoryJson
import java.util.Base64

/**
 * Debug test to understand format blob decryption.
 */
class FormatBlobDebugTest {

    @Test
    fun `parse and decrypt Go Kopia format blob`() {
        // This is the format blob from /tmp/kopia-test-repo/kopia.repository.f
        val formatBlobJson = """
{
  "tool": "https://github.com/kopia/kopia",
  "buildVersion": "v0.22.4-0.20260106004250-12e59e388d4a",
  "buildInfo": "2026-01-06T00:42:50Z-12e59e388d4adf2f1b62e332e2f31e05b19d0abd",
  "uniqueID": "tSlExrPCG1fbqPMmRdsna4OHyQ2UNYakf//HsT+1wAk=",
  "keyAlgo": "scrypt-65536-8-1",
  "encryption": "AES256_GCM",
  "encryptedBlockFormat": "CB2wHdKrMC8gG4iweG01AiGNkMkreOO+ER6QCP0cAcIcIoema3RL3Ra8P1yc9QARPFo1E6qSdIqDKpCtYCHDrBwE/YXmTG4/wPUcCeL2yG2ocmL6wy2kZauQzLSPigidZc4HmkzvdU5EIF/u5b+s5gEnMqwdZYlnzYParYZG3BbFBoLn65CPo420IoIU5Qexa0ydlZJg0GclAzKs4kSnvZRrdp7ydg56tg2USLxJHSFzcUmQpy2tMLjO5Nea4ZirNz8d7VybdcpMg+hOMbBIEADoT3zH/1stOVmhjqjn++RJUdDYbFrinttFAu/opYJIjtFM/C7BiFfpDfX4/FC4b2Ljhe3gAWud/9lQDIt9LD/R+UrLZTRmjFJ1amPC0P+2Hj5+7bqbl6G3KIamXvec+r+s2RuObsV9Rqj9+QdxheW30x2LSiHazOzpzG2XDa+acIodTzg/jeanK3wIcC+grb+9OZuijZYML+b4OSgLSbAc8C+5dvQcvQsBcNoUkfcXb56lSft9Pr5sF9msmqDBZ2LttCGHL7Vi12HmoWe0g5SIMsG7348Qw8x2l1ZWxreE7u900yu7Z8v0A1H/6BUYNJQxMNVt+CWU7Md3tQQq1vxd+McVhHjptAegKnL5htbyU7Dlrfbbv0R/AvEy64Om87pofR6hr3vBcmFNvNUyCWv2mGvJBcLfc4gVqItPDrHWeYYrzMrhbdpfrGdKfRzgQIyU5GLYP0iPqv/0vlYDuzvm+hSPGpN389X48EM2v6LhpsTfybx1DwSAY1PYczIcQdWv7WlKaLV2sqH6/eofZA=="
}
        """.trimIndent()
        
        val password = "test123"  // Password used to create the repo
        
        // Parse the format blob
        val formatBlob = KopiaRepositoryJson.parse(formatBlobJson.toByteArray())
        
        println("Parsed format blob:")
        println("  tool: ${formatBlob.tool}")
        println("  keyAlgo: ${formatBlob.keyDerivationAlgorithm}")
        println("  encryption: ${formatBlob.encryption}")
        println("  uniqueID (base64): ${Base64.getEncoder().encodeToString(formatBlob.uniqueID)}")
        println("  uniqueID length: ${formatBlob.uniqueID.size}")
        println("  encryptedBlockFormat length: ${formatBlob.encryptedBlockFormat.size}")
        
        // Derive the key
        val derivedKey = formatBlob.deriveFormatEncryptionKeyFromPassword(password)
        println("  derivedKey (hex): ${derivedKey.toHexString()}")
        println("  derivedKey length: ${derivedKey.size}")
        
        // Try to decrypt
        try {
            val config = formatBlob.decryptRepositoryConfig(derivedKey)
            println("SUCCESS! Decrypted config:")
            println("  version: ${config.version}")
            println("  encryption: ${config.encryption}")
            println("  hash: ${config.hash}")
        } catch (e: Exception) {
            println("FAILED to decrypt: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
