package org.kopiaKt.storage.sftp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kopia's default directory sharding: a 1-char level then a 3-char level (new repos). */
private val DEFAULT_SHARDS: List<Int> = listOf(1, 3)

/** Go's sharding for opening a legacy repo that has no `.shards` file. */
private val LEGACY_OPEN_SHARDS: List<Int> = listOf(3, 3)

/** Blob-id length at/below which blobs are never sharded (Go Kopia default). */
private const val DEFAULT_MAX_NON_SHARDED_LENGTH = 20

/**
 * Sharding configuration persisted in a repository's `.shards` file (Go Kopia format).
 *
 * `default` is the list of per-level directory-name prefix lengths (e.g. `[1, 3]`); an EMPTY list
 * means a flat, unsharded repo (`kopia repository create … --flat`), which stores every blob at the
 * repository root. `overrides` are per-prefix shard lists that take precedence over `default` for
 * blob ids starting with `prefix`. `maxNonShardedLength` is the blob-id length at or below which
 * blobs are never sharded regardless of `default`.
 */
@Serializable
internal data class SftpShardsConfig(
    val default: List<Int> = DEFAULT_SHARDS,
    val maxNonShardedLength: Int = DEFAULT_MAX_NON_SHARDED_LENGTH,
    val overrides: List<SftpPrefixShards> = emptyList(),
)

/** A per-prefix sharding override within a `.shards` file (Go `override` entry). */
@Serializable
internal data class SftpPrefixShards(
    val prefix: String,
    val shards: List<Int>,
)

/** Pure helpers for reading `.shards`, computing sharded paths, and resolving fallbacks — no server. */
internal object SftpSharding {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun parse(content: String): SftpShardsConfig = json.decodeFromString(content)

    fun encode(config: SftpShardsConfig): String = json.encodeToString(SftpShardsConfig.serializer(), config)

    /**
     * Fallback sharding when a repo has NO `.shards` file. Mirrors Go's
     * `if opt.DirectoryShards == nil { isCreate ? [1,3] : [3,3] }`: a null (unset) list defaults to
     * `[1,3]` on create and Go's legacy `[3,3]` on open, while a caller-supplied list — INCLUDING an
     * empty list (flat) — is used verbatim.
     */
    fun fallbackShards(isCreate: Boolean, optionShards: List<Int>?): List<Int> = when {
        optionShards != null -> optionShards
        isCreate -> DEFAULT_SHARDS
        else -> LEGACY_OPEN_SHARDS
    }

    /** The shard lengths that apply to [id]: the first matching prefix override, else the default. */
    private fun shardsForId(id: String, config: SftpShardsConfig): List<Int> {
        for (override in config.overrides) {
            if (id.startsWith(override.prefix)) {
                return override.shards
            }
        }
        return config.default
    }

    /**
     * Splits [id] into (directory-path, file-name) per [config]. An empty shard list (or an id at or
     * below `maxNonShardedLength`) yields an empty directory — i.e. the blob lives at the root.
     */
    fun shardedPath(id: String, config: SftpShardsConfig): Pair<String, String> {
        if (id.length <= config.maxNonShardedLength) {
            return "" to id
        }
        var remaining = id
        var path = ""
        for (size in shardsForId(id, config)) {
            if (remaining.length <= size) {
                break
            }
            path = if (path.isEmpty()) remaining.substring(0, size) else "$path/${remaining.substring(0, size)}"
            remaining = remaining.substring(size)
        }
        return path to remaining
    }
}
