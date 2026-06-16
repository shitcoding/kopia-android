package org.kopiaKt.core.format

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kopiaKt.core.crypto.HkdfSha256KeyDerivation
import org.kopiaKt.core.crypto.deriveKeyFromPassword
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The plaintext JSON structure stored in the kopia.repository blob.
 *
 * This contains metadata about the repository and the encrypted configuration.
 * The actual repository configuration (containing sensitive keys) is stored
 * encrypted in [encryptedBlockFormat].
 */
@Serializable
data class KopiaRepositoryJson(
    /** Tool identifier (always "kopia"). */
    val tool: String = TOOL_NAME,

    /** Kopia version that created this repository. */
    val buildVersion: String = "",

    /** Build information. */
    val buildInfo: String = "",

    /** Unique per-repository salt (32 bytes). */
    @Serializable(with = ByteArrayBase64Serializer::class)
    val uniqueID: ByteArray = ByteArray(0),

    /** Key derivation algorithm ("scrypt" or "pbkdf2"). */
    @SerialName("keyAlgo")
    val keyDerivationAlgorithm: String = DEFAULT_KEY_DERIVATION_ALGORITHM,

    /** Encryption algorithm for the format blob (always "AES256_GCM"). */
    val encryption: String = AES256_GCM_ENCRYPTION,

    /** Encrypted repository configuration. */
    @SerialName("encryptedBlockFormat")
    @Serializable(with = ByteArrayBase64Serializer::class)
    val encryptedBlockFormat: ByteArray = ByteArray(0)
) {
    /**
     * Derives the format encryption key from a password.
     *
     * @param password The repository password
     * @return 32-byte encryption key
     */
    fun deriveFormatEncryptionKeyFromPassword(password: String): ByteArray {
        return deriveKeyFromPassword(
            password = password,
            salt = uniqueID,
            keyLength = FORMAT_BLOB_ENCRYPTION_KEY_SIZE,
            algorithm = keyDerivationAlgorithm
        )
    }

    /**
     * Decrypts the repository configuration using the master key.
     *
     * @param masterKey The derived encryption key
     * @return The decrypted repository configuration
     * @throws IllegalStateException if decryption fails
     */
    fun decryptRepositoryConfig(masterKey: ByteArray): RepositoryConfig {
        require(encryption == AES256_GCM_ENCRYPTION) {
            "Unknown encryption algorithm: '$encryption'"
        }

        val plainText = decryptRepositoryBlobBytesAes256Gcm(
            encryptedBlockFormat,
            masterKey,
            uniqueID
        ) ?: throw IllegalStateException("Unable to decrypt repository format")

        val erc = json.decodeFromString<EncryptedRepositoryConfig>(plainText.decodeToString())
        return erc.format
    }

    /**
     * Encrypts the repository configuration and stores it in encryptedBlockFormat.
     *
     * @param config The repository configuration to encrypt
     * @param masterKey The encryption key
     * @return A new KopiaRepositoryJson with the encrypted config
     */
    fun encryptRepositoryConfig(config: RepositoryConfig, masterKey: ByteArray): KopiaRepositoryJson {
        require(encryption == AES256_GCM_ENCRYPTION) {
            "Unknown encryption algorithm: '$encryption'"
        }

        val data = jsonWriter.encodeToString(EncryptedRepositoryConfig(config))
        val encrypted = encryptRepositoryBlobBytesAes256Gcm(
            data.toByteArray(Charsets.UTF_8),
            masterKey,
            uniqueID
        )

        return copy(encryptedBlockFormat = encrypted)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KopiaRepositoryJson) return false

        if (tool != other.tool) return false
        if (buildVersion != other.buildVersion) return false
        if (buildInfo != other.buildInfo) return false
        if (!uniqueID.contentEquals(other.uniqueID)) return false
        if (keyDerivationAlgorithm != other.keyDerivationAlgorithm) return false
        if (encryption != other.encryption) return false
        if (!encryptedBlockFormat.contentEquals(other.encryptedBlockFormat)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tool.hashCode()
        result = 31 * result + buildVersion.hashCode()
        result = 31 * result + buildInfo.hashCode()
        result = 31 * result + uniqueID.contentHashCode()
        result = 31 * result + keyDerivationAlgorithm.hashCode()
        result = 31 * result + encryption.hashCode()
        result = 31 * result + encryptedBlockFormat.contentHashCode()
        return result
    }

    companion object {
        const val TOOL_NAME = "kopia"
        const val AES256_GCM_ENCRYPTION = "AES256_GCM"
        const val DEFAULT_KEY_DERIVATION_ALGORITHM = "scrypt-65536-8-1"
        const val FORMAT_BLOB_ENCRYPTION_KEY_SIZE = 32

        /** Blob ID for the repository format blob. */
        const val FORMAT_BLOB_ID = "kopia.repository"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** JSON encoder that always writes defaults. Required for Go Kopia compatibility. */
        private val jsonWriter = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Parses the kopia.repository blob JSON.
         *
         * @param data The raw blob data
         * @return Parsed KopiaRepositoryJson
         */
        fun parse(data: ByteArray): KopiaRepositoryJson {
            return json.decodeFromString(data.decodeToString())
        }

        /**
         * Creates a new KopiaRepositoryJson for a new repository.
         *
         * @param buildVersion The Kopia version string
         * @param keyDerivationAlgorithm The key derivation algorithm to use
         * @return A new KopiaRepositoryJson with a fresh uniqueID
         */
        fun create(
            buildVersion: String = "",
            keyDerivationAlgorithm: String = DEFAULT_KEY_DERIVATION_ALGORITHM
        ): KopiaRepositoryJson {
            val uniqueID = ByteArray(32)
            SecureRandom().nextBytes(uniqueID)

            return KopiaRepositoryJson(
                buildVersion = buildVersion,
                uniqueID = uniqueID,
                keyDerivationAlgorithm = keyDerivationAlgorithm
            )
        }

        /**
         * Serializes this to JSON bytes.
         */
        fun KopiaRepositoryJson.toJson(): ByteArray {
            return jsonWriter.encodeToString(this).toByteArray(Charsets.UTF_8)
        }
    }
}

