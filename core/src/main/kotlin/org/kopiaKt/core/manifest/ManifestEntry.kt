package org.kopiaKt.core.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Internal representation of a manifest entry stored in the repository.
 *
 * This matches the Go serialized format:
 * ```json
 * {
 *   "id": "...",
 *   "labels": {"type": "...", ...},
 *   "modified": "2024-01-20T12:00:00Z",
 *   "deleted": false,
 *   "data": {...}
 * }
 * ```
 *
 * @property id Unique manifest identifier
 * @property labels Key-value labels for querying
 * @property modTime Modification timestamp (UTC)
 * @property deleted Whether this entry marks a deletion
 * @property content The raw JSON payload
 */
@Serializable
internal data class ManifestEntry(
    val id: String,
    val labels: Map<String, String>,
    @SerialName("modified")
    @Serializable(with = InstantSerializer::class)
    val modTime: Instant,
    val deleted: Boolean = false,
    @SerialName("data")
    val content: JsonElement? = null
)

/**
 * Container for manifest entries stored in a single content block.
 *
 * Go format:
 * ```json
 * {
 *   "entries": [...]
 * }
 * ```
 */
@Serializable
internal data class ManifestContainer(
    val entries: List<ManifestEntry>
)

/**
 * Public metadata about a manifest entry.
 *
 * This is returned from Find and GetMetadata operations without
 * deserializing the full payload.
 *
 * @property id Unique manifest identifier
 * @property length Length of the serialized payload in bytes
 * @property labels Key-value labels
 * @property modTime Modification timestamp (UTC)
 */
data class EntryMetadata(
    val id: ManifestId,
    val length: Int,
    val labels: Map<String, String>,
    val modTime: Instant
)

/**
 * Exception thrown when a manifest is not found.
 */
class ManifestNotFoundException(id: ManifestId) :
    Exception("Manifest not found: $id")
