package org.kopiaKt.storage.sftp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for the SFTP `.shards` parsing, sharded-path computation, and legacy fallback. No
 * Docker required.
 *
 * The regressions these lock: (a) an SFTP repo created with `--flat` writes `.shards={"default":[]}`
 * and stores blobs UNSHARDED at the root — ignoring it computes wrong paths and reads the repo empty;
 * (b) a legacy repo with NO `.shards` must fall back to Go's `[3,3]` on open (not `[1,3]`); (c) Go's
 * per-prefix `.shards` overrides must be honored.
 */
class SftpShardingTest {

    private fun config(
        default: List<Int> = listOf(1, 3),
        maxNonShardedLength: Int = 20,
        overrides: List<SftpPrefixShards> = emptyList(),
    ) = SftpShardsConfig(default, maxNonShardedLength, overrides)

    // --- parse ---

    @Test
    fun parse_flatRepoShards_yieldsEmptyDefault() {
        val cfg = SftpSharding.parse("""{"default":[],"maxNonShardedLength":20}""")
        assertThat(cfg.default).isEmpty()
        assertThat(cfg.maxNonShardedLength).isEqualTo(20)
    }

    @Test
    fun parse_shardedRepoShards() {
        val cfg = SftpSharding.parse("""{"default":[1,3],"maxNonShardedLength":20}""")
        assertThat(cfg.default).containsExactly(1, 3).inOrder()
    }

    @Test
    fun parse_ignoresUnknownGoFields() {
        val cfg = SftpSharding.parse(
            """{"default":[2,2],"maxNonShardedLength":10,"unshardedList":["kopia.repository"]}""",
        )
        assertThat(cfg.default).containsExactly(2, 2).inOrder()
        assertThat(cfg.maxNonShardedLength).isEqualTo(10)
    }

    @Test
    fun parse_overrides() {
        val cfg = SftpSharding.parse(
            """{"default":[1,3],"maxNonShardedLength":20,"overrides":[{"prefix":"m","shards":[2,2]}]}""",
        )
        assertThat(cfg.overrides).hasSize(1)
        assertThat(cfg.overrides[0].prefix).isEqualTo("m")
        assertThat(cfg.overrides[0].shards).containsExactly(2, 2).inOrder()
    }

    // --- shardedPath ---

    @Test
    fun shardedPath_flatShards_placesLongBlobAtRoot() {
        val id = "xn0_abcdef0123456789abcdef0123" // 30 chars: WOULD be sharded under [1,3]
        val (dir, name) = SftpSharding.shardedPath(id, config(default = emptyList()))
        assertThat(dir).isEmpty()
        assertThat(name).isEqualTo(id)
    }

    @Test
    fun shardedPath_defaultShards_shardsLongBlob() {
        val (dir, name) = SftpSharding.shardedPath("xn0_abcdef0123456789abcdef0123", config(default = listOf(1, 3)))
        assertThat(dir).isEqualTo("x/n0_")
        assertThat(name).isEqualTo("abcdef0123456789abcdef0123")
    }

    @Test
    fun shardedPath_shortBlob_notSharded() {
        val (dir, name) = SftpSharding.shardedPath("short-id", config(default = listOf(1, 3)))
        assertThat(dir).isEmpty()
        assertThat(name).isEqualTo("short-id")
    }

    @Test
    fun shardedPath_prefixOverride_usesOverrideShards() {
        val cfg = config(
            default = listOf(1, 3),
            overrides = listOf(SftpPrefixShards(prefix = "m", shards = listOf(2, 2))),
        )
        val id = "m123456789012345678901234" // 25 chars, prefix "m"
        val (dir, name) = SftpSharding.shardedPath(id, cfg)
        assertThat(dir).isEqualTo("m1/23") // [2,2], not the default [1,3] ("m/123")
        assertThat(name).isEqualTo("456789012345678901234")
    }

    // --- encode ---

    @Test
    fun encode_roundTripsThroughParse() {
        val cfg = config(default = emptyList(), maxNonShardedLength = 20)
        assertThat(SftpSharding.parse(SftpSharding.encode(cfg))).isEqualTo(cfg)
    }

    // --- fallbackShards (no .shards on disk) ---

    @Test
    fun fallbackShards_open_legacyDefaultsTo33() {
        // Go: opening a repo with DirectoryShards==nil uses [3,3].
        assertThat(SftpSharding.fallbackShards(isCreate = false, optionShards = null)).containsExactly(3, 3).inOrder()
    }

    @Test
    fun fallbackShards_create_defaultsTo13() {
        assertThat(SftpSharding.fallbackShards(isCreate = true, optionShards = null)).containsExactly(1, 3).inOrder()
    }

    @Test
    fun fallbackShards_emptyMeansFlat() {
        // Go distinguishes nil (unset) from empty (flat); an explicit empty list must stay flat.
        assertThat(SftpSharding.fallbackShards(isCreate = true, optionShards = emptyList())).isEmpty()
        assertThat(SftpSharding.fallbackShards(isCreate = false, optionShards = emptyList())).isEmpty()
    }

    @Test
    fun fallbackShards_explicitWins() {
        assertThat(SftpSharding.fallbackShards(isCreate = false, optionShards = listOf(2, 2)))
            .containsExactly(2, 2).inOrder()
    }
}
