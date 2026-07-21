package org.kopiaKt.core.testvectors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Test vector data classes for parsing JSON test vectors generated from Go Kopia.
 * These vectors enable byte-exact compatibility testing between Go and Kotlin implementations.
 */

@Serializable
data class TestVectors(
    val version: String,
    val generatedAt: String,
    val hash: HashVectors,
    val keyDerivation: KeyDerivationVectors,
    val encryption: EncryptionVectors,
    val compression: CompressionVectors,
    val splitter: SplitterVectors,
    val contentId: ContentIdVectors,
)

@Serializable
data class HashVectors(
    @SerialName("blake2b_256_128")
    val blake2b256128: List<HashTestCase>,
    @SerialName("blake2b_256")
    val blake2b256: List<HashTestCase>,
    @SerialName("blake3_256")
    val blake3256: List<HashTestCase>,
    @SerialName("blake3_256_128")
    val blake3256128: List<HashTestCase>,
    @SerialName("hmac_sha256")
    val hmacSha256: List<HmacTestCase>,
)

@Serializable
data class HashTestCase(
    val name: String,
    val inputHex: String,
    val secret: String? = null,
    val outputHex: String,
) {
    val input: ByteArray get() = inputHex.hexToByteArray()
    val secretBytes: ByteArray? get() = secret?.hexToByteArray()
    val output: ByteArray get() = outputHex.hexToByteArray()
}

@Serializable
data class HmacTestCase(
    val name: String,
    val inputHex: String,
    val keyHex: String,
    val outputHex: String,
) {
    val input: ByteArray get() = inputHex.hexToByteArray()
    val key: ByteArray get() = keyHex.hexToByteArray()
    val output: ByteArray get() = outputHex.hexToByteArray()
}

@Serializable
data class KeyDerivationVectors(
    val pbkdf2: List<Pbkdf2TestCase>,
    val scrypt: List<ScryptTestCase>,
    val hkdf: List<HkdfTestCase>,
)

@Serializable
data class Pbkdf2TestCase(
    val name: String,
    val password: String,
    val saltHex: String,
    val iterations: Int,
    val keyLen: Int,
    val outputHex: String,
) {
    val salt: ByteArray get() = saltHex.hexToByteArray()
    val output: ByteArray get() = outputHex.hexToByteArray()
}

@Serializable
data class ScryptTestCase(
    val name: String,
    val password: String,
    val saltHex: String,
    val n: Int,
    val r: Int,
    val p: Int,
    val keyLen: Int,
    val outputHex: String,
) {
    val salt: ByteArray get() = saltHex.hexToByteArray()
    val output: ByteArray get() = outputHex.hexToByteArray()
}

@Serializable
data class HkdfTestCase(
    val name: String,
    val masterHex: String,
    val saltHex: String,
    val info: String,
    val length: Int,
    val outputHex: String,
) {
    val master: ByteArray get() = masterHex.hexToByteArray()
    val salt: ByteArray get() = saltHex.hexToByteArray()
    val infoBytes: ByteArray get() = info.toByteArray(Charsets.UTF_8)
    val output: ByteArray get() = outputHex.hexToByteArray()
}

@Serializable
data class EncryptionVectors(
    val aes256Gcm: List<Aes256GcmTestCase>,
)

@Serializable
data class Aes256GcmTestCase(
    val name: String,
    val keyHex: String,
    val nonceHex: String,
    val plaintextHex: String,
    val aadHex: String? = null,
    val ciphertextHex: String,
) {
    val key: ByteArray get() = keyHex.hexToByteArray()
    val nonce: ByteArray get() = nonceHex.hexToByteArray()
    val plaintext: ByteArray get() = plaintextHex.hexToByteArray()
    val aad: ByteArray? get() = aadHex?.hexToByteArray()
    val ciphertext: ByteArray get() = ciphertextHex.hexToByteArray()
}

@Serializable
data class CompressionVectors(
    val headers: List<CompressionHeaderCase>,
)

@Serializable
data class CompressionHeaderCase(
    val algorithm: String,
    val headerHex: String,
    val headerId: Int,
) {
    val header: ByteArray get() = headerHex.hexToByteArray()
}

@Serializable
data class SplitterVectors(
    val buzhash32: List<SplitterTestCase>,
    val rabinkarp64: List<SplitterTestCase>,
)

@Serializable
data class SplitterTestCase(
    val name: String,
    val algorithm: String,
    val avgSize: Int,
    val minSize: Int,
    val maxSize: Int,
    val inputHex: String,
    val boundaries: List<Int>,
) {
    val input: ByteArray get() = inputHex.hexToByteArray()
}

@Serializable
data class ContentIdVectors(
    val formation: List<ContentIdTestCase>,
)

@Serializable
data class ContentIdTestCase(
    val name: String,
    val prefix: String,
    val hashHex: String,
    val contentId: String,
) {
    val hash: ByteArray get() = hashHex.hexToByteArray()
}

// Hex conversion utilities
fun String.hexToByteArray(): ByteArray {
    if (isEmpty()) return ByteArray(0)
    check(length % 2 == 0) { "Hex string must have even length: $this" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/**
 * Loads test vectors from the JSON file.
 */
object TestVectorLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Load test vectors from the default location relative to the project.
     */
    fun load(): TestVectors {
        // Try multiple possible locations
        val possiblePaths = listOf(
            "testvectors/vectors.json",
            "../testvectors/vectors.json",
            "../../testvectors/vectors.json",
            "../../../testvectors/vectors.json",
        )

        for (path in possiblePaths) {
            val file = File(path)
            if (file.exists()) {
                return json.decodeFromString<TestVectors>(file.readText())
            }
        }

        // Try from resources
        val resourceStream = TestVectorLoader::class.java.classLoader
            .getResourceAsStream("vectors.json")
        if (resourceStream != null) {
            return json.decodeFromString<TestVectors>(resourceStream.bufferedReader().readText())
        }

        throw IllegalStateException(
            "Could not find vectors.json in any of the expected locations: $possiblePaths",
        )
    }

    /**
     * Load test vectors from a specific file path.
     */
    fun loadFrom(path: String): TestVectors = json.decodeFromString<TestVectors>(File(path).readText())
}