// AES-256-GCM encryption/decryption for repository format blob
//
// Go Kopia uses a two-level key derivation:
// 1. Password -> MasterKey (via scrypt or pbkdf2)
// 2. MasterKey -> AES Key (via HKDF with info="AES")
// 3. MasterKey -> AuthData (via HKDF with info="CHECKSUM")
//
// The salt for HKDF is the uniqueID (repositoryID).

private const val GCM_NONCE_SIZE = 12
private const val GCM_TAG_SIZE = 128 // bits
private const val HKDF_KEY_LENGTH = 32

// Purpose strings matching Go Kopia's constants
private const val PURPOSE_AES_KEY = "AES"
private const val PURPOSE_AUTH_DATA = "CHECKSUM"

/**
 * Derives the AES key and authentication data from master key using HKDF.
 *
 * @param masterKey The derived master key (from password)
 * @param salt The salt (uniqueID/repositoryID)
 * @return Pair of (aesKey, authData)
 */
private fun deriveAesKeyAndAuthData(masterKey: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
    val hkdf = HkdfSha256KeyDerivation()

    val aesKey = hkdf.derive(
        masterKey = masterKey,
        salt = salt,
        info = PURPOSE_AES_KEY.toByteArray(Charsets.UTF_8),
        length = HKDF_KEY_LENGTH
    )

    val authData = hkdf.derive(
        masterKey = masterKey,
        salt = salt,
        info = PURPOSE_AUTH_DATA.toByteArray(Charsets.UTF_8),
        length = HKDF_KEY_LENGTH
    )

    return aesKey to authData
}

/**
 * Decrypts repository blob bytes using AES-256-GCM.
 *
 * Go Kopia derives the actual AES key and auth data from the master key
 * using HKDF before performing decryption.
 *
 * @param ciphertext The encrypted data (nonce + ciphertext + tag)
 * @param masterKey The 32-byte master key (derived from password)
 * @param salt The salt (uniqueID) used for HKDF derivation
 * @return The decrypted plaintext, or null if decryption fails
 */
private fun decryptRepositoryBlobBytesAes256Gcm(
    ciphertext: ByteArray,
    masterKey: ByteArray,
    salt: ByteArray
): ByteArray? {
    if (ciphertext.size < GCM_NONCE_SIZE) {
        return null
    }

    return try {
        // Derive AES key and auth data using HKDF (matching Go Kopia)
        val (aesKey, authData) = deriveAesKeyAndAuthData(masterKey, salt)

        val nonce = ciphertext.copyOfRange(0, GCM_NONCE_SIZE)
        val encryptedData = ciphertext.copyOfRange(GCM_NONCE_SIZE, ciphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE, nonce)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(authData)
        cipher.doFinal(encryptedData)
    } catch (e: Exception) {
        null
    }
}

/**
 * Encrypts repository blob bytes using AES-256-GCM.
 *
 * Go Kopia derives the actual AES key and auth data from the master key
 * using HKDF before performing encryption.
 *
 * @param plaintext The data to encrypt
 * @param masterKey The 32-byte master key (derived from password)
 * @param salt The salt (uniqueID) used for HKDF derivation
 * @return The encrypted data (nonce + ciphertext + tag)
 */
private fun encryptRepositoryBlobBytesAes256Gcm(
    plaintext: ByteArray,
    masterKey: ByteArray,
    salt: ByteArray
): ByteArray {
    // Derive AES key and auth data using HKDF (matching Go Kopia)
    val (aesKey, authData) = deriveAesKeyAndAuthData(masterKey, salt)

    val nonce = ByteArray(GCM_NONCE_SIZE)
    SecureRandom().nextBytes(nonce)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(aesKey, "AES")
    val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE, nonce)

    cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
    cipher.updateAAD(authData)
    val encrypted = cipher.doFinal(plaintext)

    return nonce + encrypted
}
