package org.kopiaKt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security

/**
 * task-80 investigation, on ART — **prints, does not assert.**
 *
 * simpleperf on a real 1.44 GiB backup put BouncyCastle's BLAKE2b at ~56 % of all backup CPU, of
 * which 23.7 percentage points are `org.bouncycastle.util.Longs.rotateRight` (13.05 %) stacked on
 * `java.lang.Long.rotateRight` (10.65 %) — two frames around what is one `ror` instruction on arm64.
 *
 * The task says, correctly, that hot rotate frames do **not** prove ART failed to intrinsify the
 * rotate: a frame can be hot simply because it runs billions of times. So this settles the question
 * by measurement instead of by reading method names, and it settles it **on ART**, because pt19
 * established that a desktop JVM A/B of exactly this class of change measures nothing.
 *
 * Three questions, cheapest first:
 *  1. Does the platform expose BLAKE2b through the JCE at all? If it does, the whole problem is a
 *     provider swap and nothing needs writing.
 *  2. Is `Long.rotateRight` intrinsified? Compared against the inline `(x ushr n) or (x shl 64-n)`
 *     that a hand-written digest would use. Equal speed means the rotate lead is dead.
 *  3. What does BouncyCastle's BLAKE2b actually cost per byte here, so "56 % of CPU" has a MB/s
 *     attached and any future change has a baseline to beat.
 */
@RunWith(AndroidJUnit4::class)
class Blake2bArtInvestigationTest {

    @Test
    fun q1_isBlake2bAvailableFromAnyPlatformProvider() {
        val hits = Security.getProviders().flatMap { provider ->
            provider.services
                .filter { it.algorithm.contains("BLAKE", ignoreCase = true) }
                .map { "${provider.name}/${it.type}/${it.algorithm}" }
        }
        println("BLAKE2_JCE_SERVICES: ${if (hits.isEmpty()) "NONE" else hits.joinToString()}")
        println("BLAKE2_PROVIDERS: ${Security.getProviders().joinToString { it.name }}")
    }

    @Test
    fun q2_isLongRotateRightIntrinsified() {
        // Same work both ways, only the rotate differs. `acc` is consumed by the return value so
        // neither loop can be optimised away wholesale.
        fun viaLibrary(rounds: Int): Long {
            var acc = 0x0123456789ABCDEFL
            repeat(rounds) { acc = java.lang.Long.rotateRight(acc, ROTATIONS[it and 3]) xor it.toLong() }
            return acc
        }

        fun viaInline(rounds: Int): Long {
            var acc = 0x0123456789ABCDEFL
            repeat(rounds) {
                val n = ROTATIONS[it and 3]
                acc = ((acc ushr n) or (acc shl (64 - n))) xor it.toLong()
            }
            return acc
        }

        // Both must agree, or the comparison is between two different computations.
        check(viaLibrary(1000) == viaInline(1000)) { "the two rotates disagree" }

        // Let the JIT compile both before either is timed.
        repeat(3) {
            viaLibrary(ROUNDS)
            viaInline(ROUNDS)
        }

        val libraryNanos = timeOf { viaLibrary(ROUNDS) }
        val inlineNanos = timeOf { viaInline(ROUNDS) }
        println(
            "ROTATE_ART: library=%.2f ns/op inline=%.2f ns/op ratio=%.2fx"
                .format(
                    libraryNanos.toDouble() / ROUNDS,
                    inlineNanos.toDouble() / ROUNDS,
                    libraryNanos.toDouble() / inlineNanos,
                ),
        )
    }

    @Test
    fun q3_whatDoesBouncyCastleBlake2bCostPerByte() {
        val data = ByteArray(8 * MIB) { (it * 31).toByte() }
        val out = ByteArray(32)

        fun hashAll() {
            val digest = Blake2bDigest(256)
            digest.update(data, 0, data.size)
            digest.doFinal(out, 0)
        }

        repeat(2) { hashAll() }

        val passes = 4
        val nanos = timeOf { repeat(passes) { hashAll() } }
        val megabytesPerSecond = (8.0 * passes) / (nanos / 1e9)
        println("BLAKE2B_BC_ART: %.0f MB/s (8 MiB x %d)".format(megabytesPerSecond, passes))
    }

    private fun timeOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val ROUNDS = 20_000_000

        /** BLAKE2b's own rotation constants, so the branchless case matches the real workload. */
        val ROTATIONS = intArrayOf(32, 24, 16, 63)
    }
}
