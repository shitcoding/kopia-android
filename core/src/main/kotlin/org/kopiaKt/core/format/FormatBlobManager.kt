package org.kopiaKt.core.format

import org.kopiaKt.core.blob.BlobId
import org.kopiaKt.core.blob.BlobNotFoundException
import org.kopiaKt.core.blob.BlobStorage
import org.kopiaKt.core.format.KopiaRepositoryJson.Companion.toJson

/**
 * Manages reading and writing the repository format blob (kopia.repository).
 *
 * The format blob contains:
 * - Repository metadata (tool version, unique ID)
 * - Key derivation parameters
 * - Encrypted repository configuration (hash, encryption, keys, etc.)
 */
class FormatBlobManager(
    private val storage: BlobStorage,
) {
    /**
     * Reads and parses the repository format blob.
     *
     * @return The parsed KopiaRepositoryJson
     * @throws FormatBlobNotFoundException if the format blob doesn't exist
     * @throws FormatBlobParseException if the format blob is invalid
     */
    suspend fun readFormatBlob(): KopiaRepositoryJson {
        val blobId = BlobId(KopiaRepositoryJson.FORMAT_BLOB_ID)

        // ONLY a genuinely absent blob may become FormatBlobNotFoundException: createRepository uses
        // that exception as its "no repository here" probe, so classifying a transient read failure
        // (network error, throttling, expired credentials) as "not found" would let create overwrite
        // an existing kopia.repository with a fresh uniqueID and master key, permanently orphaning
        // every blob in that repository. Everything else must propagate.
        val data = try {
            storage.getBlob(blobId, 0, -1)
        } catch (e: BlobNotFoundException) {
            throw FormatBlobNotFoundException("Format blob not found: ${e.message}", e)
        }

        return try {
            KopiaRepositoryJson.parse(data)
        } catch (e: Exception) {
            throw FormatBlobParseException("Failed to parse format blob: ${e.message}", e)
        }
    }

    /**
     * Writes the repository format blob.
     *
     * @param formatJson The format JSON to write
     */
    suspend fun writeFormatBlob(formatJson: KopiaRepositoryJson) {
        val blobId = BlobId(KopiaRepositoryJson.FORMAT_BLOB_ID)
        storage.putBlob(blobId, formatJson.toJson())
    }

    /**
     * Opens a repository with the given password.
     *
     * @param password The repository password
     * @return The decrypted repository configuration
     * @throws FormatBlobNotFoundException if the format blob doesn't exist
     * @throws InvalidPasswordException if the password is incorrect
     */
    suspend fun openRepository(password: String): OpenRepositoryResult {
        val formatJson = readFormatBlob()

        // Derive encryption key from password
        val formatEncryptionKey = formatJson.deriveFormatEncryptionKeyFromPassword(password)

        // Decrypt repository configuration
        val config = try {
            formatJson.decryptRepositoryConfig(formatEncryptionKey)
        } catch (e: Exception) {
            throw InvalidPasswordException("Invalid password or corrupted format blob", e)
        }

        // Validate format version
        validateFormatVersion(config.version)

        return OpenRepositoryResult(
            formatJson = formatJson,
            config = config,
            formatEncryptionKey = formatEncryptionKey,
        )
    }

    /**
     * Creates a new repository with the given password and configuration.
     *
     * @param password The repository password
     * @param config The repository configuration
     * @param buildVersion Optional build version string
     * @param keyDerivationAlgorithm Key derivation algorithm to use
     * @return The created format JSON and encryption key
     * @throws RepositoryAlreadyExistsException if a repository already exists
     */
    suspend fun createRepository(
        password: String,
        config: RepositoryConfig,
        buildVersion: String = "",
        keyDerivationAlgorithm: String = KopiaRepositoryJson.DEFAULT_KEY_DERIVATION_ALGORITHM,
    ): CreateRepositoryResult {
        // Check if repository already exists
        try {
            readFormatBlob()
            throw RepositoryAlreadyExistsException("Repository already exists")
        } catch (e: FormatBlobNotFoundException) {
            // Good, repository doesn't exist
        }

        // Create format JSON
        var formatJson = KopiaRepositoryJson.create(
            buildVersion = buildVersion,
            keyDerivationAlgorithm = keyDerivationAlgorithm,
        )

        // Derive encryption key
        val formatEncryptionKey = formatJson.deriveFormatEncryptionKeyFromPassword(password)

        // Encrypt configuration
        formatJson = formatJson.encryptRepositoryConfig(config, formatEncryptionKey)

        // Write format blob
        writeFormatBlob(formatJson)

        return CreateRepositoryResult(
            formatJson = formatJson,
            config = config,
            formatEncryptionKey = formatEncryptionKey,
        )
    }

    /**
     * Changes the repository password.
     *
     * @param currentPassword The current password
     * @param newPassword The new password
     * @param newKeyDerivationAlgorithm Optional new key derivation algorithm
     * @throws InvalidPasswordException if the current password is incorrect
     * @throws UnsupportedOperationException if password change is not supported
     */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
        newKeyDerivationAlgorithm: String? = null,
    ) {
        // Open with current password
        val result = openRepository(currentPassword)

        // Check if password change is supported
        if (!result.config.enablePasswordChange) {
            throw UnsupportedOperationException(
                "Password change not supported for format version ${result.config.version}",
            )
        }

        // Create new format JSON with new password
        val newAlgorithm = newKeyDerivationAlgorithm ?: result.formatJson.keyDerivationAlgorithm

        var newFormatJson = result.formatJson.copy(
            keyDerivationAlgorithm = newAlgorithm,
        )

        // Re-encrypt with new password (keep same uniqueID for new key derivation)
        val newFormatEncryptionKey = newFormatJson.deriveFormatEncryptionKeyFromPassword(newPassword)
        newFormatJson = newFormatJson.encryptRepositoryConfig(result.config, newFormatEncryptionKey)

        // Write updated format blob
        writeFormatBlob(newFormatJson)
    }

    /**
     * Validates that the format version is supported.
     */
    private fun validateFormatVersion(version: Int) {
        require(version >= FormatVersion.MIN_SUPPORTED_READ.value) {
            "Format version $version is too old (minimum supported: ${FormatVersion.MIN_SUPPORTED_READ.value})"
        }
        require(version <= FormatVersion.MAX_SUPPORTED_READ.value) {
            "Format version $version is too new (maximum supported: ${FormatVersion.MAX_SUPPORTED_READ.value})"
        }
    }
}

