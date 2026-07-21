package org.kopiaKt.android.storage

import android.net.Uri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer for Android Uri.
 */
object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uri) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri = Uri.parse(decoder.decodeString())
}

/**
 * Options for Storage Access Framework (SAF) based blob storage.
 *
 * SAF is Android's mechanism for accessing external storage, SD cards,
 * and storage provided by other apps through the Storage Access Framework.
 */
@Serializable
data class SafOptions(
    /**
     * Tree URI for the storage location.
     * Obtained via Intent.ACTION_OPEN_DOCUMENT_TREE.
     */
    @Serializable(with = UriSerializer::class)
    val treeUri: Uri,

    /**
     * Directory sharding configuration.
     * Defaults to [1] for single-character shard directories.
     * For example, [1] means "p/pack-abc..." while [1, 3] means "p/ack/pack-abc..."
     */
    val directoryShards: List<Int> = listOf(1),

    /**
     * Maximum length of blob ID that won't be sharded.
     * Blob IDs shorter than this will be stored in the root directory.
     */
    val maxNonShardedLength: Int = 20,

    /**
     * Whether to use atomic writes (temp file + rename).
     * SAF supports this on most filesystems.
     */
    val atomicWrites: Boolean = true,

    /**
     * If true, the storage is opened in read-only mode.
     * Write operations will fail.
     */
    val readOnly: Boolean = false,
)

/**
 * Sharding parameters persisted in the SAF storage.
 * Stored in .shards file at the root of the repository.
 */
@Serializable
data class SafShardingParameters(
    /**
     * Default shards to use for blob IDs.
     */
    val default: List<Int> = listOf(1),

    /**
     * Maximum length of blob ID that won't be sharded.
     */
    val maxNonShardedLength: Int = 20,

    /**
     * Per-prefix overrides for sharding.
     */
    val overrides: List<SafPrefixShards> = emptyList(),
)

/**
 * Per-prefix sharding override.
 */
@Serializable
data class SafPrefixShards(
    /**
     * Blob ID prefix this override applies to.
     */
    val prefix: String,

    /**
     * Shards to use for blobs with this prefix.
     */
    val shards: List<Int>,
)
