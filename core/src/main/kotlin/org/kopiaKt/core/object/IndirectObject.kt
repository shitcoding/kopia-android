package org.kopiaKt.core.`object`

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kopiaKt.core.content.ObjectId

/**
 * Magic stream identifier for indirect objects.
 * Must match Go's constant exactly for compatibility.
 */
const val INDIRECT_STREAM_ID = "kopia:indirect"

/**
 * Content prefix used for indirect object content.
 * This ensures indirect objects are stored in metadata (q) blobs.
 */
const val INDIRECT_CONTENT_PREFIX = 'x'

/**
 * Represents an entry in an indirect object stream.
 *
 * This maps directly to Go's `IndirectObjectEntry` struct:
 * ```go
 * type IndirectObjectEntry struct {
 *     Start  int64 `json:"s,omitempty"`
 *     Length int64 `json:"l,omitempty"`
 *     Object ID    `json:"o"`
 * }
 * ```
 *
 * JSON format example from Go:
 * ```json
 * {"l":1698099,"o":"D13ea27f9ad891ad4a2edfa983906863d"}
 * {"s":1698099,"l":1302081,"o":"De8ca8327cd3af5f4edbd5ed1009c525e"}
 * ```
 *
 * @property start The starting byte offset of this entry in the overall object.
 *                 First entry typically has start=0 and omits the field.
 * @property length The length of this entry in bytes.
 * @property objectId The ObjectId of the content block for this entry.
 */
@Serializable
data class IndirectObjectEntry(
    @SerialName("s")
    val start: Long = 0,

    @SerialName("l")
    val length: Long,

    @SerialName("o")
    val objectId: ObjectIdJson,
) {
    /**
     * Returns the end offset (exclusive) of this entry.
     */
    fun endOffset(): Long = start + length

    companion object {
        /**
         * Creates an entry from the given parameters.
         */
        fun create(start: Long, length: Long, objectId: ObjectId): IndirectObjectEntry = IndirectObjectEntry(
            start = start,
            length = length,
            objectId = ObjectIdJson(objectId.toString()),
        )
    }
}

/**
 * Wrapper for ObjectId JSON serialization that matches Go's format.
 * Go serializes object IDs as plain strings in JSON.
 */
@Serializable
@JvmInline
value class ObjectIdJson(val value: String) {
    fun toObjectId(): ObjectId = ObjectId.parse(value)
}

/**
 * Represents an indirect object containing entries pointing to content blocks.
 *
 * This maps directly to Go's `indirectObject` struct:
 * ```go
 * type indirectObject struct {
 *     StreamID string                `json:"stream"`
 *     Entries  []IndirectObjectEntry `json:"entries"`
 * }
 * ```
 *
 * JSON format example:
 * ```json
 * {"stream":"kopia:indirect","entries":[
 *   {"l":1698099,"o":"D13ea27f9ad891ad4a2edfa983906863d"},
 *   {"s":1698099,"l":1302081,"o":"De8ca8327cd3af5f4edbd5ed1009c525e"}
 * ]}
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IndirectObject(
    @SerialName("stream")
    @EncodeDefault // Always encode stream ID for Go compatibility
    val streamId: String = INDIRECT_STREAM_ID,

    @SerialName("entries")
    val entries: List<IndirectObjectEntry>,
) {
    init {
        require(streamId == INDIRECT_STREAM_ID) {
            "Invalid stream ID: expected '$INDIRECT_STREAM_ID', got '$streamId'"
        }
    }

    /**
     * Calculates the total length of all entries.
     */
    fun totalLength(): Long = if (entries.isEmpty()) 0L else entries.last().endOffset()

    companion object {
        private val json = Json {
            // Don't encode default values (matching Go's omitempty)
            encodeDefaults = false
            // Ignore unknown keys for forward compatibility
            ignoreUnknownKeys = true
        }

        /**
         * Creates an IndirectObject from the given entries.
         */
        fun create(entries: List<IndirectObjectEntry>): IndirectObject = IndirectObject(entries = entries)

        /**
         * Serializes the indirect object to JSON bytes.
         *
         * @param obj The indirect object to serialize
         * @return JSON bytes matching Go's format
         */
        fun encode(obj: IndirectObject): ByteArray = json.encodeToString(IndirectObject.serializer(), obj).toByteArray()

        /**
         * Deserializes an indirect object from JSON bytes.
         *
         * @param data JSON bytes
         * @return The parsed indirect object
         * @throws kotlinx.serialization.SerializationException if parsing fails
         */
        fun decode(data: ByteArray): IndirectObject = json.decodeFromString(IndirectObject.serializer(), data.decodeToString())
    }
}