/**
 * Result of opening a repository.
 */
data class OpenRepositoryResult(
    val formatJson: KopiaRepositoryJson,
    val config: RepositoryConfig,
    val formatEncryptionKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpenRepositoryResult) return false
        if (formatJson != other.formatJson) return false
        if (config != other.config) return false
        if (!formatEncryptionKey.contentEquals(other.formatEncryptionKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = formatJson.hashCode()
        result = 31 * result + config.hashCode()
        result = 31 * result + formatEncryptionKey.contentHashCode()
        return result
    }
}

/**
 * Result of creating a repository.
 */
data class CreateRepositoryResult(
    val formatJson: KopiaRepositoryJson,
    val config: RepositoryConfig,
    val formatEncryptionKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CreateRepositoryResult) return false
        if (formatJson != other.formatJson) return false
        if (config != other.config) return false
        if (!formatEncryptionKey.contentEquals(other.formatEncryptionKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = formatJson.hashCode()
        result = 31 * result + config.hashCode()
        result = 31 * result + formatEncryptionKey.contentHashCode()
        return result
    }
}

// Exceptions

/**
 * Thrown when the format blob is not found.
 */
class FormatBlobNotFoundException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the format blob cannot be parsed.
 */
class FormatBlobParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the password is invalid.
 */
class InvalidPasswordException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when trying to create a repository that already exists.
 */
class RepositoryAlreadyExistsException(message: String) : Exception(message)
