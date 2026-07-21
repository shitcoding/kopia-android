package org.kopiaKt.app.domain.repository

/**
 * Options for creating a new Kopia repository.
 *
 * All fields are optional -- null values mean "use Kopia defaults":
 * - hash: BLAKE2B-256-128
 * - encryption: AES256-GCM-HMAC-SHA256
 * - splitter: DYNAMIC-4M-BUZHASH
 */
data class RepositoryCreateOptions(
    /** Human-readable description for this repository connection. */
    val description: String = "",

    /**
     * Hash algorithm identifier (e.g. "BLAKE2B-256-128", "HMAC-SHA256-128").
     * Null means use the default.
     */
    val hashAlgorithm: String? = null,

    /**
     * Encryption algorithm identifier (e.g. "AES256-GCM-HMAC-SHA256").
     * Null means use the default.
     */
    val encryptionAlgorithm: String? = null,

    /**
     * Splitter algorithm identifier (e.g. "DYNAMIC-4M-BUZHASH", "FIXED-1M").
     * Null means use the default.
     */
    val splitterAlgorithm: String? = null,

    /**
     * Key derivation algorithm string (e.g. "scrypt-65536-8-1").
     * Null means use the core default (scrypt-65536-8-1).
     * Override via BuildConfig to use weaker parameters in debug builds.
     */
    val keyDerivationAlgorithm: String? = null,
)
